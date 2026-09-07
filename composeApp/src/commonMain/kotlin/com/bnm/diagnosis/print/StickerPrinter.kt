package com.bnm.diagnosis.print

import com.bnm.diagnosis.billing.PrintProfile
import com.bnm.diagnosis.billing.PrintProfiles
import com.bnm.diagnosis.lab.LabOrder
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.Patient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Sample-tube stickers for a registered order: what goes on each label and
 * how the job reaches the sticker printer.
 *
 * ONE STICKER PER SAMPLE TYPE. An order for CBC + LFT + urine routine needs an
 * EDTA tube, a plain tube and a urine container — three labels, each carrying
 * the SAME accession barcode (the order is the specimen set; the type line
 * tells the bench which tube this is). Tests sharing a sample type share a
 * tube and therefore a sticker. The operator can raise a count in the dialog
 * when a type needs two tubes.
 *
 * THE BARCODE ENCODES THE ACCESSION ONLY. That is the industry practice (CLSI
 * AUTO12): the barcode is the specimen identifier the analyzer and the
 * worklist look up; date/time and patient details are printed as text for
 * the human reading the tube, not folded into the code.
 */
fun buildSampleStickers(
    order: LabOrder,
    patient: Patient,
    tests: List<LabTest>,
    labName: String,
): List<SampleSticker> {
    val age = LabRepository.resolveAgeYears(patient.dob, patient.ageYears)
    val ageLabel = when {
        age == null -> "-"
        age < 1.0 -> "${(age * 12).toInt().coerceAtLeast(0)} mo"
        else -> "${age.toInt()} y"
    }
    val ageSex = "$ageLabel / ${patient.sex.uppercase()}"
    // The COLLECTION time is what the specimen standard wants on the tube; at
    // registration (the usual print moment) it is not known yet, so the
    // registration time stands in — a reprint after collection carries the real one.
    val stamp = stickerStamp(order.collectedAt ?: order.createdAt)
    // First-appearance order, so the tubes come off the printer in the order
    // the tests were ticked.
    val types = LinkedHashSet<String>()
    for (t in tests) types += t.sampleType.trim().lowercase().ifEmpty { "other" }
    if (types.isEmpty()) types += "other" // an order with no resolvable tests still gets its accession label
    return types.map { type ->
        SampleSticker(
            accession = order.accessionNo,
            patientName = patient.name,
            ageSex = ageSex,
            sampleType = sampleTypeLabel(type),
            registeredAt = stamp,
            labName = labName,
        )
    }
}

/** Catalog `sample_type` → the word on the tube. */
fun sampleTypeLabel(raw: String): String = when (raw.trim().lowercase()) {
    "blood" -> "BLOOD"
    "serum" -> "SERUM"
    "urine" -> "URINE"
    "stool" -> "STOOL"
    "swab" -> "SWAB"
    "" , "other" -> "SAMPLE"
    else -> raw.trim().uppercase()
}

/** The stock this profile is loaded with, plus the print-position tuning. */
fun PrintProfile.stickerSpec(): StickerSpec = StickerSpec(
    widthMm = stickerWidthMm, heightMm = stickerHeightMm, gapMm = stickerGapMm,
    dpi = stickerDpi, rotate = stickerRotate, shiftXmm = stickerShiftXmm, shiftYmm = stickerShiftYmm,
)

/**
 * Send [stickers] to the sticker printer, one label each (the dialog has
 * already multiplied counts). Returns the transport's status; success starts
 * with "Sent to", like every other print path in the app.
 *
 * "system" on this profile means an OS-installed (USB) printer fed RAW bytes —
 * never the print dialog, which would rasterise a page a label printer cannot
 * use.
 */
suspend fun printSampleStickers(
    stickers: List<SampleSticker>,
    profile: PrintProfile = PrintProfiles.barcode,
): String {
    if (!profile.enabled) return "Sticker printing is turned off in Settings ▸ Printing."
    if (stickers.isEmpty()) return "Nothing to print"
    val bytes = StickerRender.render(
        language = LabelLanguage.fromSlug(profile.labelLanguage),
        stickers = stickers,
        spec = profile.stickerSpec(),
        copies = 1,
    )
    return sendToStickerPrinter(bytes, profile)
}

/**
 * Ask the printer to learn the label gap (TSPL GAPDETECT / ZPL ~JC). It feeds
 * a few labels doing so. The cure for a print that drifts a little further
 * every sticker — run it after every roll change.
 */
suspend fun calibrateStickerPrinter(profile: PrintProfile = PrintProfiles.barcode): String {
    if (!profile.enabled) return "Sticker printing is turned off in Settings ▸ Printing."
    val bytes = StickerRender.calibrate(LabelLanguage.fromSlug(profile.labelLanguage), profile.stickerSpec())
        ?: return "A receipt printer has no label gap to calibrate."
    val r = sendToStickerPrinter(bytes, profile)
    return if (r.startsWith("Sent to")) "Calibrating — the printer feeds a few labels while it learns the gap." else r
}

/** Raw bytes down the profile's transport. Off the main thread: the spooler
 *  paths block (a process wait on CUPS). Success starts with "Sent to". */
suspend fun sendToStickerPrinter(bytes: ByteArray, profile: PrintProfile): String =
    withContext(Dispatchers.Default) {
        when (profile.connection) {
            "network" -> printToNetworkPrinter(profile.ip, profile.port, bytes)
            "bluetooth" -> BtPrinter.getInstance().printBytes(profile.btAddress, bytes)
            else -> printRaw(profile.printerName, bytes)
        }
    }

/** A deterministic label for Settings ▸ "Print a test sticker". */
fun sampleSticker(labName: String): SampleSticker = SampleSticker(
    accession = "ACC-S1-00042",
    patientName = "Kavitha Subramanian",
    ageSex = "42 y / F",
    sampleType = "SERUM",
    registeredAt = "07/09/26 09:12",
    labName = labName,
)

/** ISO instant → "dd/MM/yy HH:mm" in the device timezone; a label has no room
 *  for more, and the year is on the requisition anyway. */
internal fun stickerStamp(iso: String): String {
    val dt = runCatching {
        kotlin.time.Instant.parse(iso).toLocalDateTime(TimeZone.currentSystemDefault())
    }.getOrNull() ?: return iso.take(10)
    val d = dt.date.toString() // yyyy-MM-dd
    val hh = dt.hour.toString().padStart(2, '0')
    val mm = dt.minute.toString().padStart(2, '0')
    return "${d.substring(8, 10)}/${d.substring(5, 7)}/${d.substring(2, 4)} $hh:$mm"
}
