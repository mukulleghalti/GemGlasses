package com.geno.veyra.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.geno.veyra.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One scheduled timer or reminder. */
@Serializable
data class ScheduledAlert(
    val id: String,
    /** "timer" or "reminder". */
    val kind: String,
    val label: String,
    val triggerAtMillis: Long,
)

private val Context.alertsDataStore by preferencesDataStore(
    name = "veyra_alerts",
)

/**
 * Schedules timers and reminders with AlarmManager.setAlarmClock — exact,
 * no SCHEDULE_EXACT_ALARM permission needed, and the system shows an alarm
 * icon while one is pending. Alerts are persisted in DataStore so they
 * survive process death (a reboot clears them; documented, not handled).
 *
 * When an alert fires, [AlertReceiver] speaks the label and posts a
 * notification.
 */
@Singleton
class AlertScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val alertsKey = stringPreferencesKey("alerts_json")

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    private suspend fun readAlerts(): List<ScheduledAlert> =
        runCatching {
            context.alertsDataStore.data
                .map { prefs ->
                    prefs[alertsKey]?.let {
                        runCatching {
                            json.decodeFromString<List<ScheduledAlert>>(it)
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
                .first()
        }.getOrDefault(emptyList())

    private suspend fun writeAlerts(alerts: List<ScheduledAlert>) {
        runCatching {
            context.alertsDataStore.edit { prefs ->
                prefs[alertsKey] = json.encodeToString(alerts)
            }
        }.onFailure {
            Log.w(TAG, "persisting alerts failed", it)
        }
    }

    private fun receiverIntent(alert: ScheduledAlert): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java).apply {
            putExtra(AlertReceiver.EXTRA_ID, alert.id)
            putExtra(AlertReceiver.EXTRA_LABEL, alert.label)
            putExtra(AlertReceiver.EXTRA_KIND, alert.kind)
        }
        return PendingIntent.getBroadcast(
            context,
            alert.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Schedules an alert. Returns the alert id. Throws
     * IllegalArgumentException when [triggerAtMillis] is not in the future.
     */
    suspend fun schedule(
        kind: String,
        triggerAtMillis: Long,
        label: String,
    ): String {
        require(triggerAtMillis > System.currentTimeMillis()) {
            "trigger time must be in the future"
        }

        val alert = ScheduledAlert(
            id = UUID.randomUUID().toString(),
            kind = kind,
            label = label.ifBlank { defaultLabel(kind) },
            triggerAtMillis = triggerAtMillis,
        )

        val showIntent = PendingIntent.getActivity(
            context,
            alert.id.hashCode(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE,
        )

        alarmManager()?.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent),
            receiverIntent(alert),
        )

        writeAlerts(readAlerts() + alert)

        Log.i(
            TAG,
            "scheduled $kind ${alert.id} at $triggerAtMillis " +
                "label=${alert.label}",
        )

        return alert.id
    }

    /** Cancels a scheduled alert. Returns false when the id was unknown. */
    suspend fun cancel(id: String): Boolean {
        val alerts = readAlerts()
        val alert = alerts.firstOrNull { it.id == id } ?: return false

        alarmManager()?.cancel(receiverIntent(alert))
        writeAlerts(alerts - alert)

        Log.i(TAG, "cancelled alert $id")

        return true
    }

    /** Removes a fired alert from the store without touching AlarmManager. */
    suspend fun removeFired(id: String) {
        val alerts = readAlerts()
        if (alerts.any { it.id == id }) {
            writeAlerts(alerts.filterNot { it.id == id })
        }
    }

    suspend fun list(): List<ScheduledAlert> = readAlerts()

    private fun defaultLabel(kind: String): String =
        if (kind == "timer") "Timer done" else "Reminder"

    private companion object {
        const val TAG = "AlertScheduler"
    }
}
