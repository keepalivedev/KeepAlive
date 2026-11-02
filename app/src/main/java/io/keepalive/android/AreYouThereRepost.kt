package io.keepalive.android

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.keepalive.android.receivers.AreYouThereRepostReceiver

/**
 * Brings the "Are you there?" sound back after something stops it.
 *
 * Android plays every notification sound through one shared player, so the next
 * notification with a sound replaces the prompt's repeating sound and nothing
 * restarts it. Marking the sound as a ringtone would protect it on older versions
 * but Android 16 turns that back into a normal notification sound, so instead the
 * prompt is posted again every minute until it's answered. Opening the notification
 * shade silences it the same way, so that now only lasts until the next repost.
 */
object AreYouThereRepost {

    private const val TAG = "AreYouThereRepost"

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            AppController.ARE_YOU_THERE_REPOST_ALARM_REQUEST_CODE,
            Intent(context, AreYouThereRepostReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    @SuppressLint("NewApi")
    fun schedule(context: Context) {

        // active notifications can't be read below API 23, so there'd be no way to
        //  tell that the prompt had been answered
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intervalMs = AppController.ARE_YOU_THERE_REPOST_INTERVAL_MS

        val canScheduleExactAlarms = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()

        // same as the final alarm, this is the only thing Doze won't delay. the prompt is
        //  already showing the alarm icon because of that alarm
        if (canScheduleExactAlarms) {
            val pendingIntent = pendingIntent(context)
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(System.currentTimeMillis() + intervalMs, pendingIntent),
                pendingIntent
            )
        } else {

            // not setAndAllowWhileIdle(), this repeats and would use up the idle alarm quota
            //  the final alarm depends on. a late sound is better than a late alert
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs,
                pendingIntent(context)
            )
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        alarmManager.cancel(pendingIntent(context))
    }

    // under the lock so an answer can't land between the check and the repost and bring
    //  the prompt back after it was answered
    fun repost(context: Context) {
        synchronized(AlarmRecovery.stateLock) {
            try {
                repostLocked(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed reposting the prompt", e)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun repostLocked(context: Context) {
        val stage = getDeviceProtectedPreferences(context).getString(PrefKeys.LAST_ALARM_STAGE, null)
        val enabled = getAppSharedPreferences(context).getBoolean(PrefKeys.ENABLED, false)

        if (stage != "final" || !enabled) {
            Log.d(TAG, "Not waiting for an answer anymore, stage is $stage")
            return
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val prompt = notificationManager.activeNotifications
            .firstOrNull { it.id == AppController.ARE_YOU_THERE_NOTIFICATION_ID }

        if (prompt == null) {
            Log.d(TAG, "The prompt is gone")
            return
        }

        // a full-screen intent would wake the screen every minute, and a lit screen can
        //  look like someone using the device and stop the alert from going out
        val repost = prompt.notification.clone()
        repost.fullScreenIntent = null

        notificationManager.notify(AppController.ARE_YOU_THERE_NOTIFICATION_ID, repost)

        schedule(context)
    }
}
