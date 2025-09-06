package com.example.runtogether;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Observer;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.android.volley.Request;
import com.android.volley.toolbox.JsonObjectRequest; // Using app-wide VolleySingleton

import com.example.runtogether.RecommendationsService;
import com.example.runtogether.RecommendationsService.TickResponseDto;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;

import retrofit2.Retrofit;
import retrofit2.http.Body;
import retrofit2.http.POST;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * RunActivity
 *
 * Purpose:
 *  • Start/stop a running session from a real Wear device or via simulation mode.
 *  • Show live metrics (HR / Speed / Distance) and observe server recommendations/predictions.
 *  • On finish: present a summary, persist history to the Node server, and increment runsCount.
 *
 * Policy in this version:
 *  • If simulateMode == true → user can start a simulated run (no watch required).
 *  • If simulateMode == false → user MUST be connected to a watch to start a run.
 */
public class RunActivity extends AppCompatActivity {

    // ----- Simulation toggle default (used if no pref is saved) -----
    private static final boolean SIMULATION_DEFAULT = false;

    // ----- Node API (history + runs counter) -----
    private static final String NODE_BASE_URL = "http://10.0.2.2:3000/"; // Emulator → host machine

    // ----- UI -----
    private Button btnConnectOrStart, btnEndRun;
    private LinearLayout llPhysiologicalData;
    private TextView tvSpeed, tvHeartRate, tvDistance, tvFeedback, tvTimeStart, tvSummaryHeader;

    // ----- Connection / run state -----
    private String connectedNodeId = null;
    private boolean isConnectedToWatch = false;
    private boolean isRunActive = false;
    private boolean simulateMode = SIMULATION_DEFAULT;

    // ----- Live & aggregate data -----
    private float  lastHr = 0f;
    private double lastSpeedKph = 0.0;
    private double lastDistanceKm = 0.0;

    private double sumHr = 0.0;     private int cntHr = 0;
    private double sumSpeed = 0.0;  private int cntSpeed = 0;

    private long runStartWallTimeMs = 0L;

    // ----- Recommendation-aware simulation control (NEW) -----
    // Targets provided by the recommendation service (can be null during warm-up).
    private Float targetPredSpeed = null;  // km/h
    private Float targetPredHr    = null;  // bpm
    private String lastRecText    = "";    // raw recommendation text

    // Rate limits to keep the simulation natural.
    private static final double MAX_ACCEL_KPH_PER_SEC = 0.30; // max speed change per second
    private static final double MAX_HR_CHANGE_PER_SEC = 2.0;  // max HR change per second

    // Handlers (UI + simulation loop)
    private final Handler uiHandler  = new Handler(Looper.getMainLooper());
    private final Handler simHandler = new Handler(Looper.getMainLooper());

    /** Elapsed time ticker (updates the "run time" label every second while active). */
    private final Runnable elapsedTicker = new Runnable() {
        @Override public void run() {
            if (isRunActive) {
                long elapsedMs = System.currentTimeMillis() - runStartWallTimeMs;
                tvTimeStart.setText("זמן ריצה:\n" + formatElapsed(elapsedMs));
                uiHandler.postDelayed(this, 1000);
            }
        }
    };

