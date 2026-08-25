package com.bnm.diagnosis.screens.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.bnm.diagnosis.api.models.BillingStation
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.api.models.InvoiceSettings
import com.bnm.diagnosis.billing.BillingPrefs
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.print.BtPrinter
import com.bnm.diagnosis.print.EscPos
import com.bnm.diagnosis.print.bucketLinesByStation
import com.bnm.diagnosis.print.printReceipt
import com.bnm.diagnosis.print.printToNetworkPrinter
import com.bnm.diagnosis.print.renderReceiptText
import com.bnm.diagnosis.print.renderStationToken
import com.bnm.diagnosis.util.formatDecimal2
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Post-save popup (replaces navigating to a full page): confirms the bill and
 *  offers Print receipt / View details / New sale. [changeDue] (defaults to the
 *  invoice's recorded change) is shown prominently for cash sales.
 *
 *  Printing contract: with a CONNECTED printer (LAN IP or Bluetooth device
 *  configured + printing enabled) the receipt prints AUTOMATICALLY and the
 *  dialog closes into a new sale; without one, the dialog stays with the
 *  Print button. A successful print — auto or manual — closes the dialog,
 *  and each invoice prints at most ONCE per device (the receipt itself
 *  carries the cash tendered/change, so nothing is lost by closing).
 *
 *  Collection tokens (self-service): when invoice_settings.stations is
 *  configured, each non-empty station bucket prints ONE small token after the
 *  main receipt (station's own printer_ip if set, else the main transport;
 *  the system dialog gets them appended into the one document). Token failures
 *  never fail the bill — the main receipt is already out. */
@Composable
fun SaveResultDialog(businessId: String, settings: InvoiceSettings?, businessName: String, invoice: Invoice, onView: () -> Unit, onDone: () -> Unit, changeDue: Double? = null) {
    val scope = rememberCoroutineScope()
    val prefs = remember { BillingPrefs() }
    val repo = LocalBillingRepository.current
    var printMsg by remember { mutableStateOf<String?>(null) }
    var printing by remember { mutableStateOf(false) }
    var printed by remember { mutableStateOf(prefs.isInvoicePrinted(invoice.id)) }

    // "Connected" = a real printer target is configured. The system/share
    // transport is NOT auto-printed (popping an OS dialog uninvited isn't
    // "a connected printer") but stays available on the button.
    val printerReady = remember {
        prefs.printerEnabled && when (prefs.printerConnection) {
            "network" -> prefs.printerIp.isNotBlank()
            "bluetooth" -> prefs.printerBtAddress.isNotBlank()
            else -> false
        }
    }

    // Returns true when the receipt went out (→ mark printed + close).
    suspend fun doPrint(): Boolean {
        // ── Collection tokens: bucket the bill's lines by station (explicit
        // product_ids win, else category). No stations configured → no buckets →
        // exactly the pre-token behavior.
        val stations = settings?.stations.orEmpty()
        val buckets = if (stations.isEmpty()) emptyList() else withContext(Dispatchers.Default) {
            val ids = invoice.lineItems.mapNotNull { it.productId?.takeIf { id -> id.isNotBlank() } }.distinct()
            val cats = if (ids.isEmpty()) emptyMap()
            else runCatching { repo.categoriesFor(businessId, ids) }.getOrDefault(emptyMap())
            bucketLinesByStation(stations, invoice.lineItems, cats)
        }
        // ONE token number per bill — the same number on all its station tokens,
        // persisted so a reprint reuses it.
        val tokenNo = if (buckets.isEmpty()) 0 else prefs.tokenNumberFor(invoice.id)
        val tokenFailures = mutableListOf<String>()

        val result = withContext(Dispatchers.Default) {
            val body = renderReceiptText(settings, businessName, invoice, prefs.paperWidth)
            val tokens = buckets.map { (st, ls) ->
                st to renderStationToken(st.name.ifBlank { st.key }, tokenNo, invoice, ls, prefs.paperWidth)
            }

            // A station with its OWN LAN printer prints there directly (any main
            // transport). Returns false when the station has no printer_ip.
            suspend fun printStationDirect(st: BillingStation, tokenBody: String): Boolean {
                val ip = st.printerIp?.trim().orEmpty()
                if (ip.isBlank()) return false
                val r = try { printToNetworkPrinter(ip, prefs.printerPort, EscPos.encode(tokenBody)) }
                catch (e: Throwable) { "print threw: ${e.message}" }
                if (!r.startsWith("Sent to")) tokenFailures += "Token for ${st.name.ifBlank { st.key }} failed: $r"
                return true
            }

            when (prefs.printerConnection) {
                "network", "bluetooth" -> {
                    suspend fun send(text: String): String =
                        if (prefs.printerConnection == "network") printToNetworkPrinter(prefs.printerIp, prefs.printerPort, EscPos.encode(text))
                        else BtPrinter.getInstance().printBytes(prefs.printerBtAddress, EscPos.encode(text))
                    val main = send(body)
                    if (main.startsWith("Sent to")) {
                        // Main receipt is out — tokens follow; a failed token is
                        // reported but NEVER fails the whole print.
                        for ((st, tokenBody) in tokens) {
                            if (printStationDirect(st, tokenBody)) continue
                            val r = try { send(tokenBody) } catch (e: Throwable) { "print threw: ${e.message}" }
                            if (!r.startsWith("Sent to")) tokenFailures += "Token for ${st.name.ifBlank { st.key }} failed: $r"
                        }
                    }
                    main
                }
                else -> {
                    // System transport: the OS dialog prints ONE document — append
                    // the tokens after blank lines (each opens with its own '='
                    // tear rule). Stations with their own LAN printer still print
                    // there directly.
                    val doc = StringBuilder(body)
                    for ((st, tokenBody) in tokens) {
                        if (printStationDirect(st, tokenBody)) continue
                        doc.append("\n\n").append(tokenBody)
                    }
                    printReceipt(invoice.invoiceNumber ?: "Receipt", doc.toString())
                }
            }
        }
        // Thermal transports report success as "Sent to …"; the system sheet is
        // user-mediated, so reaching it counts as handed off.
        val ok = result.startsWith("Sent to") || prefs.printerConnection !in setOf("network", "bluetooth")
        if (ok) {
            prefs.markInvoicePrinted(invoice.id); printed = true
            if (tokenFailures.isNotEmpty()) printMsg = tokenFailures.joinToString("\n")
        } else printMsg = result
        return ok
    }

    // Auto-print once on a configured printer, then close into a new sale.
    LaunchedEffect(invoice.id) {
        if (prefs.autoPrint && printerReady && !printed) {
            printing = true; printMsg = "Printing…"
            val ok = try { doPrint() } catch (e: Throwable) { printMsg = "Print failed: ${e.message}"; false }
            printing = false
            if (ok) {
                // Keep a token-failure notice on screen a moment longer than the
                // plain "Printed" confirmation before closing into a new sale.
                val tokenIssue = printMsg?.startsWith("Token") == true
                if (!tokenIssue) printMsg = "Printed"
                delay(if (tokenIssue) 3000 else 1200)
                onDone()
            }
        }
    }

    Dialog(onDismissRequest = onDone) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column(
                Modifier.width(360.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Text("Bill saved", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${invoice.displayNumber} · ₹ ${formatDecimal2(invoice.total)} · ${invoice.paymentLabel}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (changeDue ?: invoice.changeDue)?.takeIf { it > 0.005 }?.let {
                    Text(
                        "Change: ₹ ${formatDecimal2(it)}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                printMsg?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                Button(
                    onClick = {
                        if (printing || printed) return@Button
                        printing = true; printMsg = null
                        scope.launch {
                            // try/finally so the spinner ALWAYS clears — if rendering or
                            // printing throws, printing must still reset (else it hangs).
                            val ok = try {
                                doPrint()
                            } catch (e: Throwable) {
                                printMsg = "Print failed: ${e.message}"; false
                            } finally {
                                printing = false
                            }
                            // Give a token-failure notice a beat before closing.
                            if (ok) { if (printMsg?.startsWith("Token") == true) delay(2500); onDone() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp), enabled = !printing && !printed,
                ) {
                    if (printing) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                    Text(if (printed) "Printed ✓" else "Print receipt")
                }
                OutlinedButton(onClick = onView, modifier = Modifier.fillMaxWidth()) { Text("View details") }
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("New sale") }
            }
        }
    }
}
