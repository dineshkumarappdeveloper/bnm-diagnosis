package com.bnm.lab.screens.license

import com.bnm.lab.diagnostics.AppLog
import com.bnm.lab.diagnostics.logFailure

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnm.lab.api.LabActivateResult
import com.bnm.lab.api.LabApi
import com.bnm.lab.api.LabSeatDevice
import com.bnm.lab.api.LicenseActivation
import com.bnm.lab.backup.BackupController
import com.bnm.lab.backup.BackupGeneration
import com.bnm.lab.getPlatform
import com.bnm.lab.license.LicenseManager
import com.bnm.lab.resources.Res
import com.bnm.lab.resources.bnm_logo_mark
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import com.bnm.lab.lab.LocalLabRepository
import com.bnm.lab.lab.TenantRowCounts
import com.bnm.lab.screens.backup.BackupRestoreOfferDialog
import com.bnm.lab.screens.backup.RestoreDialog

private val Brand = Color(0xFF2D7FF0)

/** Platform string for the admin-lab contract: desktop | android | ios. */
internal fun labApiPlatform(): String {
    val n = getPlatform().name
    return when {
        n.startsWith("Android", ignoreCase = true) -> "android"
        n.startsWith("iOS", ignoreCase = true) || n.startsWith("iPadOS", ignoreCase = true) -> "ios"
        else -> "desktop"
    }
}

/** Sensible default device name per platform (user-editable). */
internal fun defaultDeviceName(): String = when (labApiPlatform()) {
    "android" -> "Android device"
    "ios" -> "iOS device"
    else -> {
        // Desktop platform name looks like "JVM 17 (Windows 11)".
        val os = getPlatform().name.substringAfter('(', "").substringBefore(')').trim()
        if (os.isBlank()) "Desktop PC" else "$os desktop"
    }
}

/**
 * License activation — replaces the old billing counter-pairing entry.
 * Activate with a BNMD license key; on success the lab name is shown
 * READ-ONLY (admin-set) with a Continue button into the app.
 */
