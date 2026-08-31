package com.bnm.diagnosis.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.bnm.diagnosis.api.ApiClient
import kotlinx.coroutines.launch

/**
 * Always-on "App version" panel: what is installed, a refresh to check, and — when
 * something newer exists — download + install.
 *
 * Deliberately always visible even when up to date. The point of the panel is that
 * an operator can confirm which build a lab PC is running when reporting a problem;
 * a control that only appears when there is news cannot answer that.
 *
 * It does NOT poll. Checking is an explicit act (plus one silent check per app
 * start), because the GitHub API is unauthenticated here and rate-limited per IP.
 */
@Composable
fun AppVersionPanel(
    modifier: Modifier = Modifier,
    /** Called when an installer was launched — the host should close the app. */
    onQuitForUpdate: () -> Unit = {},
    /** Injectable for tests/previews. */
    checkNow: suspend (UpdatePlatform) -> UpdateCheck = { p ->
        // Its own short-lived client: the update check talks to GitHub, not to
        // our API, so it must not inherit auth headers or a Supabase base URL.
        val c = ApiClient.create()
        try { UpdateChecker.check(c, p) } finally { c.close() }
    },
) {
    val scope = rememberCoroutineScope()
    val platform = remember { currentUpdatePlatform() }
    var state by remember { mutableStateOf<UpdateCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var installNote by remember { mutableStateOf<String?>(null) }

    fun check() {
        if (checking || downloading) return
        checking = true
        installNote = null
        scope.launch {
            state = checkNow(platform)
            checking = false
        }
    }

    // One quiet check per app start — enough to surface a fix without polling.
    LaunchedEffect(Unit) { if (platform != UpdatePlatform.STORE_MANAGED) check() }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("App version", style = MaterialTheme.typography.titleSmall)
                    Text(
                        UpdateChecker.currentVersion,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (platform != UpdatePlatform.STORE_MANAGED) {
                    if (checking) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { check() }, enabled = !downloading) {
                            Icon(Icons.Default.Refresh, contentDescription = "Check for updates")
                        }
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            when {
                platform == UpdatePlatform.STORE_MANAGED -> Text(
                    "Updates for this device come from the app store.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                downloading -> {
                    Text("Downloading update…", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    // Indeterminate when the server sends no content-length, rather
                    // than a bar frozen at 0% that looks like a hang.
                    if (progress > 0f) {
                        LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }

                else -> when (val s = state) {
                    null -> Text(
                        if (checking) "Checking for updates…" else "Tap refresh to check for updates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is UpdateCheck.UpToDate -> Text(
                        "You're on the latest version.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    is UpdateCheck.Failed -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    is UpdateCheck.Available -> Column {
                        Text(
                            "Version ${s.release.version} is available.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        s.release.sizeBytes?.let {
                            Text(
                                "Download ${it / 1_000_000} MB · installs over this version",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "The installer opens when the download finishes, and BNM " +
                                "Diagnosis closes so it can be replaced. Your lab data " +
                                "stays on this machine.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val url = s.release.downloadUrl ?: return@Button
                                downloading = true
                                progress = 0f
                                scope.launch {
                                    // Pull the published sha256 first. Null means the
                                    // release predates checksums — the installer then
                                    // says "unverified" rather than implying it checked.
                                    val sha = run {
                                        val c = ApiClient.create()
                                        try {
                                            UpdateChecker.fetchChecksum(
                                                c, s.release.checksumsUrl, platform.assetName,
                                            )
                                        } finally { c.close() }
                                    }
                                    val result = downloadAndLaunchInstaller(
                                        url = url,
                                        fileName = platform.assetName,
                                        expectedSha256 = sha,
                                        onProgress = { got, total ->
                                            progress = if (total != null && total > 0) got.toFloat() / total else 0f
                                        },
                                    )
                                    downloading = false
                                    when (result) {
                                        is UpdateInstall.LaunchedQuitNow -> onQuitForUpdate()
                                        is UpdateInstall.DownloadedOnly ->
                                            installNote = "${result.reason}\n${result.path}"
                                        is UpdateInstall.Failed -> installNote = result.message
                                        UpdateInstall.NotSupported ->
                                            installNote = "Updates come from the app store on this device."
                                    }
                                }
                            }) { Text("Download & install") }
                        }
                    }
                }
            }

            installNote?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
