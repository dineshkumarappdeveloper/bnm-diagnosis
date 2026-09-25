package com.bnm.lab.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnm.lab.instruments.CreateOrderContext
import com.bnm.lab.instruments.CreateOrderDraft
import com.bnm.lab.instruments.CreateOrderGate
import com.bnm.lab.instruments.CreateOrderRefusal
import com.bnm.lab.instruments.CreatedFromResult
import com.bnm.lab.instruments.InstrumentEngine
import com.bnm.lab.instruments.PRIORITY_ROUTINE
import com.bnm.lab.instruments.PRIORITY_URGENT
import com.bnm.lab.instruments.PatientMatch
import com.bnm.lab.instruments.QueuedFrame
import com.bnm.lab.instruments.TestFit
import com.bnm.lab.instruments.ageSexLabel
import com.bnm.lab.instruments.bestFit
import com.bnm.lab.instruments.filterForPick
import com.bnm.lab.instruments.validateCreateOrder
import com.bnm.lab.license.LicenseState
import com.bnm.lab.print.AnalyzerWorksheet
import com.bnm.lab.staff.Staff
import kotlinx.coroutines.launch

/**
 * "Create order from this result" — the claim queue's third way out.
 *
 * The queue's other two doors move data the lab already holds. This one STARTS
 * work: it registers the patient and the order the bench forgot, then hands the
 * run straight to [InstrumentEngine.claimUnmatched] so the numbers land exactly
 * as a bench entry would. Everything it needs to decide lives in
 * `instruments/CreateOrderFromResult.kt`; this file draws it.
 *
 * Four stages, one at a time, because each is a different question: refused /
 * the form / "is this the same person?" / what happened. The stage-specific
 * buttons live in [CreateOrderFromResultBody] rather than in the dialog's
 * confirm slot — a wizard's actions change with the step, and keeping them in
 * the body is also what lets a render test draw any stage whole.
 */
@Composable
internal fun CreateOrderFromResultDialog(
    engine: InstrumentEngine,
    queued: QueuedFrame,
    instrumentName: String,
    /** The accession series this computer issues from — shown, so the promise is concrete. */
    accessionSeries: String,
    who: Staff?,
    licence: LicenseState,
    /** Dismissed, whatever the stage. The caller re-reads the queue from its flow. */
    onDismiss: () -> Unit,
    /** "Create bill now": open the order, where the existing Create bill lives. */
    onOpenOrder: (orderId: String) -> Unit,
    /** Shown on the Instruments screen after the dialog closes. */
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Decided ONCE, as LicenceGatedRoute does it: the app re-reads the term every
    // minute, and a lapse landing mid-form must not throw away what was typed.
    val refusal = remember(queued.id) { CreateOrderGate.refusal(who, licence) }

    var context by remember(queued.id) { mutableStateOf<CreateOrderContext?>(null) }
    var draft by remember(queued.id) { mutableStateOf(CreateOrderDraft()) }
    var testQuery by remember(queued.id) { mutableStateOf("") }
    var duplicates by remember(queued.id) { mutableStateOf<List<PatientMatch>?>(null) }
    var created by remember(queued.id) { mutableStateOf<CreatedFromResult?>(null) }
    var busy by remember(queued.id) { mutableStateOf(false) }
    var error by remember(queued.id) { mutableStateOf<String?>(null) }

    // Rank the catalog once. Not on a refused seat: nothing here is going to be
    // registered, and ranking 223 tests to draw a refusal is work for nobody.
    LaunchedEffect(queued.id, refusal) {
        if (refusal != null) return@LaunchedEffect
        engine.createOrderContext(queued.id)
            .onSuccess { ctx ->
                context = ctx
                // Pre-select the best fit; the operator can change it. Null when
                // nothing in the catalog takes a single parameter — the form then
                // says so instead of pre-selecting a test that would be refused.
                draft = draft.copy(testId = ctx.fits.bestFit()?.test?.id)
            }
            .onFailure { error = it.message ?: "Could not read the catalog" }
    }

    /** Write it: patient (or the one we were told to reuse) → order → claim. */
    fun create(useDraft: CreateOrderDraft) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            engine.createOrderForResult(queued.id, useDraft, by = who, licence = licence)
                .onSuccess { outcome ->
                    created = outcome
                    draft = useDraft
                    onMessage(
                        if (outcome.resultsLanded) "Order ${outcome.order.accessionNo} registered — ${outcome.claimSummary}"
                        else "Order ${outcome.order.accessionNo} registered, but the result did not land: ${outcome.claimError}"
                    )
                }
                .onFailure { error = it.message ?: "Could not create the order" }
            busy = false
        }
    }

    /** The Create button: ask the duplicate question first, and only once. */
    fun submit() {
        val ctx = context ?: return
        if (busy) return
        validateCreateOrder(draft, ctx.fits, ctx.frameParams)?.let { error = it; return }
        error = null
        if (draft.reusingPatient) { create(draft); return }
        busy = true
        scope.launch {
            val matches = runCatching { engine.duplicatePatients(draft.name, draft.phoneClean) }
                .getOrDefault(emptyList())
            busy = false
            if (matches.isEmpty()) create(draft) else duplicates = matches
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (created != null) "Order created" else "Create order from this result") },
        text = {
            CreateOrderFromResultBody(
                queued = queued,
                instrumentName = instrumentName,
                accessionSeries = accessionSeries,
                refusal = refusal,
                context = context,
                draft = draft,
                onDraft = { draft = it },
                testQuery = testQuery,
                onTestQuery = { testQuery = it },
                duplicates = duplicates,
                onUseExisting = { m ->
                    duplicates = null
                    create(draft.copy(reusePatientId = m.patient.id))
                },
                onRegisterAnyway = { duplicates = null; create(draft) },
                onBackFromDuplicates = { duplicates = null },
                created = created,
                busy = busy,
                error = error,
                onSubmit = ::submit,
                onCancel = { if (!busy) onDismiss() },
                onBill = { order -> onDismiss(); onOpenOrder(order) },
                onDone = onDismiss,
            )
        },
        // Every action belongs to a stage and lives in the body above.
        confirmButton = {},
    )
}