@Composable
fun ActivationScreen(
    labApi: LabApi,
    licenseManager: LicenseManager,
    onActivated: (LicenseActivation) -> Unit,
    onEnterApp: () -> Unit,
    /** Runs BEFORE a tenant-switch wipe — the host stops the analyzer
     *  listeners so no frame lands between "old lab erased" and "new lab
     *  activated" (it would re-seed the new tenant with old-lab data). */
    onBeforeTenantWipe: suspend () -> Unit = {},
    /** Runs AFTER a tenant-switch wipe — the backup vault belonged to the
     *  previous lab and is retired so the next lab sets up its own pendrive. */
    onAfterTenantWipe: suspend () -> Unit = {},
    /** Backup pendrive engine (desktop only): offers "Restore from a backup
     *  pendrive…" under Activate. Null hides it. */
    backupController: BackupController? = null,
    /** The newest backup on a bound pendrive, found at launch while this PC
     *  holds no records — asked about once, then [onRestoreOfferHandled]. */
    restoreOffer: BackupGeneration? = null,
    onRestoreOfferHandled: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(defaultDeviceName()) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var activated by remember { mutableStateOf<LicenseActivation?>(null) }
    var seatsFull by remember { mutableStateOf<LabActivateResult.SeatsFull?>(null) }
    var replaceCandidate by remember { mutableStateOf<LabSeatDevice?>(null) }
    // Set when the entered key belongs to a DIFFERENT licence and this device
    // still holds another lab's records; drives the erase-confirmation dialog.
    // Counts are nullable: a failed count must still be able to raise the dialog
    // rather than fall through to a silent decision either way.
    var pendingTenantSwitch by remember { mutableStateOf<Pair<String, TenantRowCounts?>?>(null) }
    val labRepo = LocalLabRepository.current
    // Restore from a pendrive: opened by the button under Activate, or by the
    // launch-time offer (which pre-selects the generation it found).
    var restoreFrom by remember { mutableStateOf<BackupGeneration?>(null) }
    var showRestore by remember { mutableStateOf(false) }

    fun runActivate(
        replaceDeviceId: String? = null,
        eraseConfirmed: Boolean = false,
        /** Decided ONCE in the confirmation step below and threaded through, so
         *  the "ask" and the "wipe" can never disagree about what is happening —
         *  re-deriving it here risked prompting and then not erasing, or worse. */
        wipeFirst: Boolean = false,
    ) {
        if (loading) return
        val k = key.trim()
        if (k.length < 8) {
            errorMessage = "Enter your BNM Lab license key"
            return
        }
        // A DIFFERENT licence on a device that already holds another lab's records
        // must never proceed silently: the old lab's patients would appear under
        // the new lab's name and then sync into the new tenant. Ask first — and
        // ask BEFORE the network call, so no seat is consumed on a cancel.
        if (!eraseConfirmed) {
            scope.launch {
                // Read the counts FIRST: on an install with no recorded
                // fingerprint they are what decides whether this is a tenant
                // switch at all.
                val counts = runCatching { labRepo.tenantRowCounts() }.getOrNull()
                // A count that FAILED is not evidence of an empty device. Treat
                // unknown as "there is something here" so the fallback is always
                // to ask — never to erase silently, never to adopt silently.
                val hasLocalData = counts == null || !counts.isEmpty
                when {
                    !licenseManager.isDifferentTenant(k, hasLocalData) ->
                        runActivate(replaceDeviceId, eraseConfirmed = true)
                    hasLocalData -> pendingTenantSwitch = k to counts
                    // Different licence, but nothing stored — nothing to erase.
                    else -> runActivate(replaceDeviceId, eraseConfirmed = true)
                }
            }
            return
        }

        loading = true
        errorMessage = null
        scope.launch {
            if (wipeFirst) {
                // Wipe BEFORE saving the new activation, so a crash in between
                // leaves the device unlicensed-but-clean rather than licensed to
                // the new lab while still holding the old lab's data. Analyzer
                // listeners are stopped first for the same reason.
                AppLog.w("Licence", "tenant switch confirmed — erasing this device's lab data before activating")
                runCatching { onBeforeTenantWipe() }.logFailure("Licence", "stopping analyzers before tenant wipe")
                runCatching { labRepo.resetForNewTenant() }.logFailure("Licence", "tenant wipe")
                runCatching { onAfterTenantWipe() }.logFailure("Licence", "retiring the backup vault after tenant wipe")
            }
            // The key itself is never logged — only that an attempt happened.
            AppLog.i("Licence", "activation attempt (replacing device: ${replaceDeviceId != null})")
            labApi.activate(
                key = k,
                deviceId = licenseManager.deviceId,
                deviceName = deviceName.trim().ifBlank { defaultDeviceName() },
                platform = labApiPlatform(),
                replaceDeviceId = replaceDeviceId,
            ).onSuccess { outcome ->
                loading = false
                when (outcome) {
                    is LabActivateResult.Activated -> {
                        val a = outcome.license
                        licenseManager.saveActivation(
                            licenseJwt = a.licenseJwt,
                            deviceToken = a.deviceToken,
                            deviceRowId = a.deviceRowId,
                            labName = a.labName,
                            mode = a.mode,
                            seats = a.seats,
                            expiresAt = a.expiresAt,
                            businessId = a.businessId,
                            licenseFingerprint = licenseManager.fingerprintOf(k),
                            seatNo = a.seatNo,
                        )
                        seatsFull = null
                        replaceCandidate = null
                        activated = a
                        AppLog.i("Licence", "activated: edition=${if (a.businessId == null) "standalone" else "connected"} " +
                            "mode=${a.mode} seats=${a.seats} expires=${a.expiresAt} deviceRow=${a.deviceRowId}")
                        onActivated(a)
                    }
                    is LabActivateResult.SeatsFull -> {
                        AppLog.w("Licence", "activation refused: all seats in use (${outcome.devices.size} devices)")
                        seatsFull = outcome
                        // A PC restored from a backup pendrive is registering with a
                        // fresh device id, so its own old seat is the one in the way:
                        // pre-select it (the list stays behind the confirmation, so
                        // Cancel still offers every replaceable seat).
                        restoredSeatToReplace(outcome.devices, backupController?.status?.value?.restoredFromDeviceRowId)?.let {
                            AppLog.i("Licence", "seats full after a restore — offering the previous computer's seat")
                            replaceCandidate = it
                        }
                    }
                    is LabActivateResult.ReplaceCooldown -> {
                        AppLog.w("Licence", "activation refused: replace cooldown — ${outcome.message}")
                        seatsFull = null
                        errorMessage = outcome.message
                    }
                }
            }.onFailure {
                AppLog.w("Licence", "activation failed", it)
                loading = false
                errorMessage = it.message ?: "Activation failed — check your connection"
            }
        }
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cardColor = if (isDark) Color(0xFF14171C) else Color.White
    val cardBorder = if (isDark) Color(0xFF262A31) else Color(0xFFEAECF1)
    val titleColor = MaterialTheme.colorScheme.onSurface
    val subtleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val backdrop = Brush.verticalGradient(
        if (isDark) listOf(Color(0xFF0A0C0F), Color(0xFF0D1117))
        else listOf(Color(0xFFF6F8FC), Color(0xFFE9F0FA))
    )
    val glow = if (isDark) Brand.copy(alpha = 0.20f) else Color(0xFF2D7FF0).copy(alpha = 0.14f)

    Box(modifier = Modifier.fillMaxSize().background(backdrop), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.matchParentSize().background(
                Brush.radialGradient(colors = listOf(glow, Color.Transparent), radius = 1100f)
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = cardColor,
                border = BorderStroke(1.dp, cardBorder),
                shadowElevation = if (isDark) 0.dp else 18.dp,
                modifier = Modifier.fillMaxWidth().widthIn(max = 440.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(Res.drawable.bnm_logo_mark),
                        contentDescription = "BNM",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.width(150.dp).height(54.dp)
                    )
                    Spacer(Modifier.height(24.dp))

                    val done = activated
                    if (done == null) {
                        Text("Activate BNM Lab", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = titleColor)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Enter the license key issued for your lab. One key covers all its allowed devices (seats).",
                            fontSize = 15.sp, color = subtleColor, textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(28.dp))

                        errorMessage?.let {
                            ErrorBanner(it)
                            Spacer(Modifier.height(16.dp))
                        }

                        OutlinedTextField(
                            value = key,
                            onValueChange = { input ->
                                key = input.uppercase().filter { it.isLetterOrDigit() || it == '-' }.take(32)
                            },
                            enabled = !loading,
                            singleLine = true,
                            label = { Text("License key") },
                            placeholder = { Text("BNMD-XXXX-XXXX-XXXX-XXXX") },
                            leadingIcon = { Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Brand, focusedLabelColor = Brand, cursorColor = Brand
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { deviceName = it.take(48) },
                            enabled = !loading,
                            singleLine = true,
                            label = { Text("Device name") },
                            leadingIcon = { Icon(Icons.Outlined.Computer, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { runActivate() }),
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Brand, focusedLabelColor = Brand, cursorColor = Brand
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(18.dp))
                        PrimaryButton(
                            label = "Activate",
                            loading = loading,
                            enabled = !loading && key.trim().length >= 8,
                            onClick = { runActivate() },
                        )
                        if (backupController != null) {
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { showRestore = true }, enabled = !loading) {
                                Text("Restore from a backup pendrive…", color = Brand)
                            }
                        }
                    } else {
                        // ── Activated: lab name is admin-set and READ-ONLY ──
                        Icon(Icons.Outlined.Verified, contentDescription = null, tint = Brand, modifier = Modifier.size(44.dp))
                        Spacer(Modifier.height(14.dp))
                        Text("Licensed to:", fontSize = 14.sp, color = subtleColor)
                        Spacer(Modifier.height(4.dp))
                        Text(done.labName, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = titleColor, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModeChip(done.mode)
                            Text(
                                "${done.seats} device${if (done.seats == 1) "" else "s"}",
                                fontSize = 13.sp, color = subtleColor
                            )
                        }
                        if (done.mode == LicenseManager.MODE_SUBSCRIPTION && !done.expiresAt.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Valid until ${done.expiresAt.take(10)}", fontSize = 13.sp, color = subtleColor)
                        }
                        Spacer(Modifier.height(24.dp))
                        PrimaryButton(label = "Continue", loading = false, enabled = true, onClick = onEnterApp)
                    }

                    Spacer(Modifier.height(22.dp))
                    HorizontalDivider(color = cardBorder)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Badge, contentDescription = null, tint = subtleColor.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "The lab name is set by BNM and can't be changed in the app",
                            fontSize = 12.sp, color = subtleColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }

    // ── Seats full: pick a replaceable device to take over ───────────────────
    seatsFull?.let { sf ->
        AlertDialog(
            onDismissRequest = { if (!loading) seatsFull = null },
            title = { Text("All ${sf.seats} seats are in use") },
            text = {
                Column {
                    Text(
                        "This license allows ${sf.seats} device${if (sf.seats == 1) "" else "s"}. " +
                            "Replace a device that hasn't been used recently, or deactivate one from an active device.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(sf.devices, key = { it.id }) { d ->
                            SeatRow(
                                device = d,
                                enabled = d.replaceable && !loading,
                                onClick = { replaceCandidate = d },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { if (!loading) seatsFull = null }) { Text("Cancel") } },
        )
    }

    // ── Confirm the takeover ─────────────────────────────────────────────────
    replaceCandidate?.let { d ->
        AlertDialog(
            onDismissRequest = { if (!loading) replaceCandidate = null },
            title = { Text("Replace ${d.deviceName ?: "this device"}?") },
            text = { Text("Its access will be revoked.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = d.id
                    replaceCandidate = null
                    runActivate(replaceDeviceId = id)
                }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { if (!loading) replaceCandidate = null }) { Text("Cancel") } },
        )
    }

    // ── Different licence on a device that already holds a lab's records ──────
    // Deliberately a hard confirmation, not a silent wipe: for a PERPETUAL licence
    // the local database is the system of record and there may be no server copy
    // at all, so erasing it is unrecoverable. The counts are shown so the operator
    // can see exactly what they are about to destroy.
    pendingTenantSwitch?.let { (pendingKey, counts) ->
        AlertDialog(
            onDismissRequest = { if (!loading) pendingTenantSwitch = null },
            title = { Text("This device already holds another lab's records") },
            text = {
                Column {
                    Text(
                        "${licenseManager.state.value.labName ?: "The current lab"} has " +
                            "${counts?.summary ?: "records"} stored on this device.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Activating a different licence will PERMANENTLY ERASE all of it — " +
                            "patients, orders, results, staff, the test catalog, numbering and " +
                            "any unsent bills. This cannot be undone, and for a perpetual " +
                            "licence there may be no copy anywhere else.",
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "If these records still matter, cancel and export them first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        pendingTenantSwitch = null
                        key = pendingKey
                        runActivate(eraseConfirmed = true, wipeFirst = true)
                    },
                ) { Text("Erase and activate", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { if (!loading) pendingTenantSwitch = null }) { Text("Cancel") }
            },
        )
    }

    // ── Restore from a backup pendrive ────────────────────────────────────────
    // A new (or reinstalled) PC: the records come from the pendrive, the
    // licence travels inside the backup, and the next launch swaps them in.
    if (backupController != null) {
        restoreOffer?.let { gen ->
            BackupRestoreOfferDialog(
                gen = gen,
                onRestore = { onRestoreOfferHandled(); restoreFrom = gen; showRestore = true },
                onNotNow = onRestoreOfferHandled,
            )
        }
        if (showRestore) RestoreDialog(
            controller = backupController,
            activated = licenseManager.state.value.activated,
            initialGeneration = restoreFrom,
            onDismiss = { showRestore = false; restoreFrom = null },
        )
    }
}

@Composable
private fun SeatRow(device: LabSeatDevice, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    device.deviceName ?: "Unnamed device",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.55f),
                )
                Text(
                    listOfNotNull(device.platform, device.lastSeen?.let { "last seen ${it.take(10)}" })
                        .joinToString(" · ").ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (device.replaceable) {
                Surface(shape = RoundedCornerShape(50), color = Brand.copy(alpha = 0.12f)) {
                    Text(
                        "replaceable",
                        color = Brand,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            } else {
                Text(
                    "active recently",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ModeChip(mode: String?) {
    val label = when (mode) {
        LicenseManager.MODE_PERPETUAL -> "Perpetual"
        LicenseManager.MODE_SUBSCRIPTION -> "Subscription"
        else -> mode ?: "—"
    }
    Surface(shape = RoundedCornerShape(50), color = Brand.copy(alpha = 0.12f)) {
        Text(
            label,
            color = Brand,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PrimaryButton(label: String, loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = if (enabled) Brand else Brand.copy(alpha = 0.5f),
        contentColor = Color.White,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }
}

/**
 * After a restore from a backup pendrive this PC registers under a FRESH
 * device id, so when BNM answers "all seats in use" the seat in the way is
 * usually the old computer's own. The backup carried that seat's row id; when
 * the server lists it, that is the one to offer first. Whether the seat may be
 * taken over now is the server's call (`replace_cooldown`), not the list's
 * `replaceable` hint — a dead PC's last heartbeat can be recent.
 */
internal fun restoredSeatToReplace(devices: List<LabSeatDevice>, restoredFromRowId: String?): LabSeatDevice? {
    val id = restoredFromRowId?.takeIf { it.isNotBlank() } ?: return null
    return devices.firstOrNull { it.id == id }
}
