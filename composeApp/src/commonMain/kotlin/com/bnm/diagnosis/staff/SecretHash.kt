package com.bnm.diagnosis.staff

/**
 * The staff credential formats, in one place so [StaffRepository] (which writes
 * them) and [Staff] (which only needs to know which kind a person holds) can
 * never drift apart.
 *
 * ```
 * s1$<salt>$<sha256(salt:pin)>        numeric PIN            — shipped in P4
 * s2$<salt>$<sha256^N(salt:…)>        alphanumeric password  — round-1 feedback
 * ```
 *
 * **`s2` is additive, deliberately.** Labs already running P4 have live `s1`
 * PINs sitting in their synced `staff` rows; those must keep verifying forever,
 * so the verifier dispatches on the stored prefix instead of assuming a single
 * scheme. Both keep the salt INSIDE the value for the same reason `s1` always
 * did — a credential set on the front-desk PC has to verify on the pathologist's
 * laptop, which carries an install salt of its own.
 *
 * **Neither scheme is a security boundary** — see [StaffRepository]'s KDoc. `s2`
 * iterates SHA-256 [PASSWORD_ROUNDS] times, which moves guessing a weak password
 * from "instant" to "slow-ish"; it is nowhere near PBKDF2/scrypt/argon2, and the
 * SQLite file is readable by anyone sitting at the machine either way. The
 * iteration count is baked into the scheme *name* rather than stored alongside
 * it: changing the cost means minting `s3`, so no existing value ever becomes
 * unverifiable.
 */
internal object SecretHash {
    const val PIN = "s1"
    const val PASSWORD = "s2"

    /** Modest on purpose: [sha256Hex] is pure Kotlin and this runs on low-end
     *  Android tablets as well as lab desktops. ~12k rounds costs tens of ms. */
    private const val PASSWORD_ROUNDS = 12_000

    fun hashPin(pin: String, salt: String): String = "$PIN\$$salt\$${sha256Hex("$salt:$pin")}"

    fun hashPassword(password: String, salt: String): String =
        "$PASSWORD\$$salt\$${stretch(password, salt)}"

    /**
     * [PIN], [PASSWORD], or null when there is no credential — or when the value
     * is unreadable, which is what a row written by a NEWER seat using a scheme
     * this build predates looks like. Unreadable reads as "no credential I can
     * check", and [verify] refuses it rather than opening.
     */
    fun schemeOf(stored: String?): String? {
        if (stored.isNullOrBlank()) return null
        val parts = stored.split('$')
        if (parts.size != 3) return null
        return parts[0].takeIf { it == PIN || it == PASSWORD }
    }

    fun verify(secret: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 3) return false
        val (scheme, salt, digest) = parts
        return when (scheme) {
            PIN -> sha256Hex("$salt:$secret") == digest
            PASSWORD -> stretch(secret, salt) == digest
            else -> false
        }
    }

    private fun stretch(secret: String, salt: String): String {
        var h = sha256Hex("$salt:$secret")
        repeat(PASSWORD_ROUNDS - 1) { h = sha256Hex("$salt:$h") }
        return h
    }
}
