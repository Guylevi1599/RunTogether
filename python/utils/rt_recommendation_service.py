# rt_recommendation_service.py
"""
FastAPI service that wraps the real-time recommendations engine.

Run:
  pip install -r requirements.txt
  uvicorn rt_recommendation_service:app --host 0.0.0.0 --port 8000 --reload

Notes:
- Place under RunTogether/services/ OR set env RUN_TOGETHER_ROOT to your project root.
- Expects (as saved by train_models_with_features.py):
    models/heart_rate_model.pkl
    models/speed_model.pkl
    models/scaler_hr.pkl
    models/scaler_speed.pkl
    models/model_metadata.json   (must include hr_feature_cols, speed_feature_cols)
- Uses: utils/features.py  (add_features(df))
"""

from __future__ import annotations
from typing import Optional, List, Dict, Any
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
import os
import sys
from pathlib import Path
import threading
import time
import json

import numpy as np
import pandas as pd
import joblib

# ===================== Project Root & Paths =====================
RUN_TOGETHER_ROOT = os.environ.get("RUN_TOGETHER_ROOT")
if RUN_TOGETHER_ROOT:
    ROOT_DIR = Path(RUN_TOGETHER_ROOT).resolve()
else:
    ROOT_DIR = Path(__file__).resolve().parents[1]

if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

# IMPORTANT: models are saved to ROOT_DIR / "models" by the training script
MODELS_DIR = ROOT_DIR / "models"

# --- Import your features builder ---
from utils.features import add_features  # noqa: E402


# ===================== Safe loaders =====================
def _safe_load(path: Path):
    try:
        return joblib.load(path)
    except Exception:
        return None


# Models & scalers (two separate scalers as in training)
HEART_RATE_MODEL = _safe_load(MODELS_DIR / "heart_rate_model.pkl")
SPEED_MODEL      = _safe_load(MODELS_DIR / "speed_model.pkl")
SCALER_HR        = _safe_load(MODELS_DIR / "scaler_hr.pkl")
SCALER_SPEED     = _safe_load(MODELS_DIR / "scaler_speed.pkl")

# Metadata (feature lists + config)
META_PATH = MODELS_DIR / "model_metadata.json"
if not META_PATH.exists():
    raise RuntimeError(f"Missing metadata file: {META_PATH}")
with open(META_PATH, "r", encoding="utf-8") as f:
    _meta = json.load(f)

HR_FEATURES:    List[str] = _meta["hr_feature_cols"]
SPEED_FEATURES: List[str] = _meta["speed_feature_cols"]
SPEED_LEAD_STEPS: int = _meta.get("SPEED_LEAD_STEPS", 0)


# ===================== Utility: align feature columns =====================
def align_features(df_row: pd.DataFrame, expected_cols: List[str]) -> pd.DataFrame:
    """
    Ensures df_row has exactly the expected columns in the expected order.
    - Adds missing columns with 0.0
    - Drops extra columns
    - Preserves the exact order required by the model
    """
    df_row = df_row.copy()
    for c in expected_cols:
        if c not in df_row.columns:
            df_row[c] = 0.0
    return df_row[expected_cols]


# ===================== API Schemas =====================
class Sample(BaseModel):
    timestamp: Optional[float] = Field(None, description="Unix seconds; server fills if missing")
    heart_rate: Optional[float] = None
    speed_kmh: Optional[float] = None
    speed_mps: Optional[float] = None
    enhanced_speed: Optional[float] = None
    cadence: Optional[float] = None
    power: Optional[float] = None
    distance_km: Optional[float] = None
    elevation_m: Optional[float] = None


class TickRequest(BaseModel):
    session_id: str
    runner_level: int = Field(2, ge=1, le=3, description="1=Beginner,2=Intermediate,3=Advanced")
    samples: List[Sample]


class Recommendation(BaseModel):
    pred_hr: Optional[float]
    pred_speed: Optional[float]
    recommendation: str


class TickResponse(BaseModel):
    session_id: str
    display_recommendation: bool
    result: Recommendation
    server_time: float