/**
 * The dialog's content, fully controlled — no engine, no database, no coroutine.
 *
 * One stage is drawn at a time, in the order they can happen: refused, then what
 * happened (once it has), then the duplicate question, then the form.
 */
@Composable
internal fun CreateOrderFromResultBody(
    queued: QueuedFrame,
    instrumentName: String,
    accessionSeries: String,
    refusal: CreateOrderRefusal?,
    /** Null while the catalog is being ranked. */
    context: CreateOrderContext?,
    draft: CreateOrderDraft,
    onDraft: (CreateOrderDraft) -> Unit,
    testQuery: String,
    onTestQuery: (String) -> Unit,
    /** Non-null = the duplicate guard has something to ask. */
    duplicates: List<PatientMatch>?,
    onUseExisting: (PatientMatch) -> Unit,
    onRegisterAnyway: () -> Unit,
    onBackFromDuplicates: () -> Unit,
    created: CreatedFromResult?,
    busy: Boolean,
    error: String?,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onBill: (orderId: String) -> Unit,
    onDone: () -> Unit,
) {
    // Bounded height with the ACTIONS OUTSIDE the scroll. A registration form is
    // taller than a dialog on a laptop, and a primary button that has to be
    // scrolled to is a button the bench does not find.
    Column(
        Modifier.widthIn(max = 560.dp).heightIn(max = DIALOG_MAX_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Specimen ${queued.specimenId ?: AnalyzerWorksheet.NO_SPECIMEN} · " +
                "${queued.paramCount} parameters from $instrumentName.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                refusal != null -> RefusalPane(refusal)
                created != null -> CreatedPane(created)
                duplicates != null -> DuplicatePane(duplicates, draft, onUseExisting)
                context == null && error == null ->
                    Text("Reading the catalog…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                context == null -> Unit    // the read failed; the error line below says so
                else -> FormPane(context, draft, onDraft, testQuery, onTestQuery, accessionSeries, busy)
            }
        }

        error?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }

        // One action row, whose two choices are the stage's own.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            when {
                refusal != null -> {
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Close") }
                }
                created != null -> {
                    TextButton(onClick = onDone) { Text("Bill later") }
                    Box(Modifier.weight(1f))
                    Button(onClick = { onBill(created.order.id) }) { Text("Create bill now") }
                }
                duplicates != null -> {
                    TextButton(onClick = onBackFromDuplicates) { Text("Back") }
                    Box(Modifier.weight(1f))
                    TextButton(onClick = onRegisterAnyway) { Text("Register a new patient anyway") }
                }
                else -> {
                    TextButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
                    Box(Modifier.weight(1f))
                    Button(onClick = onSubmit, enabled = !busy && context != null && !context.nothingFits) {
                        if (busy) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp), strokeWidth = 2.dp)
                        Text(if (busy) "Registering…" else "Create order")
                    }
                }
            }
        }
    }
}

