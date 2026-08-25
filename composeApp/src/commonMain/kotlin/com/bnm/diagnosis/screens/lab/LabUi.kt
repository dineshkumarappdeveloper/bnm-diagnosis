package com.bnm.diagnosis.screens.lab

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
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.screens.license.defaultDeviceName
import com.russhwolf.settings.Settings
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Persisted device-local LIMS UI preferences (same Settings store as
 *  BillingPrefs/DiagnosisPrefs — new keys, no overlap). */
class LimsPrefs {
    private val s: Settings = Settings()

    /** Stamped as `entered_by`/`verified_by` on results (technician station). */
    var deviceName: String
        get() = s.getString(K_DEVICE_NAME, "").ifBlank { defaultDeviceName() }
        set(v) { s.putString(K_DEVICE_NAME, v.trim()) }

    /** Default pathologist name for Approve — asked once, then persisted. */
    var approvedBy: String
        get() = s.getString(K_APPROVED_BY, "")
        set(v) { s.putString(K_APPROVED_BY, v.trim()) }

    private companion object {
        const val K_DEVICE_NAME = "lims_device_name"
        const val K_APPROVED_BY = "lims_approved_by"
    }
}

/** "45y / M", "8mo / F", "— / O" from the patient's dob/age fallback. */
fun ageSexLabel(dob: String?, ageYears: Long?, sex: String): String {
    val age = LabRepository.resolveAgeYears(dob, ageYears)
    val a = when {
        age == null -> "—"
        age < 1.0 -> "${(age * 12).toInt().coerceAtLeast(0)}mo"
        else -> "${age.toInt()}y"
    }
    return "$a / ${sex.uppercase()}"
}

/** ISO instant → short local time label: "09:41" today, else "21 Aug 09:41". */
fun shortTimeLabel(iso: String): String {
    val tz = TimeZone.currentSystemDefault()
    val dt = runCatching { kotlin.time.Instant.parse(iso).toLocalDateTime(tz) }.getOrNull()
        ?: return iso.take(10)
    val today = kotlin.time.Clock.System.now().toLocalDateTime(tz).date
    val hm = "${dt.hour.toString().padStart(2, '0')}:${dt.minute.toString().padStart(2, '0')}"
    if (dt.date == today) return hm
    val mon = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return "${dt.date.day} ${mon[dt.date.month.ordinal]} $hm"
}

/** Result flag chip: N = neutral, L/H/A = amber, CL/CH = red. Null flag → nothing. */
@Composable
fun FlagChip(flag: String?, modifier: Modifier = Modifier) {
    if (flag.isNullOrBlank()) return
    val (bg, fg) = when (flag) {
        "N" -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        "L", "H", "A" -> Color(0xFFFFF3E0) to Color(0xFFE65100)      // amber
        "CL", "CH" -> Color(0xFFFFEBEE) to Color(0xFFC62828)          // red (critical)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(flag, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
