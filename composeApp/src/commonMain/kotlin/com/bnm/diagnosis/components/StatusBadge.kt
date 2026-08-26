package com.bnm.diagnosis.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun StatusBadge(
    status: String,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = statusColors(status)
    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = status.replace("_", " ").replaceFirstChar { it.uppercase() },
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun statusColors(status: String): Pair<Color, Color> = when (status.lowercase()) {
    "open", "pending", "active" -> Color(0xFFE3F2FD) to Color(0xFF1565C0)
    "in_progress", "inprogress", "processing" -> Color(0xFFFFF3E0) to Color(0xFFE65100)
    "shipped" -> Color(0xFFE0F7FA) to Color(0xFF00838F)
    "resolved", "completed", "closed", "delivered" -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
    "cancelled", "canceled", "failed" -> Color(0xFFFFEBEE) to Color(0xFFC62828)
    "confirmed", "paid" -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
    // The server writes "partial" (invoices_status_check); "partially_paid" was
    // the only key here, so a part-paid bill fell through to the default.
    "partial", "partially_paid" -> Color(0xFFFFF3E0) to Color(0xFFE65100)
    "refunded" -> Color(0xFFF3E5F5) to Color(0xFF6A1B9A)
    "credit" -> Color(0xFFE8F5E9) to Color(0xFF2E7D32)
    "debit" -> Color(0xFFFFEBEE) to Color(0xFFC62828)
    "paused" -> Color(0xFFFFF8E1) to Color(0xFFF57F17)
    else -> Color(0xFFF5F5F5) to Color(0xFF616161)
}
