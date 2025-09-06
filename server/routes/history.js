// routes/history.js
const express = require("express");
const router = express.Router();
const RunningHistory = require("../models/RunningHistory");

/**
 * GET /api/history/:userId
 * Fetch all runs for a specific user.
 * - userId is stored in the "id" field (not Mongo _id).
 * - Results are sorted by creation time (newest first).
 */
router.get("/:userId", async (req, res) => {
  const userId = Number(req.params.userId); // coerce path param to number

  if (!Number.isFinite(userId)) {
    return res.status(400).json({ message: "ID אינו תקין" });
  }

  try {
    const runs = await RunningHistory
      .find({ id: userId })
      .sort({ createdAt: -1 })
      .lean();

    return res.json(runs);
  } catch (error) {
    console.error("שגיאה בשליפת ריצות:", error);
    return res.status(500).json({ message: "שגיאה בשרת" });
  }
});

/**
 * POST /api/history
 * Create a new run history record.
 * Required: id (numeric) and run_date (string "yyyy-MM-dd" as sent from client).
 * Optional numeric fields are coerced when provided.
 */
router.post("/", async (req, res) => {
  try {
    console.log("history POST body:", req.body);

    const {
      id,
      average_heart_rate,
      total_time_minutes,
      average_speed_kmh,
      total_distance_km,
      run_date, // keep exact field name as client expects
    } = req.body;

    // Minimal validation
    if (!Number.isFinite(Number(id)) || !run_date) {
      return res.status(400).json({ error: "id and run_date are required" });
    }

    // Coerce numeric fields if provided; leave undefined if missing
    const doc = await RunningHistory.create({
      id: Number(id),
      average_heart_rate: average_heart_rate != null ? Number(average_heart_rate) : undefined,
      total_time_minutes: total_time_minutes != null ? Number(total_time_minutes) : undefined,
      average_speed_kmh:  average_speed_kmh  != null ? Number(average_speed_kmh)  : undefined,
      total_distance_km:  total_distance_km  != null ? Number(total_distance_km)  : undefined,
      run_date,
    });

    return res.status(201).json(doc);
  } catch (err) {
    console.error("history POST error:", err);
    return res.status(500).json({ error: "Server error" });
  }
});

module.exports = router;
