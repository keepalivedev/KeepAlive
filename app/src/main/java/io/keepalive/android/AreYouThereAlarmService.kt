package io.keepalive.android

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Plays the "Are you there?" alarm for as long as the prompt waits for an answer.
 *
 * A foreground service, because the prompt can be up for an hour with the app in the
 * background, where Android would otherwise stop the process and the sound with it. Its
 * type is the one for continuing an alarm, which needs the exact alarm permission.
 *
 * It is stopped from outside when the prompt is answered, paused or over. It also looks
 * for itself, in case nobody told it.
 */
class AreYouThereAlarmService : Service() {

    companion object {
        private const val TAG = "AreYouThereAlarmService"

        private const val STILL_NEEDED_CHECK_MS = 30_000L

        // how long the sound gets to load. AreYouThereAlarm waits about as long for the
        //  service itself
        private const val SOUND_START_TIMEOUT_MS = 4_000L

        // tests swap this for a sound that makes none
        internal var soundFactory: (Service) -> AlarmSound = { AlarmSound(it) }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var sound: AlarmSound? = null

    private val stillNeededCheck = object : Runnable {
        override fun run() {
            if (!AreYouThereRepost.waitingForAnAnswer(this@AreYouThereAlarmService)) {
                stopSelf()
                return
            }
            handler.postDelayed(this, STILL_NEEDED_CHECK_MS)
        }
    }

    private val soundStartTimeout = Runnable {
        if (!AreYouThereAlarm.isPlaying) {
            giveUp(IllegalStateException("The alarm sound did not start playing"))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // has to come first, and within seconds of the start. a service that stops without
        //  it takes the whole process down
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            } else {
                0
            }
            ServiceCompat.startForeground(
                this, AppController.ALARM_SERVICE_NOTIFICATION_ID, createNotification(), type
            )
        } catch (e: Exception) {
            // Android turned the type down, which it does without "Alarms & reminders".
            //  the short type has no conditions, and it lets this stop without a crash
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceCompat.startForeground(
                        this,
                        AppController.ALARM_SERVICE_NOTIFICATION_ID,
                        createNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                    )
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Failed going to the foreground at all", e2)
            }
            giveUp(e)
            return START_NOT_STICKY
        }
        AreYouThereAlarm.onServiceAnswered()

        // the prompt was answered or paused while this was on its way up
        if (!AreYouThereAlarm.shouldRun) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (sound == null) {
            try {
                sound = soundFactory(this).also {
                    it.start(
                        onPlaying = {
                            handler.removeCallbacks(soundStartTimeout)
                            AreYouThereAlarm.onPlaying(true)
                            DebugLogger.d(TAG, getString(R.string.debug_log_alarm_started))
                        },
                        onError = { giveUp(IllegalStateException("The alarm sound failed")) }
                    )
                }
            } catch (e: Exception) {
                sound = null
                giveUp(e)
                return START_NOT_STICKY
            }

            handler.postDelayed(soundStartTimeout, SOUND_START_TIMEOUT_MS)
            handler.postDelayed(stillNeededCheck, STILL_NEEDED_CHECK_MS)
        }

        // not restarted by Android after a kill: the repost timer does that, when the
        //  prompt is still up
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stillNeededCheck)
        handler.removeCallbacks(soundStartTimeout)
        sound?.stop()
        sound = null
        AreYouThereAlarm.onPlaying(false)
        super.onDestroy()
    }

    // the notification has to make the sound after all. the repost timer's work posts the
    //  prompt again to match
    private fun giveUp(e: Exception) {
        DebugLogger.d(TAG, getString(R.string.debug_log_alarm_failed, e.localizedMessage), e)
        AreYouThereAlarm.onServiceAnswered()
        handler.removeCallbacks(soundStartTimeout)
        sound?.stop()
        sound = null
        AreYouThereAlarm.onPlaying(false)
        AreYouThereAlarm.markUnavailable(this)
        stopSelf()
        AreYouThereRepost.repost(applicationContext)
    }

    // every foreground service needs a notification. this one says nothing new, so it
    //  makes no sound and doesn't pop up
    private fun createNotification(): Notification {

        // makes sure the channel exists
        AlertNotificationHelper(this)

        val builder = NotificationCompat.Builder(this, AppController.ALERT_SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.initial_check_notification_title))
            .setContentText(getString(R.string.alarm_service_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .setOngoing(true)

        // answers the prompt, like the prompt's own notification
        try {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    MainActivity.createAlertCheckIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } catch (e: Exception) {
            // before the first unlock after a restart the activity can't be resolved
        }

        return builder.build()
    }
}