# ===================== Session State =====================
class SessionState:
    def __init__(self, session_id: str, runner_level: int = 2):
        self.session_id = session_id
        self.runner_level = runner_level
        self.history_df = pd.DataFrame(columns=[
            "timestamp", "heart_rate", "enhanced_speed", "cadence", "power",
            "distance_km", "elevation_m"
        ]).astype({
            "timestamp": "float64",
            "heart_rate": "float64",
            "enhanced_speed": "float64",
            "cadence": "float64",
            "power": "float64",
            "distance_km": "float64",
            "elevation_m": "float64",
        })
        self.last_ts = time.time()

    def add_samples(self, samples: List[Dict[str, Any]]):
        if not samples:
            return
        df_add = pd.DataFrame(samples)
        self.history_df = pd.concat([self.history_df, df_add], ignore_index=True)

        # Keep at most ~10 minutes (600s) of history
        t_now = samples[-1]["timestamp"]
        self.history_df = self.history_df[self.history_df["timestamp"] >= (t_now - 600)]
        self.history_df.reset_index(drop=True, inplace=True)
        self.last_ts = time.time()


SESSIONS: Dict[str, SessionState] = {}
SESSIONS_LOCK = threading.Lock()
SESSION_TTL = 60 * 30
CLEANUP_PERIOD = 60


# ===================== Recommendation Logic =====================
def recommend(current_data: Dict[str, Any], history_df: pd.DataFrame, runner_level: int = 2) -> Dict[str, Any]:
    """
    - Builds features with add_features (causal variants exist)
    - Aligns columns to training-time feature lists (HR_FEATURES / SPEED_FEATURES)
    - Uses the correct scaler per target, then predicts
    - Emits simple rule-based recommendation using pattern signals
    """
    # thresholds by level
    if runner_level == 1:      # Beginner
        hr_slow_threshold = 0.80
        fatigue_threshold = 1.15
        hr_diff_threshold = 8
        speed_diff_threshold = 0.25
    elif runner_level == 3:    # Advanced
        hr_slow_threshold = 0.90
        fatigue_threshold = 1.25
        hr_diff_threshold = 12
        speed_diff_threshold = 0.35
    else:                      # Intermediate
        hr_slow_threshold = 0.85
        fatigue_threshold = 1.20
        hr_diff_threshold = 10
        speed_diff_threshold = 0.30

    current_hr = float(current_data.get("heart_rate", 0.0) or 0.0)
    current_speed = float(current_data.get("enhanced_speed", 0.0) or 0.0)
    percent_hr_max = current_hr / 190.0  # personalize later

    # Stage 1 — early recommendations (warm-up logic)
    if len(history_df) < 30:
        if percent_hr_max > hr_slow_threshold:
            rec = "You're starting too fast – slow down to warm up properly"
        elif percent_hr_max < 0.5 and current_speed > 7:
            rec = "Consider slowing down a bit for a smoother warm-up"
        elif percent_hr_max < 0.4 and current_speed < 4:
            rec = "Try to gradually increase your pace"
        else:
            rec = "Warm-up phase – maintain steady pace"
        return {"pred_hr": None, "pred_speed": None, "recommendation": rec}

    # Stage 2 — features
    hist_feat = add_features(history_df.copy())
    latest = hist_feat.iloc[-1:].copy()

    # Align to training feature sets
    X_hr = align_features(latest, HR_FEATURES)
    X_spd = align_features(latest, SPEED_FEATURES)

    if any(x is None for x in [HEART_RATE_MODEL, SPEED_MODEL, SCALER_HR, SCALER_SPEED]):
        raise HTTPException(status_code=500, detail="Models or scalers not loaded. See /health.")

    # Scale & predict
    try:
        X_hr_scaled = SCALER_HR.transform(X_hr)
        X_spd_scaled = SCALER_SPEED.transform(X_spd)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Scaler transform failed: {e}")

    try:
        pred_hr = float(HEART_RATE_MODEL.predict(X_hr_scaled)[0])
        pred_speed = float(SPEED_MODEL.predict(X_spd_scaled)[0])  # @t+LEAD if trained with lead
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Model prediction failed: {e}")

    # Pattern signals (legacy, computed in add_features)
    hr_trend = float(latest.get("hr_trend_30", pd.Series([0.0])).iloc[0])
    fatigue_index = float(latest.get("fatigue_index", pd.Series([1.0])).iloc[0])
    speed_var = float(latest.get("speed_variation", pd.Series([0.0])).iloc[0])

    # Stage 3 — pattern-aware logic
    if fatigue_index > fatigue_threshold and percent_hr_max > (hr_slow_threshold - 0.05):
        rec = "Fatigue building up – slow down or shorten your stride"
    elif hr_trend > 10 and current_hr > pred_hr:
        rec = "Rising heart rate – consider reducing pace"
    elif speed_var > 0.5:
        rec = "Try to stabilize your speed for better endurance"
    else:
        # Stage 4 — instant rule-based fallback
        if percent_hr_max < 0.5 and current_speed < pred_speed:
            rec = "Increase pace slightly"
        elif percent_hr_max > hr_slow_threshold:
            rec = "Slow down to reduce heart rate"
        elif abs(current_hr - pred_hr) > hr_diff_threshold:
            rec = "Reduce intensity – heart rate is too high" if current_hr > pred_hr \
                  else "Increase intensity – heart rate is too low"
        elif abs(current_speed - pred_speed) > speed_diff_threshold:
            rec = "You're running too fast – slow down a bit" if current_speed > pred_speed \
                  else "Try to reach your target pace"
        else:
            rec = "Maintain current pace"

    return {
        "pred_hr": round(pred_hr, 1),
        "pred_speed": round(pred_speed, 2),
        "recommendation": rec
    }


