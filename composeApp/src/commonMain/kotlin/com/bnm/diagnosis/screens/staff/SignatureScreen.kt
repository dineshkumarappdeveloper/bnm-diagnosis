package com.bnm.diagnosis.screens.staff

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.report.SignatureInk
import com.bnm.diagnosis.staff.LocalStaffRepository
import com.bnm.diagnosis.staff.LocalStaffSession
import com.bnm.diagnosis.staff.Staff
import kotlinx.coroutines.launch

/**
 * Approver signature capture — round-1 feedback item 5, "digital signature for
 * the approver side".
 *
 * What it produces is one base64 PNG in `staff.signature_png`, plus the two
 * text fields an Indian lab report is expected to carry next to a pathologist's
 * name: qualifications ("MD (Pathology)") and the state medical-council
 * registration number. All three ride the normal staff row, so a signature set
 * at the front desk shows up on the pathologist's laptop after the next sync —
 * which is exactly why the image is base64 in a column and not a file path.
 *
 * FULLY OFFLINE, like every other staff surface: drawing, encoding and saving
 * are local, and nothing here waits on a network.
 */

/** One drawn stroke, in NORMALISED pad coordinates (0..1 on both axes) — see
 *  [SignatureInk]. Storing them normalised is what makes a signature drawn on a
 *  phone and one drawn on a 27" monitor export to the same image. */
private typealias Stroke = List<Pair<Float, Float>>

/**
 * "My signature" — the self-service surface. A pathologist is not an owner and
 * so cannot reach Staff & roles, but they are exactly the person whose
 * signature this is, so they edit their own row here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MySignatureScreen(onBack: () -> Unit) {
    val repo = LocalStaffRepository.current
    val session = LocalStaffSession.current
    val me by session.current.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My signature") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        val person = me
        if (person == null) {
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sign in first.", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(inner).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Your signature is printed above \"Approved by (Pathologist)\" on every " +
                    "report you approve, together with the approval date. Leave it empty and " +
                    "reports print your name only, exactly as before.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                signatureSummary(person),
                style = MaterialTheme.typography.labelLarge,
            )
            Button(onClick = { editing = true }) {
                Text(if (person.hasSignature) "Change signature" else "Add signature")
            }
            message?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }

        if (editing) {
            SignatureDialog(
                person = person,
                onDismiss = { editing = false },
                onSave = { png, quals, reg ->
                    scope.launch {
                        // Re-read before writing: the session's copy can be minutes
                        // old, and saving it back would restore whatever the row
                        // held then — including a password another seat has since
                        // changed.
                        val fresh = repo.byId(person.id) ?: person
                        repo.save(fresh.copy(signaturePng = png, qualifications = quals, registrationNo = reg))
                            .onSuccess { saved ->
                                session.refresh(saved)
                                message = if (png == null) "Signature removed" else "Signature saved"
                            }
                            .onFailure { message = it.message }
                        editing = false
                    }
                },
            )
        }
    }
}

/** One-line "what is on file", shared by this screen and the staff list. */
internal fun signatureSummary(person: Staff): String {
    val bits = ArrayList<String>(3)
    bits += if (person.hasSignature) "Signature on file" else "No signature on file"
    person.qualifications?.takeIf { it.isNotBlank() }?.let { bits += it }
    person.registrationNo?.takeIf { it.isNotBlank() }?.let { bits += "Reg. No. $it" }
    return bits.joinToString(" · ")
}

/**
 * The capture dialog: a pad to sign on, plus the two credential fields.
 *
 * [onSave] receives the base64 PNG (null = remove the signature) and the two
 * trimmed text fields. Re-opening starts with an EMPTY pad rather than the
 * stored image — the pad edits strokes, and there is no way back from a
 * rasterised PNG to the strokes that drew it. Saving with nothing drawn
 * therefore keeps the existing image, and there is an explicit Remove for the
 * case where the approver really wants it gone.
 */
