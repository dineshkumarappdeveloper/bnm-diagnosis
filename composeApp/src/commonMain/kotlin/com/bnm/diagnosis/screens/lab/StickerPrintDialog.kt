package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.billing.PrintProfiles
import com.bnm.diagnosis.print.Code128
import com.bnm.diagnosis.print.SampleSticker
import com.bnm.diagnosis.print.printSampleStickers
import kotlinx.coroutines.launch

/**
 * "Print sample stickers" — one row per sample type with a count, a live
 * preview of the accession barcode, and the print button.
 *
 * Shown as an OPTION after registration (the user's rule) and from the order
 * for reprints — a torn label or a second tube. The profile's "stickers per
 * sample" is the starting count on every row; the desk adjusts per order.
 */
@Composable
fun StickerPrintDialog(
    accession: String,
    stickers: List<SampleSticker>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val profile = remember { PrintProfiles.barcode }
    var counts by remember(stickers) { mutableStateOf(List(stickers.size) { profile.copies }) }
    var printing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var printed by remember { mutableStateOf(false) }
    val ready = profile.enabled && profile.isDirectlyConnected
    val total = counts.sum()

    fun print() {
        if (printing || total == 0) return
        printing = true; status = null
        scope.launch {
            val jobs = stickers.flatMapIndexed { i, s -> List(counts[i]) { s } }
            val r = runCatching { printSampleStickers(jobs, profile) }
                .getOrElse { "Print failed: ${it.message}" }
            status = r
            printed = r.startsWith("Sent to")
            printing = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!printing) onDismiss() },
        title = { Text("Sample stickers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                stickers.firstOrNull()?.let {
                    Text(
                        "${it.patientName} · ${it.ageSex}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
                BarcodePreview(accession)
                stickers.forEachIndexed { i, s ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(s.sampleType, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { counts = counts.mapIndexed { j, c -> if (j == i) (c - 1).coerceAtLeast(0) else c } },
                            enabled = counts[i] > 0 && !printing,
                        ) { Text("−") }
                        Text(
                            counts[i].toString(), Modifier.width(28.dp), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        )
                        OutlinedButton(
                            onClick = { counts = counts.mapIndexed { j, c -> if (j == i) (c + 1).coerceAtMost(9) else c } },
                            enabled = counts[i] < 9 && !printing,
                        ) { Text("+") }
                    }
                }
                Text(
                    profile.summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!ready) {
                    Text(
                        if (!profile.enabled) "Sticker printing is turned off — Settings ▸ Printing ▸ Barcode sticker."
                        else "No sticker printer set up yet — Settings ▸ Printing ▸ Barcode sticker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                status?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = if (printed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (printed) {
                Button(onClick = onDismiss) { Text("Done") }
            } else {
                Button(onClick = { print() }, enabled = ready && !printing && total > 0) {
                    Text(if (printing) "Printing…" else "Print $total sticker${if (total == 1) "" else "s"}")
                }
            }
        },
        dismissButton = {
            if (printed) {
                TextButton(onClick = { print() }, enabled = !printing) { Text("Print again") }
            } else {
                TextButton(onClick = onDismiss, enabled = !printing) { Text("Skip") }
            }
        },
    )
}

/**
 * The Code 128 symbol drawn as bars, with the accession under it — what the
 * printer will put on the tube, so a misread accession is caught on screen.
 * Quiet zone of 10 modules each side, as the spec asks.
 */
@Composable
fun BarcodePreview(accession: String, modifier: Modifier = Modifier) {
    val modules = remember(accession) { runCatching { Code128.modules(accession) }.getOrNull() }
    Column(
        modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (modules == null) {
            Text("Cannot encode \"$accession\"", color = Color.Red, style = MaterialTheme.typography.bodySmall)
        } else {
            Canvas(Modifier.fillMaxWidth().height(44.dp)) {
                val quiet = 10
                val n = modules.size + quiet * 2
                val w = size.width / n
                for (i in modules.indices) {
                    if (modules[i]) {
                        drawRect(
                            color = Color.Black,
                            topLeft = androidx.compose.ui.geometry.Offset((quiet + i) * w, 0f),
                            size = androidx.compose.ui.geometry.Size(w, size.height),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                accession, color = Color.Black, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
