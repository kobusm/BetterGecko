package com.bettergecko.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.bettergecko.app.AppViewModel
import com.bettergecko.app.data.ACUsageLedger
import com.bettergecko.app.data.DeviceApi
import com.bettergecko.app.data.GeckoState
import com.bettergecko.app.data.HistoryPoint
import com.bettergecko.app.data.Modes
import com.bettergecko.app.data.Store
import com.bettergecko.app.data.TimeRange
import com.bettergecko.app.data.buildCsv
import com.bettergecko.app.data.formatDuration
import com.bettergecko.app.data.stateTotals
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    vm: AppViewModel,
    nav: NavHostController,
    gsn: String,
    showBack: Boolean,
    onAddDevice: (() -> Unit)? = null,
) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val device = devices.firstOrNull { it.geckoSerialNumber == gsn }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var history by remember { mutableStateOf<List<HistoryPoint>>(emptyList()) }
    var pvWeek by remember { mutableStateOf<Int?>(null) }
    var acWeek by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var ledger by remember { mutableStateOf(Store.ledger(gsn)) }
    var range by rememberSaveable { mutableStateOf(TimeRange.ONE_DAY) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    fun load() = scope.launch {
        loading = true; error = null
        try {
            val r = DeviceApi.performanceHistory(gsn)
            history = r.toHistoryPoints()
            pvWeek = r.pvUsageWeek; acWeek = r.acUsageWeek
            ledger = Store.ledger(gsn).merge(history).also { Store.saveLedger(gsn, it) }
        } catch (e: Exception) {
            error = e.message
        }
        loading = false
    }
    LaunchedEffect(gsn) { load() }

    if (device == null) {   // removed while on screen
        LaunchedEffect(Unit) { if (showBack) nav.popBackStack() }
        return
    }
    val filtered = range.filter(history)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(device.displayName) },
            navigationIcon = {
                if (showBack) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                IconButton(onClick = { load() }, enabled = !loading) { Icon(Icons.Filled.Refresh, "Refresh") }
                onAddDevice?.let { IconButton(onClick = it) { Icon(Icons.Filled.Add, "Add device") } }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Export CSV") }, enabled = filtered.isNotEmpty(),
                            leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = { menu = false; shareCsv(context, "${gsn}_${range.label}.csv", buildCsv(filtered)) })
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Device Settings") }, leadingIcon = { Icon(Icons.Filled.Settings, null) },
                            onClick = { menu = false; nav.navigate(Routes.settings(gsn)) })
                        HorizontalDivider()
                        DropdownMenuItem({ Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; renaming = true })
                        DropdownMenuItem({ Text("Remove Device", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; vm.removeDevice(gsn) })
                    }
                }
            },
        )
    }) { padding ->
        PullToRefreshBox(isRefreshing = loading && history.isNotEmpty(), onRefresh = { load() },
            modifier = Modifier.padding(padding).fillMaxSize()) {
            when {
                loading && history.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                error != null && history.isEmpty() -> EmptyState(Icons.Filled.Warning, "Error", error ?: "")
                else -> StatsGrid(gsn, filtered, history.lastOrNull(), range, { range = it }, pvWeek, acWeek, ledger)
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(device.friendlyName ?: "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename Device") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton({ vm.renameDevice(gsn, name); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton({ renaming = false }) { Text("Cancel") } },
        )
    }
}

private data class Stat(val title: String, val value: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: Color)

@Composable
private fun StatsGrid(
    gsn: String, filtered: List<HistoryPoint>, latest: HistoryPoint?, range: TimeRange, onRange: (TimeRange) -> Unit,
    pvWeek: Int?, acWeek: Int?, ledger: ACUsageLedger,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val totals = stateTotals(filtered)
    val state = GeckoState.from(latest?.state)
    val rate = Store.rate
    val kw = Store.elementKW(gsn)
    fun cost(at: Instant) = if (rate <= 0 || kw <= 0) "Set in Settings"
        else "R%.2f".format(ledger.hoursInMonthOf(at) * kw * rate)

    val stats = listOf(
        Stat("Temperature", latest?.temperature?.let { "%.1f°C".format(it) } ?: "—", Icons.Filled.Thermostat, tempColor(latest?.temperature, muted)),
        Stat("State", state.label, state.icon, SolarOrange),
        Stat("PV Voltage", latest?.pvVoltage?.let { "$it V" } ?: "—", Icons.Filled.WbSunny, SolarOrange),
        Stat("Mode", Modes.label(Store.settings(gsn)?.mode ?: Modes.DEFAULT), Icons.Filled.Tune, GeckoGreen),
        Stat("Solar Active", formatDuration(totals.solarSecs), Icons.Filled.WbSunny, SolarOrange),
        Stat("AC Active", formatDuration(totals.elementSecs), Icons.Filled.Bolt, AcRed),
        Stat("Idle / Off", formatDuration(totals.idleSecs), Icons.Filled.DarkMode, IdleBlue),
        Stat("Solar (Week)", pvWeek?.let { formatDuration(it * 60.0) } ?: "—", Icons.Filled.WbSunny, SolarOrange),
        Stat("AC (Week)", acWeek?.let { formatDuration(it * 60.0) } ?: "—", Icons.Filled.Bolt, AcRed),
        Stat("Cost (Last Month)", cost(ACUsageLedger.previousMonth(Instant.now())), Icons.Filled.Payments, GeckoGreen),
        Stat("Cost (This Month)", cost(Instant.now()), Icons.Filled.Payments, GeckoGreen),
    )

    LazyVerticalGrid(
        GridCells.Adaptive(160.dp), Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column { Segmented(TimeRange.entries, range, { it.label }, onRange) }
        }
        items(stats) { StatCard(it.title, it.value, it.icon, it.color, Modifier.fillMaxWidth()) }
    }
}

/** Writes the CSV to the app cache and opens the system share sheet (Save to Drive/Files, email…). */
fun shareCsv(context: Context, name: String, csv: String) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, name).apply { writeText(csv) }
    // FileProvider hands other apps a temporary content:// URI instead of a raw file path.
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Export CSV"))
}