/** Why this seat may not — the licence's own words, not a greyed-out button. */
@Composable
private fun RefusalPane(refusal: CreateOrderRefusal) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(refusal.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer)
            Text(refusal.detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** Patient, test, referrer, priority — the desk's fields and no more. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormPane(
    context: CreateOrderContext,
    draft: CreateOrderDraft,
    onDraft: (CreateOrderDraft) -> Unit,
    testQuery: String,
    onTestQuery: (String) -> Unit,
    accessionSeries: String,
    busy: Boolean,
) {
    // Said before the form, not after it: the operator keyed that specimen text
    // on the analyzer and would otherwise reasonably expect it on the barcode.
    Text(
        "Specimen ${queuedSpecimenLabel(context)} is kept on the order as the sample reference. " +
            "The accession is issued by this computer ($accessionSeries…).",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Patient", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text(
        "Age & sex are required — reference ranges depend on them.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = draft.patientName, onValueChange = { onDraft(draft.copy(patientName = it)) },
        label = { Text("Full name *") }, singleLine = true, enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("M" to "Male", "F" to "Female", "O" to "Other").forEach { (code, label) ->
            FilterChip(selected = draft.sex == code, enabled = !busy,
                onClick = { onDraft(draft.copy(sex = code)) }, label = { Text(label) })
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.ageText,
            onValueChange = { onDraft(draft.copy(ageText = it.filter { c -> c.isDigit() }.take(3) )) },
            label = { Text("Age (years) *") }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = draft.dob, onValueChange = { onDraft(draft.copy(dob = it)) },
            label = { Text("or DOB (YYYY-MM-DD)") }, singleLine = true, enabled = !busy,
            modifier = Modifier.weight(1.3f),
        )
    }
    OutlinedTextField(
        value = draft.phone, onValueChange = { onDraft(draft.copy(phone = it)) },
        label = { Text("Phone") }, singleLine = true, enabled = !busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    // The pre-selection is the headline, not a list to work through: it is
    // already decided, and what the operator has to do is glance at it. The
    // picker opens on "Change" — or straight away when nothing was chosen,
    // because then there is a decision to make.
    var picking by remember(context) { mutableStateOf(context.fits.bestFit() == null) }
    val selected = context.fits.firstOrNull { it.test.id == draft.testId }
    if (context.nothingFits) {
        // Nothing in the catalog takes these parameters. Usually the analyzer's
        // param map, not the catalog — say what to look at rather than leave the
        // operator picking tests until one sticks.
        Text(
            "No test in the catalog takes any of the ${context.frameParams} parameters " +
                "${context.instrumentName} sent. Check the analyzer's parameter map in its settings, " +
                "or print the worksheet for the bench.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
    } else if (!picking && selected != null) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(selected.test.name, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(selected.note, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { picking = true }, enabled = !busy) { Text("Change") }
            }
        }
    } else {
        val shown = remember(context.fits, testQuery) {
            context.fits.filterForPick(testQuery).let { list ->
                // Unsearched, only the tests this run actually fills are worth
                // offering; a search is the operator naming one, so everything
                // they reached is shown — including the ones that take nothing,
                // which say so rather than appearing to be missing.
                if (testQuery.isBlank()) list.filter { !it.fitsNothing } else list
            }.take(TEST_ROWS)
        }
        OutlinedTextField(
            value = testQuery, onValueChange = onTestQuery,
            label = { Text("Search the catalog") }, singleLine = true, enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column {
                shown.forEachIndexed { i, fit ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TestFitRow(
                        fit = fit, selected = draft.testId == fit.test.id, enabled = !busy,
                        onClick = { onDraft(draft.copy(testId = fit.test.id)); picking = false },
                    )
                }
            }
        }
        if (shown.isEmpty()) {
            Text("Nothing matches “${testQuery.trim()}”.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ReferrerPicker(context.referrers.map { it.id to it.name }, draft.referrerId, enabled = !busy) {
            onDraft(draft.copy(referrerId = it))
        }
        FilterChip(selected = draft.priority == PRIORITY_ROUTINE, enabled = !busy,
            onClick = { onDraft(draft.copy(priority = PRIORITY_ROUTINE)) }, label = { Text("Routine") })
        FilterChip(selected = draft.priority == PRIORITY_URGENT, enabled = !busy,
            onClick = { onDraft(draft.copy(priority = PRIORITY_URGENT)) }, label = { Text("Urgent") })
    }

}

/** How many ranked tests the picker shows before the operator has to search. */
private const val TEST_ROWS = 8

/**
 * The tallest this dialog gets before its middle scrolls. Sized so the whole
 * registration — patient, the pre-selected test and the referrer/priority line
 * — is on screen at once on a laptop, with the action row pinned below it so
 * the primary button is reachable at any height.
 */
private val DIALOG_MAX_HEIGHT = 620.dp

private fun queuedSpecimenLabel(context: CreateOrderContext): String =
    context.frame.specimenId?.takeIf { it.isNotBlank() } ?: AnalyzerWorksheet.NO_SPECIMEN

/** One offered test: what it is, and how much of this run it would take. */
@Composable
private fun TestFitRow(fit: TestFit, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(fit.test.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(fit.test.code, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                fit.note, style = MaterialTheme.typography.labelMedium,
                color = if (fit.fitsNothing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Referring doctor, optional — the desk's own dropdown, minus the quick-add.
 * Inline rather than a labelled section: it shares a line with the priority
 * chips so that the form, the pre-selected test and the Create button all fit
 * a dialog without scrolling.
 */
@Composable
private fun ReferrerPicker(
    referrers: List<Pair<String, String>>,
    selectedId: String?,
    enabled: Boolean,
    onPick: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) {
            Text("Ref: " + (referrers.firstOrNull { it.first == selectedId }?.second ?: "Self / walk-in"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Self / walk-in") }, onClick = { onPick(null); open = false })
            referrers.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onPick(id); open = false })
            }
        }
    }
}

/**
 * "Is this the same person?" — asked before a second record is written, never
 * after.
 *
 * The recent orders are the point: a name and a phone look the same on two rows,
 * and what tells an operator they already know this patient is seeing that the
 * lab ran their haemogram last Tuesday.
 */
@Composable
private fun DuplicatePane(
    matches: List<PatientMatch>,
    draft: CreateOrderDraft,
    onUseExisting: (PatientMatch) -> Unit,
) {
    Text(
        if (matches.size == 1) "This patient is already on file."
        else "${matches.size} patients on file have this name and phone.",
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
    )
    Text(
        "“${draft.name}” on ${draft.phoneClean ?: "no phone"} matches a record the lab already has. " +
            "Use it and this run is registered as a NEW order for that patient — nothing of theirs is overwritten.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column {
            matches.forEachIndexed { i, m ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.fillMaxWidth().clickable { onUseExisting(m) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(m.patient.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        ageSexLabel(m.patient.dob, m.patient.ageYears, m.patient.sex) +
                            (m.patient.phone?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") +
                            " · ${m.summary}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    m.recentOrders.forEach { o ->
                        Text(
                            "${o.accessionNo} · ${o.status.replace('_', ' ')} · ${niceTime(o.createdAt)}",
                            style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Use this patient", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** What happened, including the part about money. */
@Composable
private fun CreatedPane(created: CreatedFromResult) {
    Text(
        "${created.order.accessionNo} · ${created.patient.name} · ${created.testName}",
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
    )
    Text(
        if (created.reusedPatient) "Registered against the patient already on file."
        else "A new patient record was created.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (created.resultsLanded) {
        Text(created.claimSummary.orEmpty(), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    } else {
        // The order is real and the run is still in the queue — say both, or the
        // operator registers a second order for the same tube.
        Text(
            "The order was registered, but the result did not land: ${created.claimError}. " +
                "It is still in the queue — assign it to ${created.order.accessionNo} from there.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
        )
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
        Text(
            created.billingNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(10.dp),
        )
    }
    Text(
        "Create bill now opens this order, where Create bill raises the GST bill through the lab's own billing.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
