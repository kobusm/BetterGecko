package com.bettergecko.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bettergecko.app.data.AuthApi
import com.bettergecko.app.data.DeviceApi
import com.bettergecko.app.data.DeviceReading
import com.bettergecko.app.data.GeckoDevice
import com.bettergecko.app.data.Schedules
import com.bettergecko.app.data.SessionInfo
import com.bettergecko.app.data.Store
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * App-wide state — the AppState equivalent. A ViewModel survives configuration changes
 * (rotation, dark-mode switch) that recreate the Activity, so the session isn't reloaded.
 * Compose screens observe the StateFlows and recompose when they change.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val _session = MutableStateFlow<SessionInfo?>(null)
    val session: StateFlow<SessionInfo?> = _session.asStateFlow()

    private val _isLoadingSession = MutableStateFlow(true)
    val isLoadingSession: StateFlow<Boolean> = _isLoadingSession.asStateFlow()

    private val _devices = MutableStateFlow<List<GeckoDevice>>(emptyList())
    val devices: StateFlow<List<GeckoDevice>> = _devices.asStateFlow()

    /** Latest reading per GSN, shared by the Summary and Stats tabs. */
    private val _readings = MutableStateFlow<Map<String, DeviceReading>>(emptyMap())
    val readings: StateFlow<Map<String, DeviceReading>> = _readings.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            _session.value = runCatching { AuthApi.restoreSession() }.getOrNull()
            if (_session.value != null) _devices.value = Store.devices
            // Let the splash animation play out rather than flashing.
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < 1200) kotlinx.coroutines.delay(1200 - elapsed)
            _isLoadingSession.value = false
            applyActiveSchedules()
        }
    }

    suspend fun login(email: String, password: String) {
        _session.value = AuthApi.login(email, password)
        _devices.value = Store.devices
    }

    fun logout() = viewModelScope.launch {
        _devices.value.forEach { Schedules.cancelAll(getApplication(), it.geckoSerialNumber) }
        AuthApi.logout()
        _session.value = null
        _devices.value = emptyList()
        _readings.value = emptyMap()
    }

    // Devices (persisted locally — the API has no device list endpoint)

    fun addDevice(device: GeckoDevice) {
        if (_devices.value.any { it.geckoSerialNumber == device.geckoSerialNumber }) return
        setDevices(_devices.value + device)
    }

    fun removeDevice(gsn: String) {
        Schedules.cancelAll(getApplication(), gsn)
        setDevices(_devices.value.filterNot { it.geckoSerialNumber == gsn })
    }

    fun renameDevice(gsn: String, name: String) =
        setDevices(_devices.value.map { if (it.geckoSerialNumber == gsn) it.copy(friendlyName = name) else it })

    private fun setDevices(list: List<GeckoDevice>) {
        _devices.value = list
        Store.devices = list
    }

    fun refreshReadings() = viewModelScope.launch {
        _refreshing.value = true
        _devices.value.map { d ->
            async {
                runCatching { DeviceApi.reading(d.geckoSerialNumber) }.onSuccess { r ->
                    _readings.update { it + (d.geckoSerialNumber to r) }
                }
            }
        }.awaitAll()
        _refreshing.value = false
    }

    /** Catch up to whichever schedule should be active now (launch / foreground). */
    fun applyActiveSchedules() = viewModelScope.launch {
        if (_session.value == null) return@launch
        for (d in _devices.value) {
            val mode = Schedules.modeToApplyNow(d.geckoSerialNumber) ?: continue
            runCatching { DeviceApi.setModeTolerant(d.geckoSerialNumber, mode) }
                .onSuccess { Store.updateMode(d.geckoSerialNumber, mode) }
        }
    }
}
