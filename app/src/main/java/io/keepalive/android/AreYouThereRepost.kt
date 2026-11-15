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

    /**
     * Whether the timer is one Android runs on time. Without the exact alarm permission it
     * is an ordinary alarm: late by design, held back in Doze and under battery saver, and
     * cut off after about ten runs an hour for an app that isn't in active use.
     */
    fun runsOnTime(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        return alarmManager?.canScheduleExactAlarms() == true
    }

    @SuppressLint("NewApi")
    fun schedule(context: Context, intervalMs: Long = AppController.ARE_YOU_THERE_REPOST_INTERVAL_MS) {

        // active notifications can't be read below API 23, so there'd be no way to
        //  tell that the prompt had been answered
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // same as the final alarm, this is the only thing Doze won't delay. the prompt is
        //  already showing the alarm icon because of that alarm
        if (runsOnTime(context)) {
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

                // this timer is all that brings a paused prompt back, so it has to
                //  outlive a run that failed
                try {
                    if (waitingForAnAnswer(context)) {
                        schedule(context)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed setting the next repost", e)
                }
            }
        }
    }

    internal fun waitingForAnAnswer(context: Context): Boolean {
        val stage = getDeviceProtectedPreferences(context).getString(PrefKeys.LAST_ALARM_STAGE, null)
        val enabled = getAppSharedPreferences(context).getBoolean(PrefKeys.ENABLED, false)
        return stage == "final" && enabled
    }

    @SuppressLint("NewApi")
    private fun repostLocked(context: Context) {
        if (!waitingForAnAnswer(context)) {
            Log.d(TAG, "Not waiting for an answer anymore")
            AreYouThereCallPause.stop(context)
            AreYouThereAlarm.stop(context)
            return
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val prompt = notificationManager.activeNotifications
            .firstOrNull { it.id == AppController.ARE_YOU_THERE_NOTIFICATION_ID }
            ?.notification

        val paused = AreYouThereCallPause.isPaused(context)

        // a paused prompt has no notification, it is posted again when the call is over
        if (prompt == null && !paused) {
            Log.d(TAG, "The prompt is gone")
            AreYouThereCallPause.stop(context)
            AreYouThereAlarm.stop(context)
            return
        }

        // the final check would count the unlock as activity anyway, but until then the
        //  prompt and its sound would carry on for someone who is plainly there
        val postedAt = AreYouThereCallPause.promptPostedAt(context) ?: prompt?.`when`
        if (postedAt != null && answeredByUnlock(context, postedAt)) {
            DebugLogger.d(TAG, context.getString(R.string.debug_log_prompt_answered_by_unlock))
            AcknowledgeAreYouThere.acknowledge(context)
            return
        }

        var resumed = false

        when {
            // nothing to repost, the prompt stays out of the way until the call ends
            AreYouThereCallPause.shouldStepAside(context) ->
                AreYouThereCallPause.pause(context, prompt)

            paused -> {
                AreYouThereCallPause.resume(context, replace = prompt != null)
                resumed = true
            }

            prompt != null -> {
                // starts the alarm again if it died, or for the first time if it couldn't
                //  be started when the prompt went up
                val appOwnedSound = AreYouThereAlarm.start(context).appOwned

                if (appOwnedSound != prompt.extras.getBoolean(AreYouThereAlarm.EXTRA_APP_OWNED_SOUND)) {
                    // who makes the sound has changed, so the notification that is posted
                    //  is the wrong one of the two. as an update: the full-screen prompt
                    //  doesn't have to open again
                    val devicePrefs = getDeviceProtectedPreferences(context)
                    AlarmRecovery.repostAreYouThere(
                        context,
                        getAppSharedPreferences(context),
                        devicePrefs,
                        devicePrefs.getLong(PrefKeys.NEXT_ALARM_TIMESTAMP, 0L),
                        overwrite = true
                    )
                } else if (!appOwnedSound) {
                    // a full-screen intent would wake the screen every minute, and a lit
                    //  screen can look like someone using the device and stop the alert
                    //  from going out
                    val repost = prompt.clone()
                    repost.fullScreenIntent = null

                    notificationManager.notify(AppController.ARE_YOU_THERE_NOTIFICATION_ID, repost)
                }
            }
        }

        when {
            // android stops this process from running a while into a call, so the end of
            //  the call is only seen here. look more often until then
            AreYouThereCallPause.isPaused(context) ->
                schedule(context, AppController.ARE_YOU_THERE_PAUSED_CHECK_INTERVAL_MS)

            // the alarm was asked for and has yet to say that it plays. or the prompt
            //  just came back after a call, from code that was not allowed to start the
            //  alarm, as this timer is
            AreYouThereAlarm.isStarting ||
                (resumed && AreYouThereAlarm.wanted(context) && !AreYouThereAlarm.isPlaying) ->
                schedule(context, AppController.ARE_YOU_THERE_ALARM_RETRY_MS)

            else -> schedule(context)
        }
        AreYouThereCallPause.watch(context)
    }

    // whether the phone was unlocked after the prompt went up at [promptPostedAt]. with
    //  apps to monitor it is using those apps that counts as activity, not the unlock, so
    //  the prompt is left for the final check to decide
    private fun answeredByUnlock(context: Context, promptPostedAt: Long): Boolean {

        // usage events can't be read before the first unlock
        if (!isUserUnlocked(context)) {
            return false
        }

        val monitoredApps: MutableList<MonitoredAppDetails> =
            loadJSONSharedPreference(getAppSharedPreferences(context), PrefKeys.APPS_TO_MONITOR)

        return monitoredApps.isEmpty() && wasUnlockedSince(context, promptPostedAt)
    }
}
