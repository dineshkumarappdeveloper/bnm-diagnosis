package com.bnm.analyzersim

/**
 * Everything one emitted sample is made of.
 *
 * Deliberately a value object with no clock and no randomness of its own: the
 * timestamp is passed in and the seed is explicit, so a frame the CLI printed
 * with `--dry-run` is byte-for-byte the frame it will put on the wire, and the
 * fidelity tests can assert against a frame built from a fixed spec.
 *
 * The build-time faults live here (not in [Faults]) because they change what
 * the analyzer *says*, not how it sends it.
 */
data class SampleSpec(
    /** The accession the analyzer was keyed with. Null = nothing keyed — the
     *  commonest real cause of a claim-queue row. */
    val specimenId: String?,
    val patientId: String? = null,
    val patientName: String? = null,
    val sex: String = "F",
    val ageYears: Int = 34,
    val profile: Profile = Profile.NORMAL,
    val seed: Long = 1L,
    /** The analyzer's own run number — MSH-10 / the Mispa sequence field. */
    val sequence: Int = 1,
    /** A quality-control material run, not a patient (Mindray MSH-11 = "Q"). */
    val qc: Boolean = false,
    val histograms: Boolean = true,
    /** Attach the DIFF scattergram bitmap (Mindray only) — tens of KB, which
     *  is exactly why it is worth sending at least once during commissioning. */
    val image: Boolean = false,
    /** Emit a parameter the driver has no code for. */
    val unknownCode: Boolean = false,
    /** Emit a unit the app's converter cannot bridge. */
    val badUnits: Boolean = false,
    /** yyyyMMddHHmmss — injected, never read from the clock in here. */
    val timestamp: String = "20260101090000",
) {
    /** The blood count this sample reports. Derived once, shared by both builders. */
    val cbc: Cbc by lazy { cbcFor(profile, seed) }

    /** PID-5 wants Last^First; a single word is treated as the family name. */
    val nameParts: Pair<String, String>
        get() {
            val n = patientName?.trim().orEmpty()
            if (n.isEmpty()) return "" to ""
            val bits = n.split(' ').filter { it.isNotBlank() }
            return if (bits.size == 1) bits[0] to "" else bits.last() to bits.dropLast(1).joinToString(" ")
        }
}
