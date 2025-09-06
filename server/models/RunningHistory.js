const mongoose = require('mongoose');

const runningHistorySchema = new mongoose.Schema({
  id: { type: Number, required: true, index: true }, 
  average_heart_rate: { type: Number, default: 0 },
  total_time_minutes: { type: Number, default: 0 },
  average_speed_kmh: { type: Number, default: 0 },
  total_distance_km: { type: Number, default: 0 },
  run_date: { type: String, required: true }, 
}, { collection: 'running_history', timestamps: true });

module.exports = mongoose.models.RunningHistory
  || mongoose.model('RunningHistory', runningHistorySchema);
