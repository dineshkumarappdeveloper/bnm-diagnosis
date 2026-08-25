package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.api.models.Invoice
import com.bnm.diagnosis.billing.BillingPrefs
import com.bnm.diagnosis.billing.GstLine
import com.bnm.diagnosis.billing.GstTaxEngine
import com.bnm.diagnosis.billing.PaymentChoice
import com.bnm.diagnosis.chat.LocalBillingRepository
import com.bnm.diagnosis.chat.LocalOutboxSender
import com.bnm.diagnosis.connectivity.LocalConnectivity
import com.bnm.diagnosis.lab.EmrInboxItem
import com.bnm.diagnosis.lab.EmrTestMatch
import com.bnm.diagnosis.lab.EmrTestMatchKind
import com.bnm.diagnosis.lab.LabOrder
import com.bnm.diagnosis.lab.LabPanel
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.lab.LabTest
import com.bnm.diagnosis.lab.LocalLabRepository
import com.bnm.diagnosis.lab.Patient
import com.bnm.diagnosis.lab.Referrer
import com.bnm.diagnosis.screens.billing.PaymentLinkDialog
import com.bnm.diagnosis.screens.billing.PaymentSheet
import com.bnm.diagnosis.screens.billing.SaveResultDialog
import com.bnm.diagnosis.util.formatDecimal2
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch

