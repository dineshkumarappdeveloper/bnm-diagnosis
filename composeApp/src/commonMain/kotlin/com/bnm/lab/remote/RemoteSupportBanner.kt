package com.bnm.lab.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The strip every screen shows while a support session runs — amber, so the
 * person at the bench can always see that BNM is looking, with the code (in
 * case the engineer asks again), when it ends, how many actions so far, and
 * End. The dot goes green while the engineer is actually connected. Renders
 * nothing when no session is active.
 */
@Composable
fun RemoteSupportBanner(
    status: RemoteSupportStatus,
    onEnd: () -> Unit,
    /** Click on the text opens the dialog (live status + Support history). */
    onOpen: () -> Unit = {},
) {
    if (!status.isActive) return
    Surface(color = BannerAmber, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp).background(
                    if (status.peerConnected) DotConnected else DotWaiting,
                    CircleShape,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                RemoteSupportCopy.bannerText(status),
                color = BannerInk,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable(onClick = onOpen),
            )
            TextButton(onClick = onEnd) {
                Text("End", color = BannerInk, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// Fixed colours on purpose: amber must read as amber in both themes — it is a
// "someone else can see this computer" signal, not a decoration.
private val BannerAmber = Color(0xFFFFE08A)
private val BannerInk = Color(0xFF3F2E00)
private val DotConnected = Color(0xFF1B8A3C)
private val DotWaiting = Color(0xFF8A6D00)
