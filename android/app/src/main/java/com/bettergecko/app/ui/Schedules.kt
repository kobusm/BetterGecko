package com.bettergecko.app.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.bettergecko.app.AppViewModel
import com.bettergecko.app.data.ModeSchedule
import com.bettergecko.app.data.Modes
import com.bettergecko.app.data.ScheduleMath
import com.bettergecko.app.data.Schedules
import com.bettergecko.app.data.Store

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulesScreen(vm: AppViewModel, nav: NavHostController, gsn: String) {
    val context = LocalContext.current
    var list by remember { mutableStateOf(Store.schedules(gsn)) }
    var editing by remember { mutableStateOf<ModeSchedule?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var exactOk by remember { mutableStateOf(Schedules.canScheduleExact(context)) }
    // Re-check after returning from the system "Alarms & reminders" screen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        exactOk = Schedules.canScheduleExact(context)
        if (exactOk) Schedules.rearmAll(context)
    }

    // Android 13+ needs a runtime grant to post notifications.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun persist(newList: List<ModeSchedule>) {
        list = ScheduleMath.sorted(newList)
        Schedules.save(context, gsn, list)
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Mode Schedules") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) { Icon(Icons.Filled.Add, "Add schedule") }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            if (!exactOk) item {
                Card(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Allow exact alarms so schedules run on the minute. Without it, Android may delay them.",
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.startActivity(
                                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        }) { Text("Open settings") }
                    }
                }
            }
            if (list.isEmpty()) item {
                Text("No schedules yet. Add one to switch mode automatically at a set time.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            items(list, key = { it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { editing = s; showEditor = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.timeLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(s.modeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(s.enabled, { on -> persist(list.map { if (it.id == s.id) it.copy(enabled = on) else it }) })
                    IconButton(onClick = { persist(list.filterNot { it.id == s.id }) }) { Icon(Icons.Filled.Delete, "Delete") }
                }
                HorizontalDivider()
            }
            item {
                Text("At each scheduled time BetterGecko switches the mode in the background and shows a notification. " +
                    "When you open the app it also catches up to whichever schedule should be active now.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
        }
    }

    if (showEditor) ScheduleEditor(editing, onDismiss = { showEditor = false }) { s ->
        persist(list.filterNot { it.id == s.id } + s)
        showEditor = false
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditor(existing: ModeSchedule?, onDismiss: () -> Unit, onSave: (ModeSchedule) -> Unit) {
    val time = rememberTimePickerState(existing?.hour ?: 6, existing?.minute ?: 0, is24Hour = true)
    var mode by remember { mutableStateOf(existing?.mode ?: "PV_ONLY") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New Schedule" else "Edit Schedule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(), Alignment.Center) { TimePicker(time) }
                Modes.all.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().selectable(mode == m.id, role = Role.RadioButton) { mode = m.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(mode == m.id, null); Spacer(Modifier.width(8.dp)); Text(m.label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton({
                onSave((existing ?: ModeSchedule(hour = 0, minute = 0, mode = mode))
                    .copy(hour = time.hour, minute = time.minute, mode = mode))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
