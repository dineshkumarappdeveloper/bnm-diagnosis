package com.bnm.lab.lab

import com.bnm.lab.license.LicenseManager
import com.bnm.lab.staff.sha256Hex

/**
 * Which accession series this computer issues from: the `S2` in `ACC-S2-00042`.
 *
 * Up to 1.2.0 every computer issued from `S1`, because nothing ever set a seat.
 * Two PCs of one offline lab, or an old PC and the replacement that took over its
 * seat, printed the same ACC-S1-00017 on two patients' tubes. On a connected lab
 * it was worse: accession_no is UNIQUE, so one seat's copy arriving at the other
 * replaced the local order, or wedged registration on the seat that was behind.
 *
 * The seat is now derived, never typed in and never stored as a preference:
 *  • SERVER seat `S<n>`: only for a connected licence, and only from a seat number
 *    admin-lab assigns once per device row and never reuses (`seat_no` on
 *    /activate and /heartbeat, see [LicenseManager.seatNo]). The `L<n>` ordinal
 *    behind /billing-series is NOT such a number. It is the device's index among
 *    rows sorted by random UUID, so a newly activated seat can take the number
 *    an existing seat already issues from.
 *  • INSTALL seat: everything else, meaning every offline-edition computer and a
 *    connected one the server has not numbered yet. Three characters minted from
 *    [LicenseManager.deviceId] (created once, never rotated): a letter that is
 *    never S, then two Crockford base-32 characters (no I, L, O or U to misread).
 *    It can never equal a server seat, and a replacement PC has a new device id,
 *    so it never continues the old PC's numbers. The same code serves both
 *    editions, so an edition switch keeps the series.
 *
 * THREE characters is the entire budget. Every sticker preset is sized for a
 * 13-character accession at a scannable 2-dot barcode module
 * ([com.bnm.lab.print.StickerRender.minWidthMm]), and "ACC-" + seat + "-00042"
 * leaves exactly three. That caps install seats at 21,504 codes: two offline
 * seats share one with odds of about 1 in 21,500, five seats about 1 in 2,150.
 * A server seat has no such odds, which is why a connected licence switches to
 * one as soon as admin-lab sends it.
 *
 * Moving to a new seat never renames anything. Issued accessions stay on their
 * orders, the old series row stays in accession_series as history, and
 * LabRepository.createLabOrder never re-issues a number the database already
 * holds under the series it allocates from.
 */
object AccessionSeat {
    /** The series every install issued from before seats were derived. History only. */
    const val LEGACY = "S1"

    /** The highest server seat that still fits the 13-character accession. */
    const val MAX_SERVER_SEAT = 99

    private const val LEADING = "ABCDEFGHJKMNPQRTVWXYZ"          // Crockford letters, minus S
    private const val TRAILING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base-32

    /** How many distinct install seats exist (21,504). */
    val INSTALL_SEATS: Int = LEADING.length * TRAILING.length * TRAILING.length

    fun server(seatNo: Int): String = "S$seatNo"

    fun install(deviceId: String): String {
        // First 32 bits of SHA-256: spread evenly whatever shape the id has.
        var n = sha256Hex(deviceId).take(8).toLong(16) % INSTALL_SEATS
        val third = TRAILING[(n % TRAILING.length).toInt()]
        n /= TRAILING.length
        val second = TRAILING[(n % TRAILING.length).toInt()]
        n /= TRAILING.length
        return "${LEADING[n.toInt()]}$second$third"
    }

    /** The seat for these licence facts. A standalone licence always takes its install seat. */
    fun resolve(standalone: Boolean, serverSeatNo: Int?, deviceId: String): String =
        if (!standalone && serverSeatNo != null && serverSeatNo in 1..MAX_SERVER_SEAT) server(serverSeatNo)
        else install(deviceId)

    /** The seat THIS computer issues from right now. */
    fun of(licence: LicenseManager): String =
        resolve(licence.state.value.isStandalone, licence.seatNo, licence.deviceId)

    /**
     * The first string that sorts after every accession under [series]
     * (`ACC-S2-` → `ACC-S2.`). `[series, seriesEnd)` is exactly the accessions
     * starting with [series], as an index range. `ACC-S21-…` falls outside it.
     */
    internal fun seriesEnd(series: String): String = series.dropLast(1) + (series.last() + 1)
}