/**
 * Registration desk: pick/create the patient → pick tests & panels → referrer +
 * priority → register. Registering creates the lab order (accession allocated)
 * and then bills it through the EXISTING billing pipeline (NIL-GST service
 * lines) with the payment sheet + save-result dialog, linking the invoice to
 * the order. Bill failure (e.g. no counter series bound) is NON-fatal — the
 * order stands and the lab can bill separately.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalUuidApi::class)
@Composable
fun NewOrderScreen(
    businessId: String,
    labName: String,
    onBack: () -> Unit,
    /** Registration finished: pop home (snackbar shows [accession]); non-null
     *  invoiceId = the operator asked to view the bill's details. */
    onFinished: (accession: String, invoiceId: String?) -> Unit,
    /** P3 EMR bridge: registering FOR this inbox row — pre-selects the test the
     *  doctor picked (by CATALOG CODE when the row carries one, else by name;
     *  else the order note carries it), auto-identifies the patient from the
     *  row's phone, and reports the match back through [onEmrRegistered] after
     *  the order is created. */
    emrOrderId: String? = null,
    onEmrRegistered: suspend (emrId: String, order: LabOrder) -> Unit = { _, _ -> },
) {
    val labRepo = LocalLabRepository.current
    val billing = LocalBillingRepository.current
    val outbox = LocalOutboxSender.current
    val settings by billing.invoiceSettingsFlow(businessId).collectAsState(null)
    val isOnline by LocalConnectivity.current.isOnline.collectAsState(true)
    val scope = rememberCoroutineScope()

    // ── Patient ──
    var patient by remember { mutableStateOf<Patient?>(null) }

    // ── Tests & panels ──
    var allTests by remember { mutableStateOf<List<LabTest>>(emptyList()) }
    var allPanels by remember { mutableStateOf<List<LabPanel>>(emptyList()) }
    val selectedTestIds = remember { mutableStateListOf<String>() }
    val selectedPanelIds = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        allTests = runCatching { labRepo.listTests() }.getOrDefault(emptyList())
        allPanels = runCatching { labRepo.listPanels() }.getOrDefault(emptyList())
    }
    val testById = remember(allTests) { allTests.associateBy { it.id } }

    // ── Referrer + priority ──
    var referrers by remember { mutableStateOf<List<Referrer>>(emptyList()) }
    var referrer by remember { mutableStateOf<Referrer?>(null) }
    var showAddReferrer by remember { mutableStateOf(false) }
    var priority by remember { mutableStateOf("routine") }
    LaunchedEffect(Unit) { referrers = runCatching { labRepo.listReferrers() }.getOrDefault(emptyList()) }

    // ── P4 B2B pricing: the selected referrer's negotiated rate list. Switching
    // referrer re-loads it, which re-prices the whole cart (and the picker) on
    // the spot. Empty map = walk-in / catalog rates. The map is only an OVERRIDE
    // view — LabRepository.resolvePrice is still the one brain, and
    // createLabOrder re-applies it server-side-of-the-UI when it snapshots the
    // order lines, so what's shown here is exactly what gets billed. ──
    var rates by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(referrer?.id) {
        rates = runCatching { labRepo.ratesFor(referrer?.id) }.getOrDefault(emptyMap())
    }
    fun priceOf(testId: String): Double =
        LabRepository.resolvePrice(testById[testId]?.price ?: 0.0, rates[testId])

    // Panels expand to their tests, duplicates collapse — the SAME expansion
    // createLabOrder performs, so the running total matches the bill.
    val expandedIds = remember(selectedTestIds.toList(), selectedPanelIds.toList(), allPanels) {
        val out = LinkedHashSet<String>()
        out += selectedTestIds
        selectedPanelIds.forEach { pid -> allPanels.firstOrNull { it.id == pid }?.let { out += it.testIds } }
        out
    }
    val runningTotal = expandedIds.sumOf { priceOf(it) }
    val catalogTotal = expandedIds.sumOf { testById[it]?.price ?: 0.0 }

    // ── P3b EMR prefill ──────────────────────────────────────────────────────
    // Two independent jobs, both driven off the locally-synced inbox row (no
    // network): resolve the TEST (catalog code first — LabRepository.matchEmrTest
    // is the one brain), and identify the PATIENT (exact phone → preselect / pick
    // / prefill the new-patient form). Nothing is auto-CREATED: a patient the lab
    // has never seen is shown to the tech in the form, pre-typed, to confirm.
    var emrRow by remember { mutableStateOf<EmrInboxItem?>(null) }
    var emrMatch by remember { mutableStateOf<EmrTestMatch?>(null) }
    var emrPrefill by remember { mutableStateOf<EmrPatientPrefill?>(null) }
    var emrPhoneCandidates by remember { mutableStateOf<List<Patient>>(emptyList()) }
    var emrPatientNote by remember { mutableStateOf<String?>(null) }
    var orderNotes by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(emrOrderId) {
        val row = emrOrderId?.let { runCatching { labRepo.emrById(it) }.getOrNull() } ?: return@LaunchedEffect
        emrRow = row

        // ── test: code beats name beats fuzzy beats "carry it as a note" ──
        val match = runCatching { labRepo.resolveEmrTest(row) }
            .getOrDefault(EmrTestMatch(null, EmrTestMatchKind.NONE))
        emrMatch = match
        match.test?.let { if (it.id !in selectedTestIds) selectedTestIds.add(it.id) }
        orderNotes = buildString {
            append("EMR order: ").append(row.testName)
            row.testCode?.takeIf { it.isNotBlank() }?.let { append(" [").append(it).append(']') }
            row.visitNumber?.takeIf { it.isNotBlank() }?.let { append(" · visit ").append(it) }
            row.instructions?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            if (match.test == null) append(" (not in catalog — pick the closest test)")
        }

        // ── patient: exact normalized-digit phone match against the local registry ──
        val byPhone = runCatching { labRepo.patientsByPhone(row.patientPhone) }.getOrDefault(emptyList())
        if (row.hasIdentity) emrPrefill = EmrPatientPrefill.from(row)
        when {
            byPhone.size == 1 -> {
                patient = byPhone.first()
                emrPatientNote = "Matched on ${row.patientPhone} from the clinic — Change to override"
            }
            byPhone.size > 1 -> {
                emrPhoneCandidates = byPhone
                emrPatientNote = null
            }
        }
    }

    // ── Save flow state ──
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var savedOrder by remember { mutableStateOf<LabOrder?>(null) }       // order registered, sheet pending
    var billLines by remember { mutableStateOf<List<GstLine>>(emptyList()) }
    var savedInvoice by remember { mutableStateOf<Invoice?>(null) }      // → SaveResultDialog
    var linkInvoice by remember { mutableStateOf<Invoice?>(null) }       // → PaymentLinkDialog
    var billError by remember { mutableStateOf<String?>(null) }          // non-fatal bill failure
    var billingInFlight by remember { mutableStateOf(false) }            // one bill per order

    fun register() {
        val p = patient ?: run { error = "Pick or create the patient first"; return }
        if (expandedIds.isEmpty()) { error = "Select at least one test"; return }
        if (saving) return
        saving = true; error = null
        scope.launch {
            labRepo.createLabOrder(
                patientId = p.id,
                testIds = selectedTestIds.toList(),
                panelIds = selectedPanelIds.toList(),
                referrerId = referrer?.id,
                priority = priority,
                notes = orderNotes,
            ).onSuccess { order ->
                // P3 EMR bridge: store the match + best-effort acknowledge the
                // clinic (fire-and-forget; the sync sweep catches up offline).
                if (emrOrderId != null) {
                    scope.launch { runCatching { onEmrRegistered(emrOrderId, order) } }
                }
                // Snapshot the bill lines from the ORDER (names/prices frozen
                // there) — diagnostic services are NIL-GST in India (gstRate 0).
                val orderTests = runCatching { labRepo.orderTests(order.id) }.getOrDefault(emptyList())
                billLines = orderTests.map {
                    GstLine(description = it.testName, hsn = null, quantity = 1.0, rate = it.price, gstRate = 0.0)
                }
                savedOrder = order   // → PaymentSheet
                saving = false
            }.onFailure {
                saving = false; error = it.message ?: "Could not register the order"
            }
        }
    }

    /** Create the GST bill for the registered order and link it. [choice] null =
     *  bill saved unpaid (sheet dismissed / "save without payment"). */
    fun createBill(order: LabOrder, choice: PaymentChoice?, forLink: Boolean = false) {
        val p = patient ?: return
        if (billingInFlight) return
        billingInFlight = true
        scope.launch {
            billing.createInvoiceLocal(
                businessId = businessId,
                supplierStateCode = settings?.taxId?.trim()?.take(2),
                placeOfSupply = null,
                customerName = p.name,
                customerPhone = p.phone?.trim()?.ifBlank { null },
                customerGstin = null,
                lines = billLines,
                dueDays = settings?.dueDays ?: 7,
                notes = "Lab order ${order.accessionNo}",
                payment = choice,
            ).onSuccess { inv ->
                runCatching { labRepo.linkInvoice(order.id, inv.id) }
                outbox.kick()
                if (forLink) linkInvoice = inv else savedInvoice = inv
            }.onFailure {
                // NON-fatal: the lab order stands; labs may bill separately.
                billError = it.message ?: "Bill could not be created"
                billingInFlight = false // allow a retry path via the sheet if reshown
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New order") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            val rated = expandedIds.count { it in rates }
                            Text(
                                "${expandedIds.size} test${if (expandedIds.size == 1) "" else "s"}" +
                                    if (rated > 0) " · $rated at ${referrer?.name ?: "referrer"} rates" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("₹ ${formatDecimal2(runningTotal)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                // Show what the walk-in list price would have been
                                // whenever the rate list moved the number.
                                if (rated > 0 && kotlin.math.abs(catalogTotal - runningTotal) > 0.005) {
                                    Text(
                                        "₹ ${formatDecimal2(catalogTotal)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textDecoration = TextDecoration.LineThrough,
                                        modifier = Modifier.padding(bottom = 2.dp),
                                    )
                                }
                            }
                        }
                        Button(onClick = { register() }, enabled = !saving && patient != null && expandedIds.isNotEmpty()) {
                            if (saving) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                            Text(if (saving) "Registering…" else "Register & bill")
                        }
                    }
                }
            }
        },
    ) { inner ->
        BoxWithConstraints(Modifier.padding(inner).fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        emrRow?.let { EmrBanner(it, emrMatch) }
                        PatientSection(
                            // A manual pick/clear retires the "matched on …" note
                            // — it would otherwise explain the wrong patient.
                            patient, onPatient = { patient = it; emrPatientNote = null },
                            matchedNote = emrPatientNote, prefill = emrPrefill,
                            phoneCandidates = emrPhoneCandidates,
                        )
                        ReferrerSection(referrers, referrer, onPick = { referrer = it }, onQuickAdd = { showAddReferrer = true })
                        PrioritySection(priority) { priority = it }
                        SelectedTestsSummary(expandedIds.toList(), testById, rates)
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight()) {
                        Surface(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.fillMaxSize()) {}
                    }
                    TestPicker(
                        tests = allTests, panels = allPanels, rates = rates,
                        selectedTestIds = selectedTestIds, selectedPanelIds = selectedPanelIds,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    emrRow?.let { EmrBanner(it, emrMatch) }
                    PatientSection(
                        patient, onPatient = { patient = it; emrPatientNote = null },
                        matchedNote = emrPatientNote, prefill = emrPrefill,
                        phoneCandidates = emrPhoneCandidates,
                    )
                    ReferrerSection(referrers, referrer, onPick = { referrer = it }, onQuickAdd = { showAddReferrer = true })
                    PrioritySection(priority) { priority = it }
                    TestPicker(
                        tests = allTests, panels = allPanels, rates = rates,
                        selectedTestIds = selectedTestIds, selectedPanelIds = selectedPanelIds,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 460.dp),
                    )
                    SelectedTestsSummary(expandedIds.toList(), testById, rates)
                }
            }
        }
    }

    if (showAddReferrer) {
        ReferrerFormDialog(
            initial = null,
            onDismiss = { showAddReferrer = false },
            onSaved = { r ->
                showAddReferrer = false
                referrer = r
                scope.launch { referrers = runCatching { labRepo.listReferrers() }.getOrDefault(referrers) }
            },
        )
    }

    // ── Post-register: payment sheet → bill create → save-result dialog ──
    savedOrder?.let { order ->
        if (savedInvoice == null && linkInvoice == null && billError == null && !billingInFlight) {
            val total = GstTaxEngine.compute(billLines, settings?.taxId?.trim()?.take(2), null).total
            PaymentSheet(
                total = total,
                upiVpa = BillingPrefs().counterUpiVpa.ifBlank { null } ?: settings?.upiVpa,
                businessName = labName,
                isOnline = isOnline,
                // Dismissing still bills the order (unpaid) — every registered
                // order keeps a linked GST bill when a series is bound.
                onDismiss = { createBill(order, null) },
                onConfirm = { choice -> createBill(order, choice) },
                onPaymentLink = { createBill(order, PaymentChoice(method = "payment_link", markPaid = false), forLink = true) },
            )
        }

        linkInvoice?.let { inv ->
            PaymentLinkDialog(
                businessId = businessId,
                invoice = inv,
                onDone = { linkInvoice = null; savedInvoice = inv },
            )
        }

        savedInvoice?.let { inv ->
            SaveResultDialog(
                businessId = businessId,
                settings = settings,
                businessName = labName,
                invoice = inv,
                onView = { onFinished(order.accessionNo, inv.id) },
                onDone = { onFinished(order.accessionNo, null) },
            )
        }

        billError?.let { msg ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { onFinished(order.accessionNo, null) },
                title = { Text("Order registered — bill not created") },
                text = {
                    Text(
                        "Accession ${order.accessionNo} is registered and ready for the worklist.\n\n" +
                            "The GST bill could not be created: $msg\n\nYou can bill this order separately.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { onFinished(order.accessionNo, null) }) { Text("OK") }
                },
            )
        }
    }
}