# ===================== FastAPI App =====================
app = FastAPI(title="Run Together Real-Time Recommendations", version="1.2.0")


@app.get("/health")
def health():
    status_ok = all([
        HEART_RATE_MODEL is not None,
        SPEED_MODEL is not None,
        SCALER_HR is not None,
        SCALER_SPEED is not None,
        bool(HR_FEATURES),
        bool(SPEED_FEATURES),
    ])
    return {
        "status": "ok" if status_ok else "degraded",
        "root_dir": str(ROOT_DIR),
        "models_dir": str(MODELS_DIR),
        "hr_model": HEART_RATE_MODEL is not None,
        "speed_model": SPEED_MODEL is not None,
        "scaler_hr": SCALER_HR is not None,
        "scaler_speed": SCALER_SPEED is not None,
        "hr_features_n": len(HR_FEATURES),
        "speed_features_n": len(SPEED_FEATURES),
        "speed_lead_steps": SPEED_LEAD_STEPS,
        "server_time": time.time(),
    }


@app.post("/tick", response_model=TickResponse)
def tick(req: TickRequest):
    if not req.samples:
        raise HTTPException(status_code=400, detail="No samples provided")

    # Get/create session
    with SESSIONS_LOCK:
        state = SESSIONS.get(req.session_id)
        if state is None:
            state = SessionState(req.session_id, runner_level=req.runner_level)
            SESSIONS[req.session_id] = state
        else:
            state.runner_level = req.runner_level

    # Normalize and append samples
    norm_samples: List[Dict[str, Any]] = []
    for s in req.samples:
        ts = float(s.timestamp) if s.timestamp is not None else time.time()
        # preference: enhanced_speed > speed_kmh > (speed_mps*3.6)
        if s.enhanced_speed is not None:
            sp = float(s.enhanced_speed)
        elif s.speed_kmh is not None:
            sp = float(s.speed_kmh)
        elif s.speed_mps is not None:
            sp = float(s.speed_mps) * 3.6
        else:
            sp = np.nan

        norm_samples.append({
            "timestamp": ts,
            "heart_rate": float(s.heart_rate) if s.heart_rate is not None else np.nan,
            "enhanced_speed": sp,
            "cadence": float(s.cadence) if s.cadence is not None else np.nan,
            "power": float(s.power) if s.power is not None else np.nan,
            "distance_km": float(s.distance_km) if s.distance_km is not None else np.nan,
            "elevation_m": float(s.elevation_m) if s.elevation_m is not None else np.nan,
        })

    with SESSIONS_LOCK:
        state.add_samples(norm_samples)

    current_data = state.history_df.iloc[-1].to_dict()

    try:
        result = recommend(current_data=current_data, history_df=state.history_df, runner_level=state.runner_level)
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Engine error: {e}")

    # Display recommendation "boldly" every 5 seconds (optional)
    display = int(current_data["timestamp"]) % 5 == 0

    return TickResponse(
        session_id=req.session_id,
        display_recommendation=display,
        result=Recommendation(**result),
        server_time=time.time(),
    )


# ===================== Cleanup Thread =====================
def _cleanup_loop():
    while True:
        time.sleep(CLEANUP_PERIOD)
        now = time.time()
        with SESSIONS_LOCK:
            stale = [sid for sid, st in SESSIONS.items() if now - st.last_ts > SESSION_TTL]
            for sid in stale:
                SESSIONS.pop(sid, None)


t = threading.Thread(target=_cleanup_loop, daemon=True)
t.start()
