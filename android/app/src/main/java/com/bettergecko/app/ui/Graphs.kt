package com.bettergecko.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bettergecko.app.AppViewModel
import com.bettergecko.app.data.DeviceApi
import com.bettergecko.app.data.HistoryPoint
import com.bettergecko.app.data.SAST
import com.bettergecko.app.data.TimeRange
import com.bettergecko.app.data.buildCsv
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphsScreen(vm: AppViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    var selectedGsn by rememberSaveable { mutableStateOf<String?>(null) }
    val device = devices.firstOrNull { it.geckoSerialNumber == selectedGsn } ?: devices.firstOrNull()
    var range by rememberSaveable { mutableStateOf(TimeRange.ONE_DAY) }
    var history by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun load() {
        val gsn = device?.geckoSerialNumber ?: return
        scope.launch {
            loading = true; error = null
            try { history = DeviceApi.performanceHistory(gsn).toHistoryPoints() }
            catch (e: Exception) { error = e.message }
            loading = false
        }
    }
    LaunchedEffect(device?.geckoSerialNumber) { history = emptyList(); load() }
    val filtered = range.filter(history)

    Scaffold(topBar = {
        TopAppBar(title = { Text("Graphs") }, actions = {
            IconButton(onClick = { load() }, enabled = !loading && device != null) { Icon(Icons.Filled.Refresh, "Refresh") }
            IconButton(enabled = filtered.isNotEmpty(), onClick = {
                device?.let { shareCsv(context, "${it.geckoSerialNumber}_${range.label}.csv", buildCsv(filtered)) }
            }) { Icon(Icons.Filled.Share, "Export CSV") }
        })
    }) { padding ->
        if (device == null) {
            Box(Modifier.padding(padding)) {
                EmptyState(Icons.AutoMirrored.Filled.ShowChart, "No Devices", "Add a device on the Stats tab first.")
            }
            return@Scaffold
        }
        PullToRefreshBox(isRefreshing = loading && history.isNotEmpty(), onRefresh = { load() },
            modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DevicePicker(devices, device, { selectedGsn = it.geckoSerialNumber })
                Segmented(TimeRange.entries, range, { it.label }, { range = it })
                when {
                    loading && history.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), Alignment.Center) { CircularProgressIndicator() }
                    error != null -> EmptyState(Icons.Filled.Warning, "Error", error ?: "")
                    filtered.isEmpty() -> EmptyState(Icons.AutoMirrored.Filled.ShowChart, "No Data", "No readings in the selected time range.")
                    else -> Charts(filtered, range)
                }
            }
        }
    }
}

