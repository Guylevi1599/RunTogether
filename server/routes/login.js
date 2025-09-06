// routes/login.js
const express = require("express");
const bcrypt = require("bcrypt"); // Password hashing/verification
const router = express.Router();
const User = require("../models/User");

/**
 * POST /api/login
 *
 * Responsibilities:
 *  • Validate input (email & password must be present).
 *  • Look up the user by email.
 *  • Verify the provided password against the stored bcrypt hash.
 *  • Return a consistent JSON payload the client expects (Hebrew messages kept).
 */
router.post("/", async (req, res) => {
  // Basic input validation (keep messages in Hebrew)
  const { email, password } = req.body || {};
  if (typeof email !== "string" || typeof password !== "string" || !email || !password) {
    return res.status(400).json({ success: false, message: "נא להזין אימייל וסיסמה" });
  }

  try {
    // If your schema hides the password by default, ensure it's selectable (e.g., .select('+password'))
    const user = await User.findOne({ email });

    if (!user) {
      return res.status(404).json({ success: false, message: "משתמש לא קיים במערכת" });
    }

    // Compare plaintext password to stored bcrypt hash
    const isMatch = await bcrypt.compare(password, user.password);
    if (!isMatch) {
      return res.status(401).json({ success: false, message: "סיסמה שגויה" });
    }

    // Success — return minimal user identifier (client expects user.id, not _id)
    return res.status(200).json({
      success: true,
      message: "התחברת בהצלחה",
      userId: user.id,
    });
  } catch (err) {
    console.error("שגיאה בכניסה:", err);
    return res.status(500).json({ success: false, message: "שגיאה בשרת" });
  }
});

module.exports = router;
