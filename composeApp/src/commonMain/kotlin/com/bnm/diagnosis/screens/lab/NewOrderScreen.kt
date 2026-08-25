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
    /** P3 EMR bridge: registering FOR this inbox row — pre-selects the test
     *  matched by name (else the order note carries it) and reports the match
     *  back through [onEmrRegistered] after the order is created. */
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

    // ── P3 EMR prefill: match the clinic's test by name; the row carries no
    // demographics, so the patient is created from the walk-in as usual. ──
    var emrRow by remember { mutableStateOf<com.bnm.diagnosis.lab.EmrInboxItem?>(null) }
    var orderNotes by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(emrOrderId) {
        val row = emrOrderId?.let { runCatching { labRepo.emrById(it) }.getOrNull() } ?: return@LaunchedEffect
        emrRow = row
        val tests = runCatching { labRepo.listTests() }.getOrDefault(emptyList())
        val wanted = row.testName.trim()
        val match = tests.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
            ?: tests.firstOrNull { it.code.equals(wanted, ignoreCase = true) }
            ?: tests.firstOrNull {
                it.name.contains(wanted, ignoreCase = true) || wanted.contains(it.name, ignoreCase = true)
            }
        if (match != null && match.id !in selectedTestIds) selectedTestIds.add(match.id)
        orderNotes = buildString {
            append("EMR order: ").append(row.testName)
            row.instructions?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
            if (match == null) append(" (not in catalog — pick the closest test)")
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
                        emrRow?.let { EmrBanner(it) }
                        PatientSection(patient, onPatient = { patient = it })
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
                    emrRow?.let { EmrBanner(it) }
                    PatientSection(patient, onPatient = { patient = it })
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

/** P3: the clinic's order being registered — the row has NO demographics, the
 *  walk-in patient supplies them at the desk. */
@Composable
private fun EmrBanner(row: com.bnm.diagnosis.lab.EmrInboxItem) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("EMR order · ${row.testName}", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer)
            row.instructions?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Text("Ask the patient for their details below — the clinic doesn't share demographics.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

// ─────────────────────────── Patient section ───────────────────────────

@OptIn(ExperimentalUuidApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PatientSection(patient: Patient?, onPatient: (Patient?) -> Unit) {
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
                    }
                    TextButton(onClick = { onPatient(null) }) { Text("Change") }
                }
            }
            return@Column
        }

        var query by remember { mutableStateOf("") }
        var matches by remember { mutableStateOf<List<Patient>>(emptyList()) }
        var showNewForm by remember { mutableStateOf(false) }
        LaunchedEffect(query) { matches = runCatching { repo.searchPatients(query) }.getOrDefault(emptyList()) }

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
            var name by remember { mutableStateOf(query.filter { !it.isDigit() }.trim()) }
            var sex by remember { mutableStateOf<String?>(null) }
            var ageText by remember { mutableStateOf("") }
            var dob by remember { mutableStateOf("") }
            var phone by remember { mutableStateOf(query.filter { it.isDigit() }) }
            var formError by remember { mutableStateOf<String?>(null) }

            Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("New patient", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Age & sex are required — reference ranges depend on them.",
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
