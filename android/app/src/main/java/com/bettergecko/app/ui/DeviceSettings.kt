package com.bettergecko.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.bettergecko.app.AppViewModel
import com.bettergecko.app.data.ApiException
import com.bettergecko.app.data.DeviceApi
import com.bettergecko.app.data.GeckoRealtime
import com.bettergecko.app.data.Modes
import com.bettergecko.app.data.PersistedSettings
import com.bettergecko.app.data.Store
import kotlinx.coroutines.launch

/** Settings tab: device picker + embedded settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(vm: AppViewModel, nav: NavHostController) {
    val devices by vm.devices.collectAsStateWithLifecycle()
    var selectedGsn by rememberSaveable { mutableStateOf<String?>(null) }
    val device = devices.firstOrNull { it.geckoSerialNumber == selectedGsn } ?: devices.firstOrNull()
    var reloadKey by remember { mutableStateOf(0) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") }, actions = {
            if (device != null) IconButton(onClick = { reloadKey++ }) { Icon(Icons.Filled.Refresh, "Reload") }
        })
    }) { padding ->
        Box(Modifier.padding(padding)) {
            if (device == null) {
                EmptyState(Icons.Filled.Settings, "No Devices", "Add a device on the Stats tab first.")
            } else {
                Column {
                    if (devices.size > 1) DevicePicker(devices, device, { selectedGsn = it.geckoSerialNumber },
                        Modifier.padding(16.dp))
                    DeviceSettingsContent(device.geckoSerialNumber, nav, reloadKey)
                }
            }
        }
    }
}

/** Device settings pushed from the Stats detail menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsRoute(vm: AppViewModel, nav: NavHostController, gsn: String) {
    var reloadKey by remember { mutableStateOf(0) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Device Settings") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { reloadKey++ }) { Icon(Icons.Filled.Refresh, "Reload") } })
    }) { padding ->
        Box(Modifier.padding(padding)) { DeviceSettingsContent(gsn, nav, reloadKey) }
    }
}

private val modeIcons: Map<String, ImageVector> = mapOf(
    "ALL_OFF" to Icons.Filled.PowerSettingsNew,
    "PV_ONLY" to Icons.Filled.WbSunny,
    "AC_AND_PV" to Icons.Filled.WbTwilight,
    "AC_ONLY" to Icons.Filled.Bolt,
)

@Composable
private fun DeviceSettingsContent(gsn: String, nav: NavHostController, reloadKey: Int) {
    val saved = remember(gsn) { Store.settings(gsn) }
    var ac by remember(gsn) { mutableDoubleStateOf(saved?.targetTemp ?: 50.0) }
    var pv by remember(gsn) { mutableDoubleStateOf(saved?.pvTargetTemp ?: 60.0) }
    var mode by remember(gsn) { mutableStateOf(saved?.mode ?: Modes.DEFAULT) }
    var serverMode by remember(gsn) { mutableStateOf<String?>(null) }   // baseline: only send setMode on real changes
    var loading by remember(gsn) { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var savingMode by remember { mutableStateOf(false) }
    var hasControl by remember(gsn) { mutableStateOf(true) }
    var errorMsg by remember(gsn) { mutableStateOf<String?>(null) }
    var warnMsg by remember(gsn) { mutableStateOf<String?>(null) }
    var savedMsg by remember(gsn) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persist() = Store.saveSettings(gsn, PersistedSettings(ac, pv, mode))

    LaunchedEffect(gsn, reloadKey) {
        loading = true
        runCatching { DeviceApi.hasControl(gsn) }.onSuccess { hasControl = it }
        // The REST API exposes no current setpoints/mode — pull a socket.io snapshot so this
        // reflects changes made in the official app. Falls back to the cached values.
        Store.deviceID?.let { id ->
            runCatching { GeckoRealtime.fetchSettings(gsn, id) }.getOrNull()?.let { live ->
                live.acMax?.let { ac = it.toDouble() }
                live.pvMax?.let { pv = it.toDouble() }
                live.geckoMode?.let { mode = it }
                if (live.hasSetpoints || live.geckoMode != null) persist()
            }
        }
        serverMode = mode
        loading = false
    }

    fun saveMode(newMode: String) {
        mode = newMode
        if (!hasControl || newMode == serverMode) return
        scope.launch {
            savingMode = true; warnMsg = null
            try { DeviceApi.setModeTolerant(gsn, newMode); persist() }
            catch (e: Exception) { warnMsg = "Mode: ${e.message}" }
            serverMode = newMode
            savingMode = false
        }
    }

    fun save() = scope.launch {
        saving = true; errorMsg = null; warnMsg = null; savedMsg = false
        try {
            DeviceApi.setTemperature(gsn, ac, pv)
            persist()
            savedMsg = true
        } catch (e: Exception) {
            errorMsg = "Temperature: ${e.message}"
        }
        saving = false
    }

    if (loading) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text("Loading settings…")
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!hasControl) Notice(Icons.Filled.Warning, SolarOrange,
            "Your account does not have control rights for this device. Contact your GeyserGecko installer.")

        // Temperature
        Section("Temperature", Icons.Filled.Thermostat, trailing = {
            Button(onClick = { save() }, enabled = !saving) {
                if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Save")
            }
        }) {
            SetpointSlider("AC Setpoint", Icons.Filled.Bolt, AcRed, ac) { ac = it; if (it > pv) pv = it }
            SetpointSlider("PV Setpoint", Icons.Filled.WbSunny, SolarOrange, pv) { pv = it; if (it < ac) ac = it }
            Spacer(Modifier.height(4.dp))
            Explainer(Icons.Filled.Bolt, AcRed, "Gecko will use AC to heat to ", "${ac.toInt()}°C")
            Explainer(Icons.Filled.WbSunny, SolarOrange, "Gecko will use PV to heat from ${ac.toInt()}°C to ", "${pv.toInt()}°C")
            Explainer(Icons.Filled.Info, MaterialTheme.colorScheme.onSurfaceVariant,
                "If no AC is available, Gecko will use PV to heat to ", "${pv.toInt()}°C")
            if (savedMsg) Text("Saved", color = GeckoGreen, style = MaterialTheme.typography.labelMedium)
        }

        // Mode
        Section("Operating Mode", Icons.Filled.Tune) {
            Modes.all.forEach { m ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = mode == m.id, enabled = hasControl, role = Role.RadioButton) { saveMode(m.id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == m.id, onClick = null, enabled = hasControl)
                    Spacer(Modifier.width(8.dp))
                    Icon(modeIcons[m.id] ?: Icons.Filled.Tune, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(m.label)
                }
            }
            if (savingMode) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp))
                Text("Saving mode…", style = MaterialTheme.typography.labelSmall)
            }
        }

        // Schedules
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable(enabled = hasControl) { nav.navigate(Routes.schedules(gsn)) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Mode Schedules", fontWeight = FontWeight.SemiBold)
                    Text("Automatically switch mode at set times, even when the app is closed.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }

        // Cost
        Section("Electricity Cost", Icons.Filled.Payments) {
            NumberField("Electricity Rate", "R", "/kWh", Store.rate) { Store.rate = it }
            NumberField("Element Rating", null, "kW", Store.elementKW(gsn)) { Store.setElementKW(gsn, it) }
            Text("Used on the Stats tab to estimate AC heating cost (rate × element kW × hours on). " +
                "The rate applies to all devices; the element rating is for this device.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        warnMsg?.let { Notice(Icons.Filled.Warning, SolarOrange, it) }
        errorMsg?.let { Notice(Icons.Filled.Warning, MaterialTheme.colorScheme.error, it) }
    }
}

@Composable
private fun Section(title: String, icon: ImageVector, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing()
            }
            content()
        }
    }
}

@Composable
private fun Notice(icon: ImageVector, color: Color, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 40–75 °C in 1° steps. */
@Composable
private fun SetpointSlider(label: String, icon: ImageVector, tint: Color, value: Double, onChange: (Double) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, color = tint, modifier = Modifier.weight(1f))
            Text("${value.toInt()}°", color = tint, fontWeight = FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("40°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = value.toFloat(), onValueChange = { onChange(Math.round(it).toDouble()) },
                valueRange = 40f..75f, steps = 34, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint, activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent),
            )
            Text("75°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Explainer(icon: ImageVector, tint: Color, text: String, bold: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(buildAnnotatedString {
            append(text); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        }, style = MaterialTheme.typography.bodySmall)
    }
}

/** Decimal field that persists whenever the text parses as a number. */
@Composable
private fun NumberField(label: String, prefix: String?, suffix: String, initial: Double, onValue: (Double) -> Unit) {
    var text by remember { mutableStateOf(initial.toBigDecimal().stripTrailingZeros().toPlainString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onValue) },
        label = { Text(label) },
        prefix = prefix?.let { { Text(it) } },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}