/**
 * P3b: the clinic's order being registered. Says out loud HOW the test was
 * resolved ("matched Complete Blood Count (CBC) by code" vs "no catalog match —
 * added as a note") so nobody has to reverse-engineer a pre-ticked checkbox,
 * and echoes the visit no. + demographics the clinic sent. A legacy row without
 * an identity block still asks the desk to collect the details by hand.
 */
@Composable
private fun EmrBanner(row: EmrInboxItem, match: EmrTestMatch?) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val head = buildString {
                append("EMR order · ").append(row.testName)
                row.testCode?.takeIf { it.isNotBlank() }?.let { append(" [").append(it).append(']') }
                row.visitNumber?.takeIf { it.isNotBlank() }?.let { append(" · visit ").append(it) }
            }
            Text(head, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            row.instructions?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            match?.let {
                Text(
                    it.label(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (it.kind == EmrTestMatchKind.NONE) FontWeight.SemiBold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                if (row.hasIdentity) "Patient details came from the clinic — confirm them below."
                else "Ask the patient for their details below — this order carries no demographics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/**
 * The clinic's demographics, shaped for the new-patient form. `ageYears` is
 * DERIVED from the row's ISO dob (whole completed years, via
 * LabRepository.ageYearsFromDob) — null dob leaves the age field blank rather
 * than inventing a number, and the tech fills it in as they always have.
 */
private data class EmrPatientPrefill(
    val name: String,
    val phone: String,
    val sex: String?,          // 'M' | 'F' | 'O' — null when the clinic didn't say
    val ageYears: Long?,
    val dob: String?,
) {
    companion object {
        private val SEXES = setOf("M", "F", "O")

        fun from(row: EmrInboxItem): EmrPatientPrefill {
            val dob = row.patientDob?.trim()?.take(10)?.takeIf { it.isNotBlank() }
            return EmrPatientPrefill(
                name = row.patientName?.trim().orEmpty(),
                phone = row.patientPhone?.trim().orEmpty(),
                sex = row.patientSex?.trim()?.uppercase()?.takeIf { it in SEXES },
                ageYears = LabRepository.ageYearsFromDob(dob),
                dob = dob,
            )
        }
    }
}

// ─────────────────────────── Patient section ───────────────────────────

@OptIn(ExperimentalUuidApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PatientSection(
    patient: Patient?,
    onPatient: (Patient?) -> Unit,
    /** P3b: why this patient is already selected (EMR phone match). */
    matchedNote: String? = null,
    /** P3b: the clinic's demographics — seeds the new-patient form. */
    prefill: EmrPatientPrefill? = null,
    /** P3b: >1 local patient shares the clinic's phone — the tech picks. */
    phoneCandidates: List<Patient> = emptyList(),
) {
    val repo = LocalLabRepository.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Patient", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        if (patient != null) {
            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(patient.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            ageSexLabel(patient.dob, patient.ageYears, patient.sex) +
                                (patient.phone?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        matchedNote?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    TextButton(onClick = { onPatient(null) }) { Text("Change") }
                }
            }
            return@Column
        }

        // The EMR prefill arrives asynchronously (the inbox row is read in a
        // LaunchedEffect), so every field below is keyed on it and re-seeds the
        // moment it lands. With candidates to disambiguate we show those first;
        // otherwise we open straight into the pre-typed form — one confirming
        // tap instead of retyping the patient.
        var query by remember(prefill) { mutableStateOf(prefill?.phone.orEmpty()) }
        var matches by remember { mutableStateOf<List<Patient>>(emptyList()) }
        var showNewForm by remember(prefill, phoneCandidates) {
            mutableStateOf(prefill != null && phoneCandidates.isEmpty())
        }
        LaunchedEffect(query) { matches = runCatching { repo.searchPatients(query) }.getOrDefault(emptyList()) }

        if (phoneCandidates.isNotEmpty() && !showNewForm) {
            Text(
                "${phoneCandidates.size} patients share the clinic's phone — pick the right one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                phoneCandidates.forEach { p ->
                    val likely = prefill?.name?.takeIf { it.isNotBlank() }
                        ?.equals(p.name.trim(), ignoreCase = true) == true
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onPatient(p) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (likely) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(p.name + if (likely) " · name matches" else "",
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                ageSexLabel(p.dob, p.ageYears, p.sex) + (p.phone?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                "Not one of them? Search by name below, or register a new patient.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Always reachable, candidates or not — the phone match is a shortcut,
        // never a cage: free search and "+ New patient" stay one tap away.
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            label = { Text("Search name or phone") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!showNewForm) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                matches.take(6).forEach { p ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onPatient(p) },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(p.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                ageSexLabel(p.dob, p.ageYears, p.sex) + (p.phone?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = { showNewForm = true }, modifier = Modifier.fillMaxWidth()) { Text("+ New patient") }
        } else {
            // Inline mini-form. Age & sex are REQUIRED — reference ranges depend on them.
            // Seeded from the EMR row when there is one; the tech can retype any
            // of it before saving, and nothing is written until they do.
            var name by remember(prefill) {
                mutableStateOf(prefill?.name?.takeIf { it.isNotBlank() } ?: query.filter { !it.isDigit() }.trim())
            }
            var sex by remember(prefill) { mutableStateOf(prefill?.sex) }
            var ageText by remember(prefill) { mutableStateOf(prefill?.ageYears?.toString().orEmpty()) }
            var dob by remember(prefill) { mutableStateOf(prefill?.dob.orEmpty()) }
            var phone by remember(prefill) {
                mutableStateOf(prefill?.phone?.takeIf { it.isNotBlank() } ?: query.filter { it.isDigit() })
            }
            var formError by remember { mutableStateOf<String?>(null) }

            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (prefill != null) "New patient — from the clinic" else "New patient",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (prefill != null)
                            "Pre-filled from the EMR order — check it, then save. Age & sex are required (reference ranges depend on them)."
                        else "Age & sex are required — reference ranges depend on them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full name *") },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("M" to "Male", "F" to "Female", "O" to "Other").forEach { (code, label) ->
                            FilterChip(selected = sex == code, onClick = { sex = code }, label = { Text(label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = ageText, onValueChange = { ageText = it.filter { c -> c.isDigit() }.take(3) },
                            label = { Text("Age (years) *") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f))
                        OutlinedTextField(value = dob, onValueChange = { dob = it }, label = { Text("or DOB (YYYY-MM-DD)") },
                            singleLine = true, modifier = Modifier.weight(1.3f))
                    }
                    OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth())
                    formError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showNewForm = false }) { Text("Cancel") }
                        Button(onClick = {
                            val dobClean = dob.trim().ifBlank { null }
                            val age = ageText.trim().toLongOrNull()
                            when {
                                name.trim().isBlank() -> formError = "Name is required"
                                sex == null -> formError = "Sex is required — ranges depend on it"
                                dobClean == null && age == null -> formError = "Enter the age in years, or a DOB"
                                dobClean != null && runCatching { kotlinx.datetime.LocalDate.parse(dobClean.take(10)) }.isFailure ->
                                    formError = "DOB must be YYYY-MM-DD"
                                else -> {
                                    formError = null
                                    scope.launch {
                                        runCatching {
                                            repo.upsertPatient(Patient(
                                                id = Uuid.random().toString(), name = name.trim(), sex = sex!!,
                                                dob = dobClean, ageYears = if (dobClean == null) age else null,
                                                phone = phone.trim().ifBlank { null },
                                            ))
                                        }.onSuccess { onPatient(it) }
                                            .onFailure { formError = it.message ?: "Could not save patient" }
                                    }
                                }
                            }
                        }) { Text("Save patient") }
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Test picker ───────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestPicker(
    tests: List<LabTest>,
    panels: List<LabPanel>,
    /** P4: the selected referrer's negotiated overrides (testId → price). */
    rates: Map<String, Double>,
    selectedTestIds: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    selectedPanelIds: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    modifier: Modifier = Modifier,
) {
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) } // null = All, "__panels" = Panels
    val categories = remember(tests) { tests.mapNotNull { it.category?.takeIf { c -> c.isNotBlank() } }.distinct() }

    Column(modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tests & panels", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            label = { Text("Search test name or code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All") })
            FilterChip(selected = category == "__panels", onClick = { category = "__panels" }, label = { Text("Panels") })
            categories.forEach { c ->
                FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
            }
        }

        val q = search.trim().lowercase()
        val visiblePanels =
            if (category == null || category == "__panels")
                panels.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.code.lowercase().contains(q) }
            else emptyList()
        val visibleTests =
            if (category == "__panels") emptyList()
            else tests.filter { t ->
                (category == null || t.category == category) &&
                    (q.isEmpty() || t.name.lowercase().contains(q) || t.code.lowercase().contains(q))
            }

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (visiblePanels.isNotEmpty()) {
                items(visiblePanels, key = { "panel-${it.id}" }) { p ->
                    val selected = p.id in selectedPanelIds
                    val listPrice = p.testIds.sumOf { id -> tests.firstOrNull { it.id == id }?.price ?: 0.0 }
                    PickRow(
                        title = p.name,
                        subtitle = "Panel · ${p.testIds.size} tests",
                        price = p.testIds.sumOf { id ->
                            LabRepository.resolvePrice(tests.firstOrNull { it.id == id }?.price ?: 0.0, rates[id])
                        },
                        catalogPrice = listPrice,
                        rated = p.testIds.any { it in rates },
                        selected = selected,
                        onToggle = { if (selected) selectedPanelIds.remove(p.id) else selectedPanelIds.add(p.id) },
                    )
                }
            }
            items(visibleTests, key = { it.id }) { t ->
                val selected = t.id in selectedTestIds
                PickRow(
                    title = t.name,
                    subtitle = listOfNotNull(t.code, t.category, t.sampleType).joinToString(" · "),
                    price = LabRepository.resolvePrice(t.price, rates[t.id]),
                    catalogPrice = t.price,
                    rated = t.id in rates,
                    selected = selected,
                    onToggle = { if (selected) selectedTestIds.remove(t.id) else selectedTestIds.add(t.id) },
                )
            }
        }
    }
}

@Composable
private fun PickRow(
    title: String,
    subtitle: String,
    price: Double,
    selected: Boolean,
    onToggle: () -> Unit,
    /** Walk-in list price — struck through when the referrer's rate differs. */
    catalogPrice: Double = price,
    /** True when a negotiated rate applies to this row (tags it "rate"). */
    rated: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val overridden = rated && kotlin.math.abs(catalogPrice - price) > 0.005
            Column(horizontalAlignment = Alignment.End) {
                Text("₹ ${formatDecimal2(price)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                if (overridden) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "₹ ${formatDecimal2(catalogPrice)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textDecoration = TextDecoration.LineThrough,
                        )
                        RateTag()
                    }
                }
            }
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp))
            }
        }
    }
}