@Composable
fun SignatureDialog(
    person: Staff,
    onDismiss: () -> Unit,
    onSave: (signaturePngBase64: String?, qualifications: String?, registrationNo: String?) -> Unit,
) {
    var strokes by remember { mutableStateOf<List<Stroke>>(emptyList()) }
    var current by remember { mutableStateOf<Stroke>(emptyList()) }
    var qualifications by remember { mutableStateOf(person.qualifications.orEmpty()) }
    var registrationNo by remember { mutableStateOf(person.registrationNo.orEmpty()) }
    var removeExisting by remember { mutableStateOf(false) }

    val drewSomething = strokes.isNotEmpty() || current.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signature — ${person.name}") },
        text = {
            Column(
                Modifier.widthIn(max = 520.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Sign inside the box with a mouse, a stylus or a finger.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                SignaturePad(
                    strokes = strokes,
                    current = current,
                    onStrokeStart = { current = listOf(it) },
                    onStrokePoint = { current = current + it },
                    onStrokeEnd = {
                        if (current.isNotEmpty()) strokes = strokes + listOf(current)
                        current = emptyList()
                        removeExisting = false // drawing is the opposite of removing
                    },
                    onStrokeCancel = { current = emptyList() },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (strokes.isNotEmpty()) strokes = strokes.dropLast(1) },
                        enabled = strokes.isNotEmpty(),
                    ) { Text("Undo") }
                    OutlinedButton(
                        onClick = { strokes = emptyList(); current = emptyList() },
                        enabled = drewSomething,
                    ) { Text("Clear") }
                    if (person.hasSignature && !drewSomething) {
                        TextButton(onClick = { removeExisting = !removeExisting }) {
                            Text(if (removeExisting) "Keep current signature" else "Remove signature")
                        }
                    }
                }

                if (person.hasSignature && !drewSomething) {
                    Text(
                        if (removeExisting) "The stored signature will be deleted when you save."
                        else "A signature is already on file. Draw a new one to replace it, or " +
                            "save without drawing to keep it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (removeExisting) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = qualifications,
                    onValueChange = { qualifications = it },
                    label = { Text("Qualifications") },
                    placeholder = { Text("MD (Pathology)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = registrationNo,
                    onValueChange = { registrationNo = it },
                    label = { Text("Medical council registration no.") },
                    supportingText = {
                        Text("Printed under the signature — an Indian lab report is expected to carry it.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                // Three cases, in order: drew something → new image; asked to
                // remove → null; neither → keep whatever is already stored.
                val png = when {
                    drewSomething -> SignatureInk.toPngBase64(
                        if (current.isEmpty()) strokes else strokes + listOf(current)
                    )
                    removeExisting -> null
                    else -> person.signaturePng
                }
                onSave(
                    png,
                    qualifications.trim().ifBlank { null },
                    registrationNo.trim().ifBlank { null },
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The pad itself. Reports every point in NORMALISED coordinates so the export
 * does not depend on how big the dialog happened to be — see [Stroke].
 *
 * 3.2:1, matching [SignatureInk.EXPORT_W] / [SignatureInk.EXPORT_H], so what
 * the approver draws is what the report prints, undistorted.
 */
@Composable
private fun SignaturePad(
    strokes: List<Stroke>,
    current: Stroke,
    onStrokeStart: (Pair<Float, Float>) -> Unit,
    onStrokePoint: (Pair<Float, Float>) -> Unit,
    onStrokeEnd: () -> Unit,
    onStrokeCancel: () -> Unit,
) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(SignatureInk.EXPORT_W.toFloat() / SignatureInk.EXPORT_H)
            .background(Color.White, RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                // Guard against a zero-size layout pass: dividing by it would
                // put NaN into the stroke list and blank the whole signature.
                fun norm(p: Offset): Pair<Float, Float> = Pair(
                    if (size.width > 0) p.x / size.width else 0f,
                    if (size.height > 0) p.y / size.height else 0f,
                )
                detectDragGestures(
                    onDragStart = { onStrokeStart(norm(it)) },
                    onDragEnd = { onStrokeEnd() },
                    onDragCancel = { onStrokeCancel() },
                    onDrag = { change, _ ->
                        change.consume()
                        onStrokePoint(norm(change.position))
                    },
                )
            }
    ) {
        val all = if (current.isEmpty()) strokes else strokes + listOf(current)
        for (stroke in all) {
            for (i in 1 until stroke.size) {
                drawLine(
                    color = Color.Black,
                    start = Offset(stroke[i - 1].first * size.width, stroke[i - 1].second * size.height),
                    end = Offset(stroke[i].first * size.width, stroke[i].second * size.height),
                    strokeWidth = size.height / (SignatureInk.EXPORT_H / 10f),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