    /** Simulation tick: follows recommendations (setpoints + text nudges) and accumulates distance. */
    private final Runnable simTick = new Runnable() {
        private double hr = 135;     // starting HR (bpm)
        private double sp = 8.8;     // starting speed (km/h)
        private double distKm = 0.0; // cumulative distance

        @Override public void run() {
            if (!isRunActive || !simulateMode) return;

            // --- 1) Small baseline variability (bounded random walk) ---
            // Keep some natural fluctuation even when following setpoints.
            sp += ThreadLocalRandom.current().nextDouble(-0.06, 0.08);
            sp = clamp(sp, 4.0, 16.0);

            // --- 2) Translate free-text recommendation into a tiny speed nudge (once per tick) ---
            Double textNudge = nudgeFromText(lastRecText, sp);
            if (textNudge != null) {
                sp = approach(sp, sp + textNudge, MAX_ACCEL_KPH_PER_SEC);
            } else if (targetPredSpeed != null) {
                // --- 3) No explicit text nudge → converge smoothly to model-predicted speed ---
                sp = approach(sp, targetPredSpeed, MAX_ACCEL_KPH_PER_SEC);
            }

            // --- 4) Compute HR setpoint from speed and blend with predicted HR (if available) ---
            // Non-linear mapping speed→HR (rough heuristic for realism).
            double targetHrFromSpeed = 60 + (200 - 60) * Math.pow(sp / 16.0, 0.85);
            double hrSetpoint = targetHrFromSpeed;

            if (targetPredHr != null) {
                // Blend: 60% speed-based + 40% model-predicted HR
                hrSetpoint = 0.6 * targetHrFromSpeed + 0.4 * targetPredHr;
            }

            // Move HR toward the setpoint with physiological lag (+ small noise).
            hr = approach(hr, hrSetpoint, MAX_HR_CHANGE_PER_SEC);
            hr += ThreadLocalRandom.current().nextDouble(-0.6, 0.6);
            hr = clamp(hr, 85, 198);

            // --- 5) Accumulate distance (km/h → km per second) ---
            distKm += sp / 3600.0;

            // --- 6) Publish to UI and SensorsRepository ---
            lastHr = (float) hr;
            lastSpeedKph = sp;
            lastDistanceKm = distKm;

            tvHeartRate.setText("דופק:\n" + Math.round(lastHr));
            tvSpeed.setText("מהירות:\n" + String.format(Locale.getDefault(), "%.2f", lastSpeedKph) + " קמ\"ש");
            tvDistance.setText("מרחק:\n" + String.format(Locale.getDefault(), "%.2f", lastDistanceKm) + " ק\"מ");

            RecommendationsService.SensorsRepository.setHeartRate(lastHr);
            RecommendationsService.SensorsRepository.setSpeedKmh((float) lastSpeedKph);
            RecommendationsService.SensorsRepository.setDistanceKm((float) lastDistanceKm);

            // Aggregates for summary
            sumHr += lastHr;     cntHr++;
            sumSpeed += lastSpeedKph; cntSpeed++;

            simHandler.postDelayed(this, 1000);
        }
    };

    /** Map app runner level 0..2 → API contract 1..3. */
    private int apiRunnerLevel = 2;

    // ----- Retrofit for Node history persistence -----
    interface HistoryApi { @POST("api/history") Call<Object> saveRun(@Body RunSummaryDto body); }
    private HistoryApi historyApi;

    // ----- Observe recommendations from the foreground service -----
    private final Observer<TickResponseDto> recObserver = dto -> {
        if (!isRunActive || dto == null || dto.result == null) return;

        // Save latest recommendation text & setpoint predictions for the simulator.
        lastRecText = dto.result.recommendation != null ? dto.result.recommendation : "";
        targetPredHr = dto.result.predHr;         // may be null in warm-up stage
        targetPredSpeed = dto.result.predSpeed;   // may be null in warm-up stage

        // Compose feedback panel (as before).
        StringBuilder sb = new StringBuilder("המלצה: ").append(lastRecText);
        if (targetPredHr != null)  sb.append("\n🔍 חזוי דופק: ").append(String.format(Locale.getDefault(), "%.1f", targetPredHr));
        if (targetPredSpeed != null) sb.append("\n🔍 מהירות מומלצת: ").append(String.format(Locale.getDefault(), "%.2f", targetPredSpeed));

        tvFeedback.setVisibility(View.VISIBLE);
        tvFeedback.setText(sb.toString());
    };

