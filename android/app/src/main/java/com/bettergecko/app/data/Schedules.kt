package com.bettergecko.app.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bettergecko.app.MainActivity
import com.bettergecko.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Mode schedules. Each enabled rule gets a one-shot AlarmManager alarm at its next occurrence;
 * when it fires, [ScheduleReceiver] applies the mode in the background, posts a notification,
 * and arms the next day's alarm. (iOS can only post a "tap to apply" notification.)
 */
object Schedules {
    const val CHANNEL_ID = "mode_schedules"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL_ID, "Mode schedules", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = "Shown when a scheduled mode change is applied"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    fun save(context: Context, gsn: String, list: List<ModeSchedule>) {
        Store.schedules(gsn).forEach { cancel(context, gsn, it) }
        Store.saveSchedules(gsn, list)
        list.filter { it.enabled }.forEach { arm(context, gsn, it) }
    }

    /** Re-arms every device's alarms (app start, boot, exact-alarm permission change). */
    fun rearmAll(context: Context) {
        for (d in Store.devices) {
            Store.schedules(d.geckoSerialNumber).forEach {
                cancel(context, d.geckoSerialNumber, it)
                if (it.enabled) arm(context, d.geckoSerialNumber, it)
            }
        }
    }

    fun cancelAll(context: Context, gsn: String) = Store.schedules(gsn).forEach { cancel(context, gsn, it) }

    /** Exact alarms need user consent on Android 12+; without it alarms may fire a few minutes late. */
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun arm(context: Context, gsn: String, s: ModeSchedule) {
        val am = context.getSystemService(AlarmManager::class.java)
        val at = ScheduleMath.nextOccurrence(s, ScheduleMath.now()).toInstant().toEpochMilli()
        val pi = pendingIntent(context, gsn, s)
        if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun cancel(context: Context, gsn: String, s: ModeSchedule) =
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context, gsn, s))

    private fun pendingIntent(context: Context, gsn: String, s: ModeSchedule): PendingIntent {
        val intent = Intent(context, ScheduleReceiver::class.java)
            .setAction("com.bettergecko.app.SCHEDULE.$gsn.${s.id}")   // unique per rule
            .putExtra("gsn", gsn).putExtra("id", s.id)
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Applies whichever schedule should be active now, unless that occurrence was already applied. */
    fun modeToApplyNow(gsn: String): String? {
        val (mode, occ) = ScheduleMath.activeOccurrence(Store.schedules(gsn), ScheduleMath.now()) ?: return null
        val stamp = occ.toInstant().toString()
        if (Store.appliedStamp(gsn) == stamp) return null
        Store.setAppliedStamp(gsn, stamp)
        return mode
    }

    fun notify(context: Context, gsn: String, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= 33) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(gsn.hashCode(), n)
    }
}

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val gsn = intent.getStringExtra("gsn") ?: return
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()   // keeps the process alive (~10 s) for the network call
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = Store.schedules(gsn).firstOrNull { it.id == id && it.enabled } ?: return@launch
                Schedules.arm(context, gsn, s)   // tomorrow's run
                val name = Store.devices.firstOrNull { it.geckoSerialNumber == gsn }?.displayName ?: gsn
                Schedules.modeToApplyNow(gsn)    // stamp this occurrence so foreground catch-up skips it
                val result = runCatching { DeviceApi.setModeTolerant(gsn, s.mode) }
                if (result.isSuccess) {
                    Store.updateMode(gsn, s.mode)
                    Schedules.notify(context, gsn, name, "Switched to ${s.modeLabel}")
                } else {
                    Schedules.notify(context, gsn, name,
                        "Couldn't switch to ${s.modeLabel}: ${result.exceptionOrNull()?.message}")
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Schedules.rearmAll(context)
}
