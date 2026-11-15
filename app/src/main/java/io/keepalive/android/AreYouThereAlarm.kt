package io.keepalive.android

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
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
 * The prompt must never be left with neither, so the app only counts as making the sound
 * while the service has said so, or for the few seconds it is given to say so.
 */
object AreYouThereAlarm {

    private const val TAG = "AreYouThereAlarm"

    /** Set on the prompt's notification when the app makes the sound, not the notification. */
    const val EXTRA_APP_OWNED_SOUND = "io.keepalive.android.APP_OWNED_SOUND"

    // how long the service gets to report that the sound is playing. the repost timer
    //  looks again after AppController.ARE_YOU_THERE_ALARM_RETRY_MS, which is longer
    private const val START_TIMEOUT_MS = 4_000L

    enum class Start(val appOwned: Boolean) {
        /** The app makes the sound. */
        PLAYING(true),

        /** The service has been asked for and hasn't reported yet. Look again in seconds. */
        STARTING(true),

        /** The notification has to make the sound: no permission, alarms muted, silent mode. */
        NOT_WANTED(false),

        /**
         * The app should make the sound but Android refused the service for now. It only
         * lets one start from the background for a few seconds after an exact alarm, which
         * the periodic check usually isn't. The repost timer is, and tries again.
         */
        REFUSED(false)
    }

    // set by the service once the sound is playing, in this process
    @Volatile
    var isPlaying = false
        private set

    // when the service was asked for, in elapsed realtime, until it reports. 0 otherwise
    @Volatile
    private var startRequestedAt = 0L

    // whether the service should be playing once it is up. a service that is stopped from
    //  outside before it has gone to the foreground takes the process down, so a stop
    //  that comes while it is on its way is left to the service itself
    @Volatile
    internal var shouldRun = false
        private set

    /**
     * Whether the app is to make the sound and it isn't known yet that it does: the
     * service is on its way up, or the sound is still loading. Worth another look soon.
     */
    val isStarting: Boolean
        get() = shouldRun && !isPlaying

    /**
     * Start the alarm if the app should be making the prompt's sound and it isn't playing
     * yet. Only [Start.PLAYING] and [Start.STARTING] mean the app makes the sound;
     * otherwise the notification has to. Safe to call again and again.
     */
    fun start(context: Context): Start {
        if (!wanted(context)) {
            halt(context)
            return Start.NOT_WANTED
        }

        shouldRun = true

        if (isPlaying) {
            return Start.PLAYING
        }

        val requestedAt = startRequestedAt
        if (requestedAt != 0L) {
            if (SystemClock.elapsedRealtime() - requestedAt < START_TIMEOUT_MS) {
                return Start.STARTING
            }

            // asked for and never heard from. Android can also turn a start down without
            //  saying so, and then nothing would ever play
            Log.w(TAG, "The alarm did not start in time")
            markUnavailable(context)
            startRequestedAt = 0L
            halt(context)
            return Start.NOT_WANTED
        }

        return try {
            val appContext = context.applicationContext
            ContextCompat.startForegroundService(
                appContext, Intent(appContext, AreYouThereAlarmService::class.java)
            )
            startRequestedAt = SystemClock.elapsedRealtime()
            Start.STARTING
        } catch (e: Exception) {
            Log.w(TAG, "The alarm can't be started right now: $e")
            shouldRun = false
            Start.REFUSED
        }
    }

