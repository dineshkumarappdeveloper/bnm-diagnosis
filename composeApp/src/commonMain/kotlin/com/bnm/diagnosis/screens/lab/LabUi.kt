package com.bnm.diagnosis.screens.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bnm.diagnosis.lab.LabRepository
import com.bnm.diagnosis.screens.license.defaultDeviceName
import com.bnm.diagnosis.screens.staff.initialsOf
import com.bnm.diagnosis.staff.Staff
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

/**
 * Signed-in staff chip for the LabHome header (P4): initials disc + name + role,
 * click → "Switch user" / "Sign out". Both do the same thing to the session (it
 * is dropped and the app returns to the sign-in grid) — two labels because they
 * mean different things to the person at the bench.
 *
 * Null staff (session somehow empty) renders nothing rather than a dead chip.
 */
@Composable
fun StaffHeaderChip(
    staff: Staff?,
    onSwitchUser: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (staff == null) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                .clickable { open = true }
                .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(initialsOf(staff.name), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column {
                Text(staff.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(staff.roleLabel, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Switch user") }, onClick = { open = false; onSwitchUser() })
            DropdownMenuItem(text = { Text("Sign out") }, onClick = { open = false; onSignOut() })
        }
    }
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
