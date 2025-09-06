// routes/register.js
const express = require("express");
const bcrypt = require("bcrypt"); // password hashing
const router = express.Router();
const User = require("../models/User");

/**
 * POST /api/register
 *
 * Responsibilities:
 *  • Validate input and ensure the email is not already registered.
 *  • Assign a numeric incremental id (NOTE: see race-condition note below).
 *  • Hash the password with bcrypt and persist the user.
 */
router.post("/", async (req, res) => {
  //Basic normalization & validation
  const {
    fullName,
    age,
    phone,
    city,
    street,
    gender,
    level,
    email,
    password,
    availability,
  } = req.body || {};

  // Validate required fields (you can relax/adjust as your UI guarantees them)
  if (!fullName || !email || !password) {
    return res
      .status(400)
      .json({ success: false, message: "נא למלא את כל השדות הנדרשים" });
  }

  const emailNorm = String(email).toLowerCase().trim();
  const ageNum = Number(age);
  const levelNum = Number(level);

  // Defensive: ensure availability is an array of strings
  const availabilityArr = Array.isArray(availability)
    ? availability.map(String)
    : [];

  console.log("📥 נתונים שהתקבלו בהרשמה:", req.body);

  try {
    // 1) Check if user already exists (by email)
    const existingUser = await User.findOne({ email: emailNorm });
    if (existingUser) {
      console.log("משתמש כבר קיים:", emailNorm);
      return res
        .status(400)
        .json({ success: false, message: "משתמש כבר קיים" });
    }

    // 2) Generate next numeric id
    // NOTE: This approach can race under concurrent signups.
    // Prefer an atomic counter or enforce a UNIQUE index on { id } and retry on E11000.
    const lastUser = await User.findOne().sort({ id: -1 }).lean();
    const nextId = lastUser ? lastUser.id + 1 : 0;

    // 3) Hash password
    const hashedPassword = await bcrypt.hash(String(password), 10); // 10 salt rounds

    // 4) Create and persist the user
    const newUser = new User({
      id: nextId,
      fullName: String(fullName).trim(),
      age: Number.isFinite(ageNum) ? ageNum : undefined,
      phone: phone ? String(phone).trim() : undefined,
      city: city ? String(city).trim() : undefined,
      street: street ? String(street).trim() : undefined,
      gender: gender ? String(gender).trim() : undefined,
      level: Number.isFinite(levelNum) ? levelNum : undefined,
      email: emailNorm,
      password: hashedPassword, // store hash, never plaintext
      availability: availabilityArr,
    });

    await newUser.save();

    console.log("נרשם בהצלחה:", emailNorm);
    return res
      .status(201)
      .json({ success: true, message: "נרשמת בהצלחה", id: nextId });
  } catch (err) {
    // Handle duplicate-key (in case unique index is present and race occurs)
    if (err && err.code === 11000) {
      // Figure out which unique key violated:
      if (err.keyPattern?.email) {
        return res
          .status(400)
          .json({ success: false, message: "משתמש כבר קיים" });
      }
      if (err.keyPattern?.id) {
        // Rare: id collision due to race. You can retry the whole block or return an error.
        return res
          .status(409)
          .json({ success: false, message: "שגיאה בהרשמה, נסה שוב" });
      }
    }

    console.error("שגיאה בהרשמה:", err);
    return res
      .status(500)
      .json({ success: false, message: "שגיאה בשרת", error: err.message });
  }
});

module.exports = router;
