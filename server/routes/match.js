// routes/match.js
const express = require('express');
const router = express.Router();
const User = require('../models/User');
const { findBestMatches } = require('../utils/matchUtils');

/**
 * POST /api/match
 * Body:  { userId: Number }
 * Query: ?minScore=60  (optional, default 60; valid range 0..100)
 */
router.post('/', async (req, res) => {
  try {
    // ---- Input validation ----
    const userId = Number(req.body?.userId);
    if (!Number.isFinite(userId) || userId <= 0) {
      return res.status(400).json({ error: 'userId is required' });
    }

    // Parse minScore (optional); default to 60 and clamp to [0, 100]
    const qMin = Number(req.query?.minScore);
    let minScore = Number.isFinite(qMin) ? qMin : 60;
    if (minScore < 0 || minScore > 100) {
      return res.status(400).json({ error: 'minScore must be between 0 and 100' });
    }

    // ---- Load current user ----
    // TIP: If matchUtils needs specific fields only, project them explicitly to reduce I/O.
    const currentUser = await User.findOne({ id: userId }).lean();
    if (!currentUser) {
      return res.status(404).json({ error: 'User not found' });
    }

    //  Load candidate pool (all except current user)
    const allUsers = await User.find({ id: { $ne: userId } }).lean();

    if (!Array.isArray(allUsers) || allUsers.length === 0) {
      return res.json({ matches: [] });
    }

    //Compute matches via your domain logic
    const matches = findBestMatches(currentUser, allUsers, minScore) || [];

    return res.json({ matches });
  } catch (err) {
    console.error('Matching Error:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

module.exports = router;
