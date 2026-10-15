package com.bettergecko.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.encodeToString

import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * All local persistence. SharedPreferences is Android's UserDefaults equivalent; keys match the
 * iOS app so the data model is the same on both platforms.
 */
object Store {
    private lateinit var prefs: SharedPreferences
    val json = AppJson

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bettergecko", Context.MODE_PRIVATE)
    }

    // Session
    var token: String?
        get() = prefs.getString("gg_token", null)
        set(v) = prefs.edit().putString("gg_token", v).apply()
    var deviceID: String?
        get() = prefs.getString("gg_deviceid", null)
        set(v) = prefs.edit().putString("gg_deviceid", v).apply()

    fun clearSession() = prefs.edit().remove("gg_token").remove("gg_deviceid").apply()

    /** Simulated device instead of the GeyserGecko service; see [Demo]. */
    var demoMode: Boolean
        get() = prefs.getBoolean("demo_mode", false)
        set(v) = prefs.edit().putBoolean("demo_mode", v).apply()

    // Devices
    var devices: List<GeckoDevice>
        get() = decode("gg_manual_devices") ?: emptyList()
        set(v) = encode("gg_manual_devices", v)

    fun address(gsn: String): String = prefs.getString("device_address_$gsn", "") ?: ""
    fun setAddress(gsn: String, value: String) = prefs.edit().putString("device_address_$gsn", value).apply()

    // Settings cache
    fun settings(gsn: String): PersistedSettings? = decode("device_settings_$gsn")
    fun saveSettings(gsn: String, s: PersistedSettings) = encode("device_settings_$gsn", s)
    fun updateMode(gsn: String, mode: String) = saveSettings(gsn, (settings(gsn) ?: PersistedSettings()).copy(mode = mode))

    // Cost
    const val DEFAULT_RATE = 4.0
    const val DEFAULT_ELEMENT_KW = 4.0
    var rate: Double
        get() = prefs.getFloat("electricity_rate_zar_per_kwh", DEFAULT_RATE.toFloat()).toDouble()
        set(v) = prefs.edit().putFloat("electricity_rate_zar_per_kwh", v.toFloat()).apply()
    fun elementKW(gsn: String): Double = prefs.getFloat("element_kw_$gsn", DEFAULT_ELEMENT_KW.toFloat()).toDouble()
    fun setElementKW(gsn: String, v: Double) = prefs.edit().putFloat("element_kw_$gsn", v.toFloat()).apply()

    // Ledger
    fun ledger(gsn: String): ACUsageLedger = decode("ac_usage_ledger_$gsn") ?: ACUsageLedger()
    fun saveLedger(gsn: String, l: ACUsageLedger) = encode("ac_usage_ledger_$gsn", l)

    // Schedules
    fun schedules(gsn: String): List<ModeSchedule> = ScheduleMath.sorted(decode("mode_schedules_$gsn") ?: emptyList())
    fun saveSchedules(gsn: String, list: List<ModeSchedule>) = encode("mode_schedules_$gsn", ScheduleMath.sorted(list))
    fun appliedStamp(gsn: String): String? = prefs.getString("schedule_applied_$gsn", null)
    fun setAppliedStamp(gsn: String, v: String) = prefs.edit().putString("schedule_applied_$gsn", v).apply()

    /** Wipes everything on sign-out. */
    fun clearAll() = prefs.edit().clear().apply()

    private inline fun <reified T> decode(key: String): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() }

    private inline fun <reified T> encode(key: String, value: T) =
        prefs.edit().putString(key, json.encodeToString(value)).apply()
}

/**
 * Credential storage — the Keychain equivalent. Values are AES-GCM encrypted with a key that
 * lives in the Android Keystore (hardware-backed where available and never exportable), and the
 * ciphertext is kept in a separate SharedPreferences file.
 */
object SecureStore {
    private const val ALIAS = "bettergecko_creds"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("bettergecko_secure", Context.MODE_PRIVATE)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = cipher.doFinal(value.toByteArray())
        val blob = Base64.encodeToString(cipher.iv + enc, Base64.NO_WRAP)
        prefs.edit().putString(name, blob).apply()
    }

    fun load(name: String): String? = runCatching {
        val bytes = Base64.decode(prefs.getString(name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    fun clear() = prefs.edit().clear().apply()
}