    /**
     * Stop the alarm. [promptOver] is false when the prompt is only paused for a phone
     * call: an alarm that could not be played stays off for the rest of this prompt.
     */
    fun stop(context: Context, promptOver: Boolean = true) {
        halt(context)

        if (!promptOver) {
            return
        }

        // the next prompt may fare better
        try {
            val devicePrefs = getDeviceProtectedPreferences(context)
            if (devicePrefs.contains(PrefKeys.ALARM_UNAVAILABLE)) {
                devicePrefs.edit(commit = true) { remove(PrefKeys.ALARM_UNAVAILABLE) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing the alarm state", e)
        }
    }

    private fun halt(context: Context) {
        shouldRun = false

        // on its way up: it stops itself when it gets there, see shouldRun
        if (startRequestedAt != 0L) {
            return
        }

        try {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, AreYouThereAlarmService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed stopping the alarm", e)
        }
    }

    /**
     * From the service: it has gone to the foreground, or failed to. Either way it is no
     * longer on its way up, and a stop from outside is safe again.
     */
    internal fun onServiceAnswered() {
        startRequestedAt = 0L
    }

    /** From the service: the sound is playing, or has stopped. */
    internal fun onPlaying(playing: Boolean) {
        isPlaying = playing
    }

    /**
     * The alarm could not be played. Kept in storage: a service that fails to go to the
     * foreground can take the process down with it, and the next run must not try again.
     * Kept for this boot only: when the phone shuts down, its media service goes first
     * and every player fails, which says nothing about the alarm after the restart.
     */
    internal fun markUnavailable(context: Context) {
        try {
            getDeviceProtectedPreferences(context).edit(commit = true) {
                putInt(PrefKeys.ALARM_UNAVAILABLE, bootCount(context))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving the alarm state", e)
        }
    }

    private fun isUnavailable(context: Context): Boolean =
        getDeviceProtectedPreferences(context).getInt(PrefKeys.ALARM_UNAVAILABLE, -1) == bootCount(context)

    // how many times the phone has started. API 24+, and the alarm is API 26+
    @SuppressLint("NewApi")
    private fun bootCount(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)

    /** Whether the app should be making the prompt's sound, rather than the notification. */
    internal fun wanted(context: Context): Boolean {
        val reason = whyNot(context) ?: return true
        Log.d(TAG, "The notification makes the sound: $reason")
        return false
    }

    // why the app can't or shouldn't make the sound, or null if it should
    @SuppressLint("NewApi")
    private fun whyNot(context: Context): String? {
        return try {
            // the prompt's notification needs a channel of its own without a sound, and
            //  channels are API 26+. below that the notification keeps making the sound
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return "needs Android 8"
            }

            // nobody could see the prompt or stop the alarm: this user is not the one in
            //  front. the notification's sound isn't played then either (issue #215)
            if (isInBackgroundUser(context)) {
                return "another user is in front"
            }

            if (isUnavailable(context)) {
                return "the alarm failed earlier for this prompt"
            }

            // the service is one that continues an alarm, a type Android only allows with
            //  the exact alarm permission. the repost timer, which watches over the alarm,
            //  needs the same
            if (!AreYouThereRepost.runsOnTime(context)) {
                return "no exact alarm permission"
            }

            // the user has turned the soundless channel off. the prompt would then not be
            //  posted at all
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val silentChannel = notificationManager
                ?.getNotificationChannel(AppController.ARE_YOU_THERE_SILENT_NOTIFICATION_CHANNEL_ID)
            if (silentChannel?.importance == NotificationManager.IMPORTANCE_NONE) {
                return "the soundless channel is turned off"
            }

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return "no audio service"

            // alarms turned all the way down is the user's choice. the notification still
            //  makes its sound then, at its own volume
            if (audioManager.getStreamVolume(AudioManager.STREAM_ALARM) == 0) {
                return "the alarm volume is zero"
            }

            // an alarm rings in silent mode, which is the point, unless the user said no
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL &&
                !getAppSharedPreferences(context).getBoolean(PrefKeys.ALARM_IN_SILENT_MODE, true)
            ) {
                return "silent mode, and the setting is off"
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed checking whether to sound the alarm", e)
            "the check failed"
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
    private var stopped = false

    /**
     * Loads the sound without holding up the caller. [onPlaying] is called once it plays,
     * [onError] if it can't be played or stops playing later. Both come on the calling
     * thread, which has to be one with a looper. Throws if the sound can't even be
     * opened; nothing is left running then.
     */
    fun start(onPlaying: () -> Unit, onError: () -> Unit) {
        try {
            // the address of the setting, not of the sound it points to. before the first
            //  unlock after a restart the sound itself can't be read, and for this address
            //  the player falls back to the copy Android keeps for that case
            val sound = Settings.System.DEFAULT_ALARM_ALERT_URI

            players += newPlayer(sound, null, onError) {
                alsoOnSpeaker(sound)
                vibrate()
                onPlaying()
            }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        stopped = true

        players.forEach {
            try {
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed releasing a player", e)
            }
        }
        players.clear()

        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed stopping the vibration", e)
        }
        vibrator = null
    }

    private val alarmAttributes: AudioAttributes
        get() = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun newPlayer(
        sound: Uri, device: AudioDeviceInfo?, onError: () -> Unit, onPlaying: () -> Unit
    ): MediaPlayer {
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(alarmAttributes)
            player.setDataSource(context, sound)
            player.isLooping = true
            if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                player.setPreferredDevice(device)
            }

            player.setOnPreparedListener {
                if (!stopped) {
                    try {
                        it.start()
                        onPlaying()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed starting a player", e)
                        onError()
                    }
                }
            }

            // also when the sound has been playing for a while: the media service can die
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "A player failed: $what, $extra")
                if (!stopped) {
                    onError()
                }
                true
            }

            // not prepare(): that one waits, and this is the main thread
            player.prepareAsync()
            return player
        } catch (e: Exception) {
            player.release()
            throw e
        }
    }

    // with a headset connected the phone's speaker can stay silent, and the headset may be
    //  lying on a table. this one is an extra: if it fails, the alarm still plays
    private fun alsoOnSpeaker(sound: Uri) {
        try {
            val speaker = speakerBesidesHeadset() ?: return
            var extra: MediaPlayer? = null
            extra = newPlayer(sound, speaker, onError = {
                extra?.let {
                    players.remove(it)
                    it.release()
                }
            }, onPlaying = {})
            players += extra
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing on the speaker as well", e)
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

    // the call with AudioAttributes is deprecated for VibrationAttributes, which is API 33+.
    //  NewApi: the app only plays the alarm from API 26, see AreYouThereAlarm
    @Suppress("DEPRECATION")
    @SuppressLint("NewApi")
    private fun vibrate() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!vibrator.hasVibrator()) {
                return
            }

            // most of a second on, most of a second off, over and over
            val pattern = longArrayOf(0, 800, 800)
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), alarmAttributes)
            this.vibrator = vibrator
        } catch (e: Exception) {
            // the sound is what matters
            Log.e(TAG, "Failed starting the vibration", e)
        }
    }

    private companion object {
        const val TAG = "AlarmSound"
    }
}