    // ----- Data layer broadcasts from the watch (if connected) -----
    private final BroadcastReceiver wearDataReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null || !isRunActive) return;

            if ("WEAR_HR".equals(action)) {
                lastHr = intent.getFloatExtra("bpm", 0f);
                tvHeartRate.setText("דופק:\n" + Math.round(lastHr));
                RecommendationsService.SensorsRepository.setHeartRate(lastHr);
                if (lastHr > 0) { sumHr += lastHr; cntHr++; }

            } else if ("WEAR_SPEED".equals(action)) {
                double speedMs = intent.getDoubleExtra("speed_ms", 0.0);
                lastSpeedKph = speedMs * 3.6;
                tvSpeed.setText("מהירות:\n" + String.format(Locale.getDefault(), "%.2f", lastSpeedKph) + " קמ\"ש");
                RecommendationsService.SensorsRepository.setSpeedKmh((float) lastSpeedKph);
                if (lastSpeedKph >= 0) { sumSpeed += lastSpeedKph; cntSpeed++; }

            } else if ("WEAR_DISTANCE".equals(action)) {
                double distMeters = intent.getDoubleExtra("distance_m", 0.0);
                lastDistanceKm = distMeters / 1000.0;
                tvDistance.setText("מרחק:\n" + String.format(Locale.getDefault(), "%.2f", lastDistanceKm) + " ק\"מ");
                RecommendationsService.SensorsRepository.setDistanceKm((float) lastDistanceKm);
            }
        }
    };

    // ===== Lifecycle =====
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_run);

        // View binding
        btnConnectOrStart   = findViewById(R.id.btnConnectWatch);
        btnEndRun           = findViewById(R.id.btnEndRun);
        llPhysiologicalData = findViewById(R.id.llPhysiologicalData);
        tvSpeed     = findViewById(R.id.tvSpeed);
        tvHeartRate = findViewById(R.id.tvHeartRate);
        tvDistance  = findViewById(R.id.tvDistance);
        tvFeedback  = findViewById(R.id.tvFeedback);
        tvSummaryHeader  = findViewById(R.id.tvSummaryHeader);
        tvTimeStart = findViewById(R.id.tvTimeStart);

        // Initial UI state
        llPhysiologicalData.setVisibility(View.GONE);
        tvFeedback.setVisibility(View.GONE);
        tvTimeStart.setText("זמן ריצה:\n00:00");
        btnEndRun.setVisibility(View.GONE);

        // Build Retrofit API for Node history
        historyApi = buildNodeHistoryApi(NODE_BASE_URL);

        // Read runner level (app → API mapping)
        apiRunnerLevel = mapRunnerLevelFromPrefs();

        // Read simulateMode from prefs (fallback to SIMULATION_DEFAULT)
        simulateMode = readSimulateFromPrefs();

        // Configure primary CTA according to mode
        updatePrimaryCtaByMode();

        // End run button
        btnEndRun.setOnClickListener(v -> finishRun());

        // Register watch data broadcasts
        IntentFilter f = new IntentFilter();
        f.addAction("WEAR_HR");
        f.addAction("WEAR_SPEED");
        f.addAction("WEAR_DISTANCE");
        LocalBroadcastManager.getInstance(this).registerReceiver(wearDataReceiver, f);
    }

    @Override protected void onStart() {
        super.onStart();
        RecommendationsService.recommendationLiveData.observe(this, recObserver);
    }

    @Override protected void onStop() {
        super.onStop();
        RecommendationsService.recommendationLiveData.removeObserver(recObserver);
    }

    @Override protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(wearDataReceiver);
        super.onDestroy();
    }

    // ===== Connect / Start =====

    /**
     * Configure the primary CTA according to simulateMode:
     *  • simulateMode == true  → "Start Simulation" (starts run immediately)
     *  • simulateMode == false → "Connect Watch" (tries to connect; only then can start)
     */
    private void updatePrimaryCtaByMode() {
        if (simulateMode) {
            btnConnectOrStart.setText("התחל סימולציה");
            btnConnectOrStart.setOnClickListener(v -> startRun());
        } else {
            btnConnectOrStart.setText("התחבר לשעון");
            btnConnectOrStart.setOnClickListener(v -> tryConnectToWatch());
        }
    }

    /**
     * Attempts to connect to a Wear device.
     * If not connected and simulateMode == false → cannot start run.
     * If not connected but simulateMode == true  → allowed to start a simulated run.
     */
    private void tryConnectToWatch() {
        ConnectWatchHelper.connectToWatch(this, new ConnectWatchHelper.OnWatchConnected() {
            @Override public void onConnected(String nodeId) {
                isConnectedToWatch = true;
                connectedNodeId = nodeId;
                saveNodeId(nodeId);
                Toast.makeText(RunActivity.this, "מחובר לשעון", Toast.LENGTH_SHORT).show();
                promoteButtonToStart(); // Now allowed to start run
            }
            @Override public void onNotConnected() {
                isConnectedToWatch = false;
                connectedNodeId = null;

                if (!simulateMode) {
                    // No simulation → cannot start without a watch
                    Toast.makeText(RunActivity.this, "לא נמצא שעון – לא ניתן להתחיל ריצה", Toast.LENGTH_SHORT).show();
                    // Keep CTA as "connect"
                    updatePrimaryCtaByMode();
                } else {
                    // Simulation mode is on → allow starting anyway
                    Toast.makeText(RunActivity.this, "לא נמצא שעון — מצב סימולציה פעיל", Toast.LENGTH_SHORT).show();
                    promoteButtonToStart();
                }
            }
        });
    }

    /** Switch primary CTA to "Start Run". */
    private void promoteButtonToStart() {
        btnConnectOrStart.setText("התחל ריצה");
        btnConnectOrStart.setOnClickListener(v -> startRun());
    }

    /**
     * Starts the running session:
     *  • Validates requirements (simulation or watch connection).
     *  • Resets state, starts foreground service, and optionally starts simulation loop.
     */
    private void startRun() {
        // Guard: if simulation is OFF, a watch must be connected
        if (!simulateMode && !(isConnectedToWatch && connectedNodeId != null)) {
            Toast.makeText(this, "לא ניתן להתחיל – התחבר/י לשעון או הפעיל/י מצב סימולציה", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isRunActive) return;
        isRunActive = true;
        runStartWallTimeMs = System.currentTimeMillis();
        resetAggregates();

        llPhysiologicalData.setVisibility(View.VISIBLE);
        btnConnectOrStart.setVisibility(View.GONE);
        btnEndRun.setVisibility(View.VISIBLE);
        tvFeedback.setText("");
        tvFeedback.setVisibility(View.VISIBLE);
        tvTimeStart.setText("זמן ריצה:\n00:00");
        uiHandler.post(elapsedTicker);

        tvHeartRate.setText("דופק:\n—");
        tvSpeed.setText("מהירות:\n—");
        tvDistance.setText("מרחק:\n0.00 ק\"מ");

        // Notify watch (if connected)
        if (isConnectedToWatch && connectedNodeId != null) {
            WearMessaging.sendMessage(this, connectedNodeId, "/start_run", null, null, null);
        }

        String sessionId = "user" + getUserId() + "-" + System.currentTimeMillis();

        if (simulateMode) {
            // Seed initial values to avoid nulls on first service tick
            RecommendationsService.SensorsRepository.setHeartRate(135f);
            RecommendationsService.SensorsRepository.setSpeedKmh(8.8f);
            RecommendationsService.SensorsRepository.setDistanceKm(0f);

            // Start simulation loop
            simHandler.post(simTick);

            // Start foreground service after a short delay
            uiHandler.postDelayed(() -> {
                Intent svc = new Intent(this, RecommendationsService.class);
                svc.putExtra(RecommendationsService.EXTRA_SESSION_ID, sessionId);
                svc.putExtra(RecommendationsService.EXTRA_RUNNER_LEVEL, apiRunnerLevel);
                ContextCompat.startForegroundService(this, svc);
            }, 400);
        } else {
            // Non-simulation: start service normally (watch is guaranteed by guard above)
            Intent svc = new Intent(this, RecommendationsService.class);
            svc.putExtra(RecommendationsService.EXTRA_SESSION_ID, sessionId);
            svc.putExtra(RecommendationsService.EXTRA_RUNNER_LEVEL, apiRunnerLevel);
            ContextCompat.startForegroundService(this, svc);
        }
    }

    /**
     * Finishes the run:
     *  • Stops loops/services, computes summary, persists history, and bumps runs counter.
     */
    private void finishRun() {
        if (!isRunActive) return;
        isRunActive = false;

        uiHandler.removeCallbacks(elapsedTicker);
        simHandler.removeCallbacks(simTick);

        // Notify watch (optional)
        if (connectedNodeId != null) {
            WearMessaging.sendMessage(this, connectedNodeId, "/stop_run", null, null, null);
        }

        // Stop the recommendation service
        stopService(new Intent(this, RecommendationsService.class));

        // Compute summary
        long elapsedMs = System.currentTimeMillis() - runStartWallTimeMs;
        double totalTimeMinutes = elapsedMs / 60000.0;
        double avgHr    = (cntHr > 0) ? (sumHr / cntHr) : 0.0;
        double avgSpeed = (cntSpeed > 0) ? (sumSpeed / cntSpeed) : 0.0;
        double totalKm  = Math.max(0.0, lastDistanceKm);

        // Present summary
        tvSummaryHeader.setVisibility(View.VISIBLE);
        tvHeartRate.setText("דופק ממוצע:\n" + String.format(Locale.getDefault(), "%.0f", avgHr));
        tvSpeed.setText("מהירות ממוצעת:\n" + String.format(Locale.getDefault(), "%.2f", avgSpeed) + " קמ\"ש");
        tvDistance.setText("מרחק:\n" + String.format(Locale.getDefault(), "%.2f", totalKm) + " ק\"מ");
        tvTimeStart.setText("זמן:\n" + formatElapsed(elapsedMs));

        tvFeedback.setVisibility(View.GONE);
        btnEndRun.setVisibility(View.GONE);
        btnConnectOrStart.setVisibility(View.GONE);
        llPhysiologicalData.setVisibility(View.VISIBLE);

        Toast.makeText(this, "הריצה הושלמה בהצלחה", Toast.LENGTH_SHORT).show();

        // Post history to Node (optional) and then try to increment runsCount regardless of result
        RunSummaryDto dto = new RunSummaryDto();
        dto.id = getUserId();
        dto.average_heart_rate = round2(avgHr);
        dto.total_time_minutes = round2(totalTimeMinutes);
        dto.average_speed_kmh  = round2(avgSpeed);
        dto.total_distance_km  = round2(totalKm);
        dto.run_date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        historyApi.saveRun(dto).enqueue(new Callback<Object>() {
            @Override public void onResponse(Call<Object> call, Response<Object> response) {
                incrementRunsCountWithVolley(getUserId());
            }
            @Override public void onFailure(Call<Object> call, Throwable t) {
                incrementRunsCountWithVolley(getUserId());
            }
        });
    }

    // ===== Helpers =====

    /** Move 'current' toward 'target' but cap the step by 'maxDelta' per call (rate limiting). */
    private double approach(double current, double target, double maxDelta) {
        if (Double.isNaN(target)) return current;
        double diff = target - current;
        if (Math.abs(diff) <= maxDelta) return target;
        return current + Math.signum(diff) * maxDelta;
    }

    /** Translate free-text recommendation into a small speed nudge (km/h). */
    private Double nudgeFromText(String rec, double currentSpeed) {
        if (rec == null || rec.isEmpty()) return null;
        String r = rec.toLowerCase(Locale.ROOT);

        // Slow-down cues
        if (r.contains("slow down") || r.contains("too fast") || r.contains("reduce pace")) {
            return -0.4;
        }
        // Speed-up cues
        if (r.contains("increase pace") || r.contains("target pace") || r.contains("too low")) {
            return +0.4;
        }
        // Stabilize speed → small push toward mid band
        if (r.contains("stabilize")) {
            return (currentSpeed > 9.0) ? -0.25 : (currentSpeed < 7.5 ? +0.25 : 0.0);
        }
        // Warm-up → keep moderate
        if (r.contains("warm-up") || r.contains("warm up")) {
            return (currentSpeed > 8.0) ? -0.3 : (currentSpeed < 6.0 ? +0.3 : 0.0);
        }
        // Fatigue → ease off a bit more
        if (r.contains("fatigue")) {
            return -0.6;
        }
        return null;
    }

    /** Reset aggregates before starting a new run. */
    private void resetAggregates() {
        sumHr = 0.0; cntHr = 0;
        sumSpeed = 0.0; cntSpeed = 0;
        lastDistanceKm = 0.0;
    }

    /** mm:ss formatting helper. */
    private String formatElapsed(long ms) {
        long totalSec = TimeUnit.MILLISECONDS.toSeconds(ms);
        long mm = totalSec / 60;
        long ss = totalSec % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", mm, ss);
    }

    /** App-level runner level (0..2) mapped to API contract (1..3). */
    private int mapRunnerLevelFromPrefs() {
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        int appLevel = prefs.getInt("level", 0);
        return Math.max(1, Math.min(3, appLevel + 1));
    }

    /** Read userId from prefs. */
    private int getUserId() {
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        return prefs.getInt("userId", 0);
    }

    /** Read simulateMode from prefs; fallback to SIMULATION_DEFAULT if missing. */
    private boolean readSimulateFromPrefs() {
        SharedPreferences prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE);
        return prefs.getBoolean("simulate_mode", SIMULATION_DEFAULT);
    }

    /** Build Retrofit client for Node server (basic logging). */
    private HistoryApi buildNodeHistoryApi(String base) {
        HttpLoggingInterceptor log = new HttpLoggingInterceptor();
        log.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(log).build();
        Retrofit r = new Retrofit.Builder()
                .baseUrl(base)
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        return r.create(HistoryApi.class);
    }

    /** Round to 2 decimals. */
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    /** Clamp helper. */
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Persist last connected node id (optional). */
    private void saveNodeId(String nodeId) {
        SharedPreferences sp = getSharedPreferences("wear_prefs", MODE_PRIVATE);
        sp.edit().putString("node_id", nodeId).apply();
    }

    // DTO for Node history API
    public static class RunSummaryDto {
        public int id;
        public double average_heart_rate;
        public double total_time_minutes;
        public double average_speed_kmh;
        public double total_distance_km;
        public String run_date; // "yyyy-MM-dd"
    }

    /**
     * PUT /api/users/{userId}/runsCount with { "delta": 1 } using the shared Volley queue.
     * Saves returned runsCount to local SharedPreferences if the server echoes the new value.
     */
    private void incrementRunsCountWithVolley(int userId) {
        try {
            String url = NODE_BASE_URL + "api/users/" + userId + "/runsCount";
            JSONObject body = new JSONObject();
            body.put("delta", 1); // increment by 1

            JsonObjectRequest req = new JsonObjectRequest(
                    Request.Method.PUT,
                    url,
                    body,
                    res -> {
                        int newCount = res.optInt("runsCount", -1);
                        if (newCount >= 0) {
                            getSharedPreferences("UserPrefs", MODE_PRIVATE)
                                    .edit()
                                    .putInt("runsCount", newCount)
                                    .apply();
                        }
                    },
                    err -> {
                        // Non-critical; ignore or add logging if needed
                    }
            );

            // Use the app-wide RequestQueue (no newRequestQueue per call)
            VolleySingleton.getInstance(this).addToRequestQueue(req);

        } catch (Exception ignored) {
            // Swallow non-critical exceptions
        }
    }
}
