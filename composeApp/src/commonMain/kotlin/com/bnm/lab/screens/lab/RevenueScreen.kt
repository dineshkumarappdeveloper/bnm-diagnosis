package com.bnm.lab.screens.lab

import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bnm.lab.revenue.RevenuePreset
import com.bnm.lab.revenue.RevenueRange
import com.bnm.lab.revenue.RevenueReport
import com.bnm.lab.revenue.RevenueRepository
import com.bnm.lab.revenue.RevenueSlice
import com.bnm.lab.revenue.inr
import com.bnm.lab.revenue.percentChange
import com.bnm.lab.revenue.renderRevenueCsv
import com.bnm.lab.ui.theme.AppTheme
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

private val WIDE = 900.dp

/**
 * The revenue dashboard — the Revenue tab of the Bills page: what the lab billed, collected and is still owed over a
 * period, how that splits by payment mode, test and referrer, and how it moved
 * against the period before.
 *
 * It reads this computer's database only, so it works the same with no network
 * at all. On the offline edition that database is the lab's whole record; on the
 * connected edition it also holds what sync has brought in from the lab's other
 * computers.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RevenueDashboard(
    revenue: RevenueRepository,
    businessId: String,
    offlineEdition: Boolean,
    /** Shows a short confirmation on the host page (e.g. "Copied as CSV"). */
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Recomputed on every composition rather than remembered, so a dashboard left
    // open past midnight rolls "Today" over on its own.
    val today = todayLocal()
    var preset by remember { mutableStateOf(RevenuePreset.THIS_MONTH) }
    var customFrom by remember { mutableStateOf(firstOfMonth(today).toString()) }
    var customTo by remember { mutableStateOf(today.toString()) }

    val customRange: RevenueRange? = remember(customFrom, customTo) {
        val f = customFrom.trim().takeIf { it.length == 10 }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val t = customTo.trim().takeIf { it.length == 10 }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        RevenueRange.validated(f, t, today)
    }
    // A half-typed custom date keeps the last good range on screen instead of
    // re-querying garbage on every keystroke.
    val range: RevenueRange? = if (preset == RevenuePreset.CUSTOM) customRange else preset.rangeFor(today)
    var shownRange by remember { mutableStateOf(range ?: RevenuePreset.THIS_MONTH.rangeFor(today)!!) }
    LaunchedEffect(range) { if (range != null) shownRange = range }

    val report by remember(businessId, shownRange) { revenue.reportFlow(businessId, shownRange) }
        .collectAsState(null)

    val clipboard = LocalClipboardManager.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 24.dp else 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Period ──
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                RevenuePreset.entries.forEach { p ->
                    FilterChip(selected = preset == p, onClick = { preset = p }, label = { Text(p.label) })
                }
                val csvReport = report
                TextButton(onClick = {
                    if (csvReport != null) {
                        clipboard.setText(AnnotatedString(renderRevenueCsv(csvReport)))
                        onMessage("Copied as CSV — paste into a spreadsheet")
                    }
                }, enabled = csvReport != null) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  Copy as CSV")
                }
            }
            if (preset == RevenuePreset.CUSTOM) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(customFrom, { customFrom = it.take(10) }, label = { Text("From (YYYY-MM-DD)") },
                        singleLine = true, modifier = Modifier.widthIn(max = 200.dp),
                        isError = customRange == null)
                    OutlinedTextField(customTo, { customTo = it.take(10) }, label = { Text("To (YYYY-MM-DD)") },
                        singleLine = true, modifier = Modifier.widthIn(max = 200.dp),
                        isError = customRange == null)
                }
                if (customRange == null) {
                    Text("Enter two dates up to today, the first on or before the second, at most ten years apart.",
                        style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.danger)
                }
            }

            val r = report
            if (r == null) {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            Text(
                "${rangeLabel(r.range)}  ·  compared with ${rangeLabel(r.priorRange)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Headline figures ──
            val tiles = listOf<@Composable (Modifier) -> Unit>(
                { m -> KpiTile("Billed", inr(r.current.billed), m,
                    caption = "${r.current.bills} bill${plural(r.current.bills)}",
                    change = percentChange(r.current.billed, r.prior.billed)) },
                { m -> KpiTile("Collected", inr(r.current.collected), m,
                    caption = collectedShare(r),
                    change = percentChange(r.current.collected, r.prior.collected)) },
                { m -> KpiTile("Due", inr(r.current.due), m,
                    caption = "on these bills",
                    tone = if (r.current.due > 0.005) AppTheme.colors.warning else null) },
                { m -> KpiTile("Orders", "${r.current.orders}", m,
                    caption = "${r.current.tests} test${plural(r.current.tests)} · ${inr(r.current.orderValue)}",
                    change = percentChange(r.current.orders.toDouble(), r.prior.orders.toDouble())) },
            )
            if (wide) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    tiles.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                }
            } else {
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { it(Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }

            // ── Things that need a decision, only when they exist ──
            AttentionChips(r)

            if (r.isEmpty) {
                RevenueCard {
                    Text("Nothing billed or registered in this period.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // ── Over time + payment modes ──
                if (wide) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(0.64f)) { TrendCard(r) }
                        Box(Modifier.weight(0.36f)) { SliceCard("By payment mode", "Collected", r.byPaymentMode, emptyText = "Nothing collected in this period.") }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) { SliceCard("Top tests", "Order value", r.topTests, countNoun = "ordered") }
                        Box(Modifier.weight(1f)) { SliceCard("Referrers", "Order value", r.byReferrer, countNoun = "order", showCommission = true) }
                    }
                } else {
                    TrendCard(r)
                    SliceCard("By payment mode", "Collected", r.byPaymentMode, emptyText = "Nothing collected in this period.")
                    SliceCard("Top tests", "Order value", r.topTests, countNoun = "ordered")
                    SliceCard("Referrers", "Order value", r.byReferrer, countNoun = "order", showCommission = true)
                }
            }

            // ── Where the numbers come from ──
            Text(
                "Billed, collected and due count bills by the date printed on them. Orders, tests and commission " +
                    "count by registration date; cancelled orders are left out. " +
                    if (offlineEdition) "Offline edition: every figure comes from this computer."
                    else "Bills from the lab's other computers are included once they have synced (every five minutes).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RevenueCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun KpiTile(
    label: String,
    value: String,
    modifier: Modifier,
    caption: String? = null,
    change: Int? = null,
    tone: Color? = null,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FittedAmount(value, AppTheme.typography.displayMd, tone ?: MaterialTheme.colorScheme.onSurface)
            caption?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            change?.let { ChangeChip(it) }
        }
    }
}

