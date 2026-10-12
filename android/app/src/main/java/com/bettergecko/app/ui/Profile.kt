package com.bettergecko.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bettergecko.app.AppViewModel
import com.bettergecko.app.data.GeckoRealtime
import com.bettergecko.app.data.LiveSnapshot
import com.bettergecko.app.data.Store

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: AppViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    var selectedGsn by rememberSaveable { mutableStateOf<String?>(null) }
    val device = devices.firstOrNull { it.geckoSerialNumber == selectedGsn } ?: devices.firstOrNull()
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Profile") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Group("Account") {
                Field("Email", session?.email ?: "—")
                Field("Role", session?.role ?: "—")
            }
            if (device != null) {
                DevicePicker(devices, device, { selectedGsn = it.geckoSerialNumber })
                // key() gives each device its own edit state; switching devices disposes (and saves) the old one.
                androidx.compose.runtime.key(device.geckoSerialNumber) {
                    SystemSection(vm, device.geckoSerialNumber, device.friendlyName ?: "")
                    DeviceInfoSection(device.geckoSerialNumber)
                }
            }
            OutlinedButton(
                onClick = { confirmLogout = true }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Sign out") }
        }
    }

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("Sign out?") },
        confirmButton = { TextButton({ confirmLogout = false; vm.logout() }) { Text("Sign out", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton({ confirmLogout = false }) { Text("Cancel") } },
    )
}

@Composable
private fun SystemSection(vm: AppViewModel, gsn: String, initialName: String) {
    var name by remember { mutableStateOf(initialName) }
    var address by remember { mutableStateOf(Store.address(gsn)) }
    val latestName by rememberUpdatedState(name)
    val latestAddress by rememberUpdatedState(address)
    // Commit edits when leaving the tab or switching device.
    DisposableEffect(gsn) {
        onDispose {
            if (latestName != initialName) vm.renameDevice(gsn, latestName)
            Store.setAddress(gsn, latestAddress)
        }
    }
    Group("System") {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("System Name") }, singleLine = true)
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("Installation Address") },
            placeholder = { Text("Street, city…") }, maxLines = 3)
        Footnote("Stored on this device only — GeyserGecko has no API to save the name or address to the server.")
    }
}

@Composable
private fun DeviceInfoSection(gsn: String) {
    var live by remember { mutableStateOf<LiveSnapshot?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(gsn) {
        loading = true
        Store.deviceID?.let { id -> live = runCatching { GeckoRealtime.fetchSettings(gsn, id) }.getOrNull() }
        loading = false
    }
    fun v(s: String?) = s ?: if (loading) "…" else "—"
    Group("Device", trailing = { if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }) {
        Field("GSN", gsn)
        Field("Firmware", v(live?.fwVersion))
        Field("MCU Firmware", v(live?.mcuFwVersion?.toString()))
        Field("Connected AP", v(live?.connectedAP))
        Field("Signal (RSSI)", v(live?.rssi?.let { "$it dBm" }))
        live?.temperature?.let { Field("Temperature", "%.1f °C".format(it)) }
        Footnote("The API exposes only one serial (the GSN). Firmware, AP and signal come from the live device.")
    }
}

@Composable
private fun Group(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing()
            }
            content()
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row {
        Text(label, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Footnote(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
