package io.keepalive.android

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * The "Are you there?" prompt's own alarm sound.
 *
 * As a notification sound the prompt is silent in silent mode, plays only in a headset when
 * one is connected, and stops for the volume keys, the notification shade and the next
 * notification. So where it can, the app plays the sound itself, as an alarm, from
 * [AreYouThereAlarmService], and posts the prompt's notification without a sound. Where
 * it can't, the notification makes the sound as before.
 *
 * [start] says which of the two it is, and whoever posts the prompt posts it to match.
 */
object AreYouThereAlarm {

    private const val TAG = "AreYouThereAlarm"

    /** Set on the prompt's notification when the app makes the sound, not the notification. */
    const val EXTRA_APP_OWNED_SOUND = "io.keepalive.android.APP_OWNED_SOUND"

    // set by the service, in this process
    @Volatile
    var isPlaying = false
        internal set

    enum class Start {
        /** The app makes the sound. */
        PLAYING,

        /** The notification has to make the sound: no permission, alarms muted, silent mode. */
        NOT_WANTED,

        /**
         * The app should make the sound but Android refused the service for now. It only
         * lets one start from the background for a few seconds after an exact alarm, which
         * the periodic check usually isn't. The repost timer is, and tries again.
         */
        REFUSED
    }

    /**
     * Start the alarm if the app should be making the prompt's sound and it isn't playing
     * yet. Anything but [Start.PLAYING] means the notification has to make the sound.
     * Safe to call again and again.
     */
    fun start(context: Context): Start {
        if (!wanted(context)) {
            stopService(context)
            return Start.NOT_WANTED
        }

        if (isPlaying) {
            return Start.PLAYING
        }

        return try {
            val appContext = context.applicationContext
            ContextCompat.startForegroundService(
                appContext, Intent(appContext, AreYouThereAlarmService::class.java)
            )
            Start.PLAYING
        } catch (e: Exception) {
            Log.w(TAG, "The alarm can't be started right now: $e")
            Start.REFUSED
        }
    }

    /** The prompt is over, or paused: stop the alarm. */
    fun stop(context: Context) {
        stopService(context)

        // the next prompt may fare better
        try {
            val devicePrefs = getDeviceProtectedPreferences(context)
            if (devicePrefs.getBoolean(PrefKeys.ALARM_UNAVAILABLE, false)) {
                devicePrefs.edit(commit = true) { remove(PrefKeys.ALARM_UNAVAILABLE) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing the alarm state", e)
        }
    }

    private fun stopService(context: Context) {
        try {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, AreYouThereAlarmService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed stopping the alarm", e)
        }
    }

    /**
     * The service could not get going. Kept in storage: a service that fails to go to the
     * foreground takes the process down with it, and the next run must not try again.
     */
    internal fun markUnavailable(context: Context) {
        try {
            getDeviceProtectedPreferences(context).edit(commit = true) {
                putBoolean(PrefKeys.ALARM_UNAVAILABLE, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving the alarm state", e)
        }
    }

    // whether the user has switched on "Alarms & reminders" for the app, which is what
    //  Android looks at when the service starts (from API 34, where the service type is
    //  enforced). canScheduleExactAlarms() isn't the same question: it also says yes for
    //  an app that is exempt from battery optimisation
    private fun exactAlarmsGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }

        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val op = AppOpsManager.permissionToOp(Manifest.permission.SCHEDULE_EXACT_ALARM) ?: return false

        return appOps.unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    // whether the app should be making the sound, rather than the notification
    private fun wanted(context: Context): Boolean {
        return try {
            if (getDeviceProtectedPreferences(context).getBoolean(PrefKeys.ALARM_UNAVAILABLE, false)) {
                return false
            }

            // the service is one that continues an alarm, a type Android only allows with
            //  the exact alarm permission
            if (!AreYouThereRepost.runsOnTime(context) || !exactAlarmsGranted(context)) {
                return false
            }

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return false

            // alarms turned all the way down is the user's choice. the notification still
            //  makes its sound then, at its own volume
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                return false
            }

            // an alarm rings in silent mode, which is the point, unless the user said no
            audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL ||
                getAppSharedPreferences(context).getBoolean(PrefKeys.ALARM_IN_SILENT_MODE, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed checking whether to sound the alarm", e)
            false
        }
    }
}

/**
 * The sound and vibration of the alarm: the phone's alarm sound on the alarm stream, on a
 * loop, until [stop].
 */
internal class AlarmSound(private val context: Context) {

    private val players = mutableListOf<MediaPlayer>()
    private var vibrator: Vibrator? = null

    /** Throws if the sound can't be played; nothing is left running then. */
    fun start() {
        try {
            val sound = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
                ?: Settings.System.DEFAULT_ALARM_ALERT_URI

            players += newPlayer(sound, null)

            // with a headset connected the phone's speaker can stay silent, and the
            //  headset may be lying on a table
            speakerBesidesHeadset()?.let { speaker ->
                try {
                    players += newPlayer(sound, speaker)
                } catch (e: Exception) {
                    Log.e("AlarmSound", "Failed playing on the speaker as well", e)
                }
            }

            vibrate()
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        players.forEach {
            try {
                it.release()
            } catch (e: Exception) {
                Log.e("AlarmSound", "Failed releasing a player", e)
            }
        }
        players.clear()

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e("AlarmSound", "Failed stopping the vibration", e)
        }
        vibrator = null
    }

    private val alarmAttributes: AudioAttributes
        get() = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun newPlayer(sound: Uri, device: AudioDeviceInfo?): MediaPlayer {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(alarmAttributes)
            player.setDataSource(context, sound)
            player.isLooping = true
            if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                player.setPreferredDevice(device)
            }
            player.prepare()
            player.start()
            return player
        } catch (e: Exception) {
            player.release()
            throw e
        }
    }

    // the built-in speaker, if sound would otherwise go to a headset. a player can only be
    //  pointed at a device from API 28
    private fun speakerBesidesHeadset(): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return null
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return null
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val headsetTypes = mutableSetOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            headsetTypes += AudioDeviceInfo.TYPE_BLE_HEADSET
        }

        if (outputs.none { it.type in headsetTypes }) {
            return null
        }
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    // the two-argument calls are deprecated for VibrationAttributes, which is API 33+
    @Suppress("DEPRECATION")
    private fun vibrate() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) {
                return
            }

            // most of a second on, most of a second off, over and over
            val pattern = longArrayOf(0, 800, 800)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), alarmAttributes)
            } else {
                vibrator.vibrate(pattern, 0, alarmAttributes)
            }
            this.vibrator = vibrator
        } catch (e: Exception) {
            // the sound is what matters
            Log.e("AlarmSound", "Failed starting the vibration", e)
        }
    }
}