/**
 * A money figure that shrinks to fit rather than ending in "…": on a phone two
 * tiles share a row, and "₹ 12,54,…" is a number the owner cannot read.
 */
@Composable
internal fun FittedAmount(text: String, style: TextStyle, color: Color, minSize: TextUnit = 13.sp) {
    // Plain Text only — no BoxWithConstraints. The KPI rows size themselves with
    // IntrinsicSize.Min, and a SubcomposeLayout inside them throws at layout
    // ("intrinsic measurements of SubcomposeLayout layouts are not supported"),
    // which closed the app the moment the dashboard opened.
    var size by remember(text, style) { mutableStateOf(style.fontSize) }
    Text(
        text,
        style = style.copy(fontSize = size),
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { layout ->
            val room = layout.layoutInput.constraints.maxWidth
            if (layout.hasVisualOverflow) {
                if (size.value > minSize.value) size = maxOf(minSize.value, size.value * 0.9f).sp
            } else if (size.value < style.fontSize.value) {
                // A window that grew gives the full size back, one step at a time,
                // and only when the bigger text will clearly still fit.
                val bigger = minOf(style.fontSize.value, size.value / 0.9f)
                if (layout.size.width * (bigger / size.value) <= room * 0.97f) size = bigger.sp
            }
        },
    )
}

