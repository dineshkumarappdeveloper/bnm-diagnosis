package com.bnm.diagnosis.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnm.diagnosis.chat.SyncEngine

/**
 * Snackbar-style banner shown ONCE during a business's first full sync. App-level
 * (above every screen) so the first-time setup progress is shown regardless of
 * which page is open — replaces the old per-page "first sync" full-screen states.
 */
@Composable
fun SyncProgressBanner(progress: SyncEngine.Progress?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Setting up your workspace",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        progress?.let { "${it.label} · ${it.done}/${it.total}" } ?: "Syncing your data…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.72f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            val frac = progress?.let { if (it.total > 0) it.done.toFloat() / it.total else 0f } ?: 0f
            val animated by animateFloatAsState(frac, label = "syncFrac")
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }
    }
}
