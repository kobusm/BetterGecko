package com.bettergecko.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.bettergecko.app.AppViewModel
import com.bettergecko.app.data.DeviceApi
import com.bettergecko.app.data.DeviceReading
import com.bettergecko.app.data.GeckoDevice
import com.bettergecko.app.data.GeckoState
import kotlinx.coroutines.launch

// MARK: - Summary tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: AppViewModel) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    val readings by vm.readings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    LaunchedEffect(devices) { vm.refreshReadings() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Summary") }, actions = {
            if (devices.isNotEmpty()) IconButton(onClick = { vm.refreshReadings() }, enabled = !refreshing) {
                Icon(Icons.Filled.Refresh, "Refresh")
            }
        })
    }) { padding ->
        Box(Modifier.padding(padding)) {
            if (devices.isEmpty()) {
                EmptyState(Icons.Filled.Thermostat, "No Devices", "Add a device on the Stats tab first.")
            } else {
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refreshReadings() }) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(devices, key = { it.geckoSerialNumber }) { InfoCard(it, readings[it.geckoSerialNumber]) }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(device: GeckoDevice, reading: DeviceReading?) {
    val state = reading?.state ?: GeckoState.UNKNOWN
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            device.friendlyName?.takeIf { it.isNotEmpty() }?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = muted)
                Spacer(Modifier.height(20.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (reading?.temperature != null) {
                        Text("%.1f°".format(reading.temperature), fontSize = 48.sp, fontWeight = FontWeight.Bold,
                            color = tempColor(reading.temperature, MaterialTheme.colorScheme.onSurface))
                    } else {
                        Box(Modifier.height(64.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Thermostat, null, Modifier.size(16.dp), tint = muted)
                        Text("Temperature", color = muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                VerticalDivider(Modifier.height(80.dp).padding(horizontal = 12.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(state.icon, null, Modifier.size(44.dp), tint = state.color(muted))
                    Spacer(Modifier.height(8.dp))
                    Text(state.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = state.color(muted))
                }
            }
        }
    }
}

// MARK: - Stats tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: AppViewModel, nav: NavHostController) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }

    when {
        // Single device — skip the list and show the detail directly.
        devices.size == 1 -> DeviceDetailScreen(vm, nav, devices.first().geckoSerialNumber, showBack = false,
            onAddDevice = { showAdd = true })
        else -> {
            val readings by vm.readings.collectAsStateWithLifecycle()
            val refreshing by vm.refreshing.collectAsStateWithLifecycle()
            LaunchedEffect(devices) { if (devices.isNotEmpty()) vm.refreshReadings() }
            Scaffold(topBar = {
                TopAppBar(title = { Text("Stats") }, actions = {
                    if (devices.isNotEmpty()) IconButton(onClick = { vm.refreshReadings() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    IconButton(onClick = { showAdd = true }) { Icon(Icons.Filled.Add, "Add device") }
                })
            }) { padding ->
                Box(Modifier.padding(padding)) {
                    if (devices.isEmpty()) {
                        EmptyState(Icons.Filled.Thermostat, "No Devices",
                            "Add your GeyserGecko device using the GSN printed on the unit's screen.",
                            "Add Device" to { showAdd = true })
                    } else {
                        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refreshReadings() }) {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(devices, key = { it.geckoSerialNumber }) { d ->
                                    DeviceRow(d, readings[d.geckoSerialNumber],
                                        onClick = { nav.navigate(Routes.device(d.geckoSerialNumber)) },
                                        onDelete = { vm.removeDevice(d.geckoSerialNumber) })
                                    HorizontalDivider(Modifier.padding(start = 76.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAdd) AddDeviceDialog(vm) { showAdd = false }
}

@Composable
private fun DeviceRow(device: GeckoDevice, reading: DeviceReading?, onClick: () -> Unit, onDelete: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val color = reading?.state?.color(muted) ?: muted
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(reading?.state?.icon ?: Icons.Filled.Thermostat, null, tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(device.displayName, fontWeight = FontWeight.Bold)
            Text(reading?.state?.label ?: "Loading…", style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (reading?.temperature != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text("%.1f°".format(reading.temperature), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = tempColor(reading.temperature, muted))
                reading.pvVoltage?.takeIf { it > 0 }?.let {
                    Text("${it}V PV", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Remove", tint = muted) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Remove ${device.displayName}?") },
        confirmButton = { TextButton({ confirm = false; onDelete() }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}

@Composable
fun AddDeviceDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var gsn by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(gsn, { gsn = it }, label = { Text("GSN (Gecko Serial Number)") },
                    placeholder = { Text("e.g. fce8c0de16e0") }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                OutlinedTextField(name, { name = it }, label = { Text("Device name (optional)") }, singleLine = true)
                Text("Find the GSN on your GeyserGecko unit's display screen — it's the short hex code, not the longer SN.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = gsn.isNotBlank() && !verifying, onClick = {
                val serial = gsn.trim().lowercase().replace(":", "")
                verifying = true; error = null
                scope.launch {
                    try {
                        DeviceApi.performanceHistory(serial)   // verify the GSN is reachable
                        vm.addDevice(GeckoDevice(serial, name.trim().ifEmpty { null }))
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                    }
                    verifying = false
                }
            }) {
                if (verifying) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Add")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
