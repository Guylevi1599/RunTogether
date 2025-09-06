// routes/users.js
const express = require('express');
const bcrypt = require('bcrypt');
const router = express.Router();
const User = require('../models/User');

/**
 * GET /api/users/:id
 */
router.get('/:id', async (req, res) => {
  try {
    const id = Number(req.params.id);
    console.log('GET /api/users/:id => looking for:', id);

    if (!Number.isFinite(id)) {
      return res.status(400).json({ success: false, message: 'Invalid id' });
    }

    const user = await User.findOne({ id })
      .select('id fullName age phone city street gender level availability email runsCount partnersCount');

    if (!user) {
      return res.status(404).json({ success: false, message: 'User not found' });
    }

    return res.json(user);
  } catch (e) {
    console.error('GET /users/:id error', e);
    return res.status(500).json({ success: false, message: 'Server error' });
  }
});

/**
 * PUT /api/users/:id
 *
 * Responsibilities:
 *  • Update editable profile fields for a given user id.
 *  • Hash the password if a new password is provided.
 *
 * Validation:
 *  • age must be 1..120
 *  • level must be in {0,1,2}
 *  • availability must be an array (we coerce to strings)
 *  • password, if present, must be ≥ 6 chars; we bcrypt-hash it before saving
 */
router.put('/:id', async (req, res) => {
  try {
    const id = Number(req.params.id);
    console.log('PUT /api/users/:id => updating:', id, 'with data:', req.body);

    if (!Number.isFinite(id)) {
      return res.status(400).json({ success: false, message: 'Invalid id' });
    }

    // Whitelist fields you permit to be updated
    const allowed = [
      'fullName', 'age', 'phone', 'city', 'street',
      'gender', 'level', 'availability', 'password',
    ];

    const update = {};
    for (const k of allowed) {
      if (Object.prototype.hasOwnProperty.call(req.body, k)) {
        update[k] = req.body[k];
      }
    }

    // Field-level validation / normalization
    if (update.age != null) {
      const a = Number(update.age);
      if (!Number.isFinite(a) || a <= 0 || a > 120) {
        return res.status(400).json({ success: false, message: 'Invalid age' });
      }
      update.age = a;
    }

    if (update.level != null) {
      const lvl = Number(update.level);
      if (![0, 1, 2].includes(lvl)) {
        return res.status(400).json({ success: false, message: 'Invalid level' });
      }
      update.level = lvl;
    }

    if (update.availability != null) {
      if (!Array.isArray(update.availability)) {
        return res.status(400).json({ success: false, message: 'availability must be an array of strings' });
      }
      // Normalize to array of strings (Hebrew values are fine)
      update.availability = update.availability.map(String);
    }

    // Password handling (hash if provided)
    if (update.password) {
      if (String(update.password).length < 6) {
        return res.status(400).json({ success: false, message: 'Password must be at least 6 characters' });
      }
      update.password = await bcrypt.hash(String(update.password), 10);
    }

    const user = await User.findOneAndUpdate(
      { id },
      { $set: update },
      { new: true, runValidators: true }
    ).select('id fullName age phone city street gender level availability email runsCount partnersCount');

    if (!user) {
      return res.status(404).json({ success: false, message: 'User not found' });
    }

    return res.json(user);
  } catch (e) {
    console.error('PUT /users/:id error', e);
    return res.status(500).json({ success: false, message: 'Server error' });
  }
});

/**
 * PUT /api/users/:id/runsCount
 * Body: { delta: Number }  // optional, defaults to 1
 */
router.put('/:id/runsCount', async (req, res) => {
  try {
    const id = Number(req.params.id);
    // Allow floats but store as integer increments (optional: Math.trunc/round)
    const rawDelta = req.body?.delta;
    const delta = Number.isFinite(Number(rawDelta)) ? Number(rawDelta) : 1;

    if (!Number.isFinite(id)) {
      return res.status(400).json({ error: 'Invalid user id' });
    }

    const updated = await User.findOneAndUpdate(
      { id },
      { $inc: { runsCount: delta } },
      { new: true }
    ).lean();

    if (!updated) {
      return res.status(404).json({ error: 'User not found' });
    }

    // Return minimal payload needed by the client
    return res.json({ id: updated.id, runsCount: updated.runsCount });
  } catch (err) {
    console.error('increment runsCount error:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

module.exports = router;