@Composable
private fun ChangeChip(pct: Int) {
    val c = AppTheme.colors
    val (bg, fg) = when {
        pct > 0 -> c.successSoft to c.success
        pct < 0 -> c.dangerSoft to c.danger
        else -> c.surfaceMuted to c.textSecondary
    }
    val text = when {
        pct > 0 -> "▲ $pct% vs before"
        pct < 0 -> "▼ ${-pct}% vs before"
        else -> "No change"
    }
    Box(Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttentionChips(r: RevenueReport) {
    val c = AppTheme.colors
    val chips = buildList {
        if (r.outstandingAll > 0.005) add(Triple(
            "${inr(r.outstandingAll)} to collect across ${r.outstandingAllBills} bill${plural(r.outstandingAllBills)} (all dates)",
            c.warningSoft, c.warning))
        if (r.unbilledOrders > 0) add(Triple(
            "${r.unbilledOrders} order${plural(r.unbilledOrders)} without a bill on this computer · ${inr(r.unbilledValue)}",
            c.warningSoft, c.warning))
        if (r.commissionPayable > 0.005) add(Triple(
            "Referral commission on these orders ${inr(r.commissionPayable)}",
            c.infoSoft, c.info))
        if (r.cancelledStillBilled > 0) add(Triple(
            "${r.cancelledStillBilled} cancelled order${plural(r.cancelledStillBilled)} still ha${if (r.cancelledStillBilled == 1) "s" else "ve"} a bill",
            c.dangerSoft, c.danger))
        if (r.unpricedTests > 0) add(Triple(
            "${r.unpricedTests} test${plural(r.unpricedTests)} registered at ₹ 0 — set prices in the catalog",
            c.warningSoft, c.warning))
    }
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { (text, bg, fg) ->
            Box(Modifier.background(bg, RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(text, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Billed per day (or month) as bars, with the collected part filled solid, so
 * the gap at the top of each bar IS what that day's bills still owe.
 */
@Composable
private fun TrendCard(r: RevenueReport) {
    val c = AppTheme.colors
    RevenueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (r.monthly) "Billed by month" else "Billed by day", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            LegendDot(c.accent, "Collected")
            Spacer(Modifier.width(12.dp))
            LegendDot(c.accentSoft, "Still due")
        }
        val peak = r.buckets.maxOfOrNull { it.billed } ?: 0.0
        if (peak <= 0.005) {
            Text("No bills in this period.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@RevenueCard
        }
        Text("Highest: ${inr(peak)}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        val chartHeight = 160.dp
        Row(
            Modifier.fillMaxWidth().height(chartHeight),
            horizontalArrangement = Arrangement.spacedBy(if (r.buckets.size > 40) 1.dp else 3.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            r.buckets.forEach { b ->
                val billedFrac = (b.billed / peak).toFloat().coerceIn(0f, 1f)
                val collectedFrac = if (b.billed > 0) (b.collected / b.billed).toFloat().coerceIn(0f, 1f) else 0f
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    if (billedFrac > 0f) {
                        Column(
                            Modifier.fillMaxWidth().fillMaxHeight(billedFrac.coerceAtLeast(0.02f))
                                .background(c.accentSoft, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                            verticalArrangement = Arrangement.Bottom,
                        ) {
                            if (collectedFrac > 0f) {
                                Box(Modifier.fillMaxWidth().fillMaxHeight(collectedFrac)
                                    .background(c.accent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
                            }
                        }
                    }
                }
            }
        }
        // One label per group of bars, each group given the width of the bars it
        // spans — so a label always has room, and a phone shows fewer of them
        // than a desktop instead of clipping every one ("1 Se", "11 S").
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fit = maxOf(1, (maxWidth / 64.dp).toInt())
            val every = maxOf(1, (r.buckets.size + fit - 1) / fit)
            Row(Modifier.fillMaxWidth()) {
                r.buckets.chunked(every).forEach { group ->
                    Box(Modifier.weight(group.size.toFloat()), contentAlignment = Alignment.TopStart) {
                        // A short trailing group has no room for a whole label; a
                        // clipped "31 A" reads as a wrong date, so it goes unlabelled.
                        if (group.size == every) {
                            Text(group.first().label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                softWrap = false, overflow = TextOverflow.Clip,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A ranked list with a proportion bar per row. */
@Composable
private fun SliceCard(
    title: String,
    amountLabel: String,
    slices: List<RevenueSlice>,
    countNoun: String = "bill",
    showCommission: Boolean = false,
    emptyText: String = "Nothing in this period.",
) {
    val c = AppTheme.colors
    RevenueCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(amountLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (slices.isEmpty()) {
            Text(emptyText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@RevenueCard
        }
        val top = slices.maxOf { it.amount }.takeIf { it > 0 } ?: 1.0
        slices.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(inr(s.amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).background(c.surfaceMuted, RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth((s.amount / top).toFloat().coerceIn(0f, 1f)).height(6.dp)
                        .background(c.accent, RoundedCornerShape(3.dp)))
                }
                Text(
                    buildString {
                        append(sliceCount(s.count, countNoun))
                        if (showCommission && s.commission > 0.005) append(" · commission ${inr(s.commission)}")
                    },
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sliceCount(n: Int, noun: String): String = when (noun) {
    "ordered" -> "ordered $n time${plural(n)}"
    else -> "$n $noun${plural(n)}"
}

private fun plural(n: Int) = if (n == 1) "" else "s"

private fun collectedShare(r: RevenueReport): String =
    if (r.current.billed > 0.005) "${(r.current.collected / r.current.billed * 100).toInt()}% of billed" else "of billed"

private val MONTH_NAMES = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun dayLabel(d: LocalDate) = "${d.day} ${MONTH_NAMES[d.month.ordinal]} ${d.year}"

private fun rangeLabel(r: RevenueRange): String =
    if (r.from == r.to) dayLabel(r.from) else "${dayLabel(r.from)} – ${dayLabel(r.to)}"