@Composable
private fun Charts(points: List<HistoryPoint>, range: TimeRange) {
    val xStart = points.first().timestamp
    val xEnd = points.last().timestamp
    val ticks = remember(points, range) { xTicks(xStart, xEnd, range) }
    val fmt = remember(range) { xFormatter(range) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    // Temperature with tap/drag selection — value shown in the header.
    var selected by remember(points) { mutableStateOf<HistoryPoint?>(null) }
    val temps = points.mapNotNull { it.temperature }
    val yMin = floor(((temps.minOrNull() ?: 0.0) - 10) / 5) * 5
    val yMax = ceil(((temps.maxOrNull() ?: 100.0) + 10) / 5) * 5
    ChartCard("Temperature", trailing = {
        val sel = selected
        if (sel?.temperature != null) Column(horizontalAlignment = Alignment.End) {
            Text("%.1f°C".format(sel.temperature), color = SolarOrange, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(fmt.format(sel.timestamp), color = muted, style = MaterialTheme.typography.labelSmall)
        } else Text("${points.size} readings", color = muted, style = MaterialTheme.typography.labelMedium)
    }) {
        TimeChart(
            series = points.mapNotNull { p -> p.temperature?.let { p.timestamp to it } },
            xStart, xEnd, ticks, fmt, yMin, yMax,
            yTicks = generateSequence(yMin) { it + 5 }.takeWhile { it <= yMax }.toList(),
            yLabel = { "${it.toInt()}°" }, color = SolarOrange, height = 200.dp,
            selected = selected?.timestamp,
            onSelect = { t -> selected = points.minByOrNull { abs(it.timestamp.toEpochMilli() - t.toEpochMilli()) } },
        )
    }

    ChartCard("PV Active") {
        TimeChart(points.map { it.timestamp to it.pvOn.toDouble() }, xStart, xEnd, ticks, fmt, 0.0, 1.0,
            listOf(0.0, 1.0), { if (it >= 1) "On" else "Off" }, SolarOrange, 100.dp, step = true)
    }
    ChartCard("AC Active") {
        TimeChart(points.map { it.timestamp to it.acOn.toDouble() }, xStart, xEnd, ticks, fmt, 0.0, 1.0,
            listOf(0.0, 1.0), { if (it >= 1) "On" else "Off" }, AcRed, 100.dp, step = true)
    }

    val pv = points.mapNotNull { p -> p.pvVoltage?.takeIf { it > 0 }?.let { p.timestamp to it.toDouble() } }
    ChartCard("PV Voltage") {
        if (pv.isEmpty()) {
            Text("No PV voltage data in this range", color = muted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(16.dp))
        } else {
            val lo = pv.minOf { it.second }; val hi = pv.maxOf { it.second }
            val (yLo, yHi, step) = niceScale(lo, hi)
            TimeChart(pv, xStart, xEnd, ticks, fmt, yLo, yHi,
                generateSequence(yLo) { it + step }.takeWhile { it <= yHi + 1e-9 }.toList(),
                { "${it.toInt()} V" }, SolarOrange, 150.dp)
        }
    }
}

@Composable
private fun ChartCard(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing()
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * Minimal time-series chart on a Canvas: gridlines, y labels on the left, SAST time labels
 * below, a line (or step + filled area for on/off series), and an optional selection rule.
 */
@Composable
private fun TimeChart(
    series: List<Pair<Instant, Double>>,
    xStart: Instant, xEnd: Instant, xTicks: List<Instant>, xFmt: DateTimeFormatter,
    yMin: Double, yMax: Double, yTicks: List<Double>, yLabel: (Double) -> String,
    color: Color, height: Dp,
    step: Boolean = false,
    selected: Instant? = null,
    onSelect: ((Instant) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val span = (xEnd.toEpochMilli() - xStart.toEpochMilli()).coerceAtLeast(1)
    val yLabelWidth = yTicks.maxOfOrNull { measurer.measure(yLabel(it), labelStyle).size.width } ?: 0

    var plotLeft = 0f; var plotWidth = 1f
    fun xToInstant(x: Float) = Instant.ofEpochMilli(xStart.toEpochMilli() + ((x - plotLeft) / plotWidth * span).toLong())

    val gestures = if (onSelect == null) Modifier else Modifier
        .pointerInput(series) { detectTapGestures { onSelect(xToInstant(it.x)) } }
        .pointerInput(series) { detectDragGestures { change, _ -> onSelect(xToInstant(change.position.x)) } }

    Canvas(Modifier.fillMaxWidth().height(height + 20.dp).then(gestures)) {
        val left = yLabelWidth + 6.dp.toPx()
        val bottom = size.height - 18.dp.toPx()
        val top = 4.dp.toPx()
        val w = size.width - left - 4.dp.toPx()
        plotLeft = left; plotWidth = w
        fun x(t: Instant) = left + (t.toEpochMilli() - xStart.toEpochMilli()).toFloat() / span * w
        fun y(v: Double) = bottom - ((v - yMin) / (yMax - yMin).coerceAtLeast(1e-9)).toFloat() * (bottom - top)

        for (t in yTicks) {
            val yy = y(t)
            drawLine(gridColor, Offset(left, yy), Offset(left + w, yy), 1f)
            val m = measurer.measure(yLabel(t), labelStyle)
            drawText(m, topLeft = Offset(left - m.size.width - 4.dp.toPx(), yy - m.size.height / 2f))
        }
        for ((i, t) in xTicks.withIndex()) {
            val xx = x(t)
            drawLine(gridColor, Offset(xx, top), Offset(xx, bottom), 1f)
            val m = measurer.measure(xFmt.format(t), labelStyle)
            // Keep edge labels inside the canvas.
            val lx = when (i) { 0 -> xx; xTicks.lastIndex -> xx - m.size.width; else -> xx - m.size.width / 2f }
                .coerceIn(0f, size.width - m.size.width)
            drawText(m, topLeft = Offset(lx, bottom + 4.dp.toPx()))
        }

        if (series.isNotEmpty()) {
            val line = Path()
            series.forEachIndexed { i, (t, v) ->
                if (i == 0) line.moveTo(x(t), y(v))
                else {
                    if (step) line.lineTo(x(t), y(series[i - 1].second))   // hold previous value until this sample
                    line.lineTo(x(t), y(v))
                }
            }
            if (step) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(x(series.last().first), y(yMin))
                    lineTo(x(series.first().first), y(yMin))
                    close()
                }
                drawPath(area, color.copy(alpha = 0.35f))
            }
            drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
        }

        selected?.let {
            val xx = x(it)
            drawLine(labelColor.copy(alpha = 0.5f), Offset(xx, top), Offset(xx, bottom), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
    }
}

private fun xFormatter(range: TimeRange): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (range == TimeRange.THREE_DAYS) "EEE HH:mm" else "HH:mm", Locale.US).withZone(SAST)

/** Ticks from first to last sample at the range's stride (15 min / 1 h / 6 h / 1 day), plus the last sample. */
internal fun xTicks(first: Instant, last: Instant, range: TimeRange): List<Instant> {
    if (!first.isBefore(last)) return listOf(first)
    val (amount, unit) = when (range) {
        TimeRange.ONE_HOUR -> 15L to ChronoUnit.MINUTES
        TimeRange.SIX_HOURS -> 1L to ChronoUnit.HOURS
        TimeRange.ONE_DAY -> 6L to ChronoUnit.HOURS
        TimeRange.THREE_DAYS -> 1L to ChronoUnit.DAYS
    }
    val out = mutableListOf(first)
    var cur = first.atZone(SAST)
    while (true) {
        cur = cur.plus(amount, unit)
        if (!cur.toInstant().isBefore(last)) break
        out += cur.toInstant()
    }
    // Drop a penultimate tick that would collide with the last label.
    if (out.size > 1 && (last.toEpochMilli() - out.last().toEpochMilli()) < (last.toEpochMilli() - first.toEpochMilli()) / 12) out.removeAt(out.lastIndex)
    out += last
    return out
}

/** Rounds [lo, hi] outward to a step that gives ~4 gridlines. */
internal fun niceScale(lo: Double, hi: Double): Triple<Double, Double, Double> {
    val raw = ((hi - lo).coerceAtLeast(1.0)) / 4
    val mag = Math.pow(10.0, floor(Math.log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
    return Triple(floor(lo / step) * step, ceil(hi / step) * step, step)
}