// ─────────────────────── Referrer + priority + summary ───────────────────────

@Composable
private fun ReferrerSection(
    referrers: List<Referrer>,
    selected: Referrer?,
    onPick: (Referrer?) -> Unit,
    onQuickAdd: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Referrer (optional)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.name ?: "Self / walk-in", modifier = Modifier.weight(1f))
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Self / walk-in") }, onClick = { onPick(null); open = false })
                referrers.forEach { r ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("${r.name} · ${r.kind.replace('_', ' ')}") },
                        onClick = { onPick(r); open = false },
                    )
                }
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("+ Add referrer") }, onClick = { open = false; onQuickAdd() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrioritySection(priority: String, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Priority", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = priority == "routine", onClick = { onPick("routine") }, label = { Text("Routine") })
            FilterChip(selected = priority == "urgent", onClick = { onPick("urgent") }, label = { Text("Urgent") })
        }
    }
}

@Composable
private fun SelectedTestsSummary(
    expandedIds: List<String>,
    testById: Map<String, LabTest>,
    /** P4: the referrer's negotiated overrides (testId → price). */
    rates: Map<String, Double> = emptyMap(),
) {
    if (expandedIds.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Selected (${expandedIds.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        expandedIds.forEach { id ->
            val t = testById[id] ?: return@forEach
            val price = LabRepository.resolvePrice(t.price, rates[id])
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (kotlin.math.abs(t.price - price) > 0.005) {
                    Text(
                        "₹ ${formatDecimal2(t.price)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textDecoration = TextDecoration.LineThrough,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
                Text("₹ ${formatDecimal2(price)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The small "rate" chip marking a line priced off the referrer's B2B list. */
@Composable
internal fun RateTag() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            "rate", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
