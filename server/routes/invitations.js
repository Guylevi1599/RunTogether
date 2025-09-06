// routes/invitations.js
const express = require('express');
const router = express.Router();
const User = require('../models/User');
const RunInvitation = require('../models/RunInvitation');

/**
 * Create invitation (or return existing pending one).
 */
router.post('/', async (req, res) => {
  console.log('Received POST body:', req.body);
  const { senderId, receiverId, timestamp } = req.body;

  // Basic validation (numeric and not the same user)
  const sId = Number(senderId);
  const rId = Number(receiverId);
  if (!Number.isFinite(sId) || !Number.isFinite(rId)) {
    return res.status(400).json({ error: 'Missing or invalid senderId/receiverId' });
  }
  if (sId === rId) {
    return res.status(400).json({ error: 'Cannot invite yourself' });
  }

  const inviteTimestamp = timestamp ? new Date(timestamp) : new Date();

  try {
    // 1) Return already-pending invitation if exists
    const existing = await RunInvitation.findOne({ senderId: sId, receiverId: rId, status: 'pending' }).lean();
    if (existing) {
      return res.json({ success: true, invitation: existing, existed: true });
    }

    // 2) Create new pending invitation
    const invitation = await RunInvitation.create({
      senderId: sId,
      receiverId: rId,
      timestamp: inviteTimestamp,
      status: 'pending',
    });

    console.log('Invitation saved:', invitation);
    return res.status(201).json({ success: true, invitation, existed: false });
  } catch (err) {
    // Handle unique-index race (duplicate key)
    if (err?.code === 11000) {
      const dupe = await RunInvitation.findOne({ senderId: sId, receiverId: rId, status: 'pending' }).lean();
      if (dupe) return res.json({ success: true, invitation: dupe, existed: true });
    }
    console.error('Error saving invitation:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

/** Get all pending invitations by sender (used to gray-out sent buttons on client). */
router.get('/pending', async (req, res) => {
  try {
    const senderId = Number(req.query.senderId);
    if (!Number.isFinite(senderId)) {
      return res.status(400).json({ error: 'senderId is required' });
    }

    const docs = await RunInvitation
      .find({ senderId, status: 'pending' }, { receiverId: 1, timestamp: 1 })
      .sort({ createdAt: -1 })
      .lean();

    const out = docs.map(d => ({ receiverId: d.receiverId, timestamp: d.timestamp }));
    return res.json(out);
  } catch (err) {
    console.error('Pending fetch error:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

/** Get invitations I received (pending only). */
router.get('/:receiverId', async (req, res) => {
  try {
    const receiverId = Number(req.params.receiverId);
    if (!Number.isFinite(receiverId)) {
      return res.status(400).json({ error: 'Invalid receiverId' });
    }

    const invitations = await RunInvitation
      .find({ receiverId, status: 'pending' })
      .sort({ createdAt: -1 })
      .lean();
      
    //Wrap in an object with the invitations key to keep a consistent format
    return res.json({ invitations });
  } catch (err) {
    console.error('Error fetching invitations:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

/**
 * Update invitation status.
 * Idempotent: if setting the same status it returns updated:false.
 * Atomic increment of partnersCount happens only on the first transition to 'accepted'.
 */
router.put('/status/:id', async (req, res) => {
  try {8
    const invitationId = req.params.id;
    const { status } = req.body;

    const ALLOWED = new Set(['pending', 'accepted', 'declined', 'cancelled']);
    if (!ALLOWED.has(status)) {
      return res.status(400).json({ error: 'Missing or invalid status' });
    }

    // Atomically update and get the previous document (pre-update)
    const prev = await RunInvitation.findOneAndUpdate(
      { _id: invitationId },
      { $set: { status } },
      { new: false } // return the document BEFORE the update
    );

    if (!prev) {
      return res.status(404).json({ error: 'Invitation not found' });
    }

    // If no change → idempotent return (we already set to same value; no-op for counters)
    if (prev.status === status) {
      return res.json({ success: true, invitation: await RunInvitation.findById(invitationId).lean(), updated: false });
    }

    // First transition to 'accepted' → bump partnersCount for both users
    if (status === 'accepted' && prev.status !== 'accepted') {
      await Promise.all([
        User.updateOne({ id: prev.senderId },   { $inc: { partnersCount: 1 } }),
        User.updateOne({ id: prev.receiverId }, { $inc: { partnersCount: 1 } }),
      ]);
    }

    const updatedDoc = await RunInvitation.findById(invitationId).lean();
    return res.json({ success: true, invitation: updatedDoc, updated: true });
  } catch (err) {
    console.error('Error updating invitation status:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

/**
 * GET /api/invitations/partners-count/:userId
 * Returns: { count: <number>, partnerIds: [<id>...] }
 * Unique partners are counted across both roles (sender/receiver) for accepted invitations.
 */
router.get('/partners-count/:userId', async (req, res) => {
  try {
    const userId = Number(req.params.userId);
    if (!Number.isFinite(userId)) {
      return res.status(400).json({ error: 'Invalid userId' });
    }

    const agg = await RunInvitation.aggregate([
      {
        $match: {
          status: 'accepted',
          $or: [{ senderId: userId }, { receiverId: userId }],
        },
      },
      {
        $project: {
          partnerId: { $cond: [{ $eq: ['$senderId', userId] }, '$receiverId', '$senderId'] },
        },
      },
      { $group: { _id: null, partners: { $addToSet: '$partnerId' } } },
      { $project: { _id: 0, count: { $size: '$partners' }, partnerIds: '$partners' } },
    ]);

    const result = agg[0] || { count: 0, partnerIds: [] };
    return res.json(result);
  } catch (err) {
    console.error('partners-count error:', err);
    return res.status(500).json({ error: 'Server error' });
  }
});

module.exports = router;
