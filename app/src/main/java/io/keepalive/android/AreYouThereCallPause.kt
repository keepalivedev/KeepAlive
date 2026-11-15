package io.keepalive.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit

/**
 * Keeps the "Are you there?" prompt out of the way of phone calls.
 *
 * The full-screen prompt covers the lock screen, so Android shows an incoming call as a
 * banner only, and on Android 16 the banner's Answer button then asks for the PIN. Once
 * the phone is unlocked the overlay sits on top of the call screen and blocks its buttons.
 * So while a call is ringing or in progress the whole prompt is taken down, notification
 * included, and it is posted again when the call ends. The notification can't stay: a
 * silent update stops its repeating sound but not its repeating vibration, and only
 * cancelling it stops both.
 *
 * The final alarm is not touched, the alert still goes out on time. So the prompt must
 * be sure to come back first: it does not step aside in the last minutes before the
 * alert (see [KEEP_CLEAR_BEFORE_ALERT_MS]), and not at all where the timer that brings
 * it back can't be relied on (see [AreYouThereRepost.runsOnTime]).
 *
 * [AreYouThereRepost] decides what to do, on its one-minute timer. The timer alone would
 * leave the prompt in the way for up to a minute, so [watch] also looks at the call state
 * every second for as long as this process lives and runs that timer's work early when
 * the state changes.
 */
object AreYouThereCallPause {

    private const val TAG = "AreYouThereCallPause"

    private const val POLL_INTERVAL_MS = 1_000L

    // Both ends of a call need a moment before the prompt moves.
    //  When a call starts to ring with the prompt over the lock screen, it first shows as
    //  a banner. Android turns a banner that is on screen into the full call screen when
    //  the lock screen comes back, but a call whose banner isn't up yet is left as a line
    //  in the notification list. So the prompt stays put until the banner has appeared.
    //  When the call ends, the call screen is still up for a moment, and a prompt posted
    //  under it would not get its own full screen back.
    private const val SETTLE_MS = 2_000L

    // A call can last longer than the time left to answer. Without this the alert would
    //  go out from behind the call, with the prompt hidden and silent to the end. In the
    //  last minutes the prompt is back, call or not, the way it was before it learned to
    //  step aside.
    private const val KEEP_CLEAR_BEFORE_ALERT_MS = 5 * 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    // only touched on the main thread
    private var poll: Runnable? = null

    // when the current call was first seen ringing, and when it was first seen to be
    //  over, in elapsed realtime. 0 when that isn't the case
    private var ringingSince = 0L
    private var endedSince = 0L

    private enum class CallState { NONE, RINGING, ACTIVE }

    /**
     * The audio mode needs no permission and also covers calls in other apps, but it
     * doesn't change for a call that rings with the ringer on silent or vibrate. The
     * telephony call state does show that one, where it can be read: always below API 31,
     * and with READ_PHONE_STATE from there on.
     */
    private fun callState(context: Context): CallState {

        // the prompt can't be read back below API 23 (see AreYouThereRepost.schedule()),
        //  so there it could be taken away but never brought back
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return CallState.NONE
        }

        // once this process is stopped from running, the repost timer is all that brings
        //  the prompt back. a timer that Android may hold back for an hour is not enough
        //  to take the prompt down on
        if (!AreYouThereRepost.runsOnTime(context)) {
            return CallState.NONE
        }

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            when (audioManager?.mode ?: AudioManager.MODE_NORMAL) {
                AudioManager.MODE_NORMAL -> {}
                AudioManager.MODE_RINGTONE -> return CallState.RINGING
                else -> return CallState.ACTIVE
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return CallState.NONE
            }

            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

            // deprecated for the per-subscription call, which is API 31+ and has the same
            //  permission rule
            @Suppress("DEPRECATION")
            when (telephonyManager?.callState ?: TelephonyManager.CALL_STATE_IDLE) {
                TelephonyManager.CALL_STATE_IDLE -> CallState.NONE
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                else -> CallState.ACTIVE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading the call state", e)
            CallState.NONE
        }
    }

    /**
     * Whether the prompt has to be out of the way right now. That starts a moment after a
     * call begins to ring and ends a moment after the call is over, see [SETTLE_MS], or
     * when the alert is close, see [KEEP_CLEAR_BEFORE_ALERT_MS].
     */
    @Synchronized
    fun shouldStepAside(context: Context): Boolean {
        val deadline = getDeviceProtectedPreferences(context)
            .getLong(PrefKeys.NEXT_ALARM_TIMESTAMP, 0L)
        if (deadline - System.currentTimeMillis() < KEEP_CLEAR_BEFORE_ALERT_MS) {
            return false
        }

        val now = SystemClock.elapsedRealtime()
        val paused = isPaused(context)

        return when (callState(context)) {
            CallState.ACTIVE -> {
                ringingSince = 0L
                endedSince = 0L
                true
            }
            CallState.RINGING -> {
                endedSince = 0L
                if (ringingSince == 0L) {
                    ringingSince = now
                }
                paused || now - ringingSince >= SETTLE_MS
            }
            CallState.NONE -> {
                ringingSince = 0L
                if (!paused) {
                    endedSince = 0L
                    false
                } else {
                    if (endedSince == 0L) {
                        endedSince = now
                    }
                    now - endedSince < SETTLE_MS
                }
            }
        }
    }

    // kept in storage because the process can be gone by the time the call ends
    fun isPaused(context: Context): Boolean =
        getDeviceProtectedPreferences(context).getBoolean(PrefKeys.PROMPT_PAUSED_FOR_CALL, false)

    /**
     * When the prompt first went up, if it has been paused since, else null. The copy that
     * is posted after a call carries a later time, and an unlock from during the call must
     * still count as the answer.
     */
    fun promptPostedAt(context: Context): Long? =
        getDeviceProtectedPreferences(context).getLong(PrefKeys.PROMPT_POSTED_AT, 0L)
            .takeIf { it > 0L }

    /**
     * Take the prompt down: off the screen, and its sound and vibration stopped. [prompt]
     * is the posted notification, or null when it is already down.
     */
    fun pause(context: Context, prompt: Notification?) {
        if (!isPaused(context)) {
            DebugLogger.d(TAG, context.getString(R.string.debug_log_prompt_paused_for_call))

            val firstPause = promptPostedAt(context) == null
            getDeviceProtectedPreferences(context).edit(commit = true) {
                putBoolean(PrefKeys.PROMPT_PAUSED_FOR_CALL, true)
                if (firstPause && prompt != null) {
                    putLong(PrefKeys.PROMPT_POSTED_AT, prompt.`when`)
                }
            }
        }

        AreYouThereOverlay.dismiss(context)
        AreYouThereActivity.finishActive()
        AreYouThereAlarm.stop(context)

        if (prompt != null) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(AppController.ARE_YOU_THERE_NOTIFICATION_ID)
        }
    }

    /**
     * Show the prompt again, the way it was first shown, with the time that is left now.
     * [replace] says that a notification was posted during the pause. It is taken down
     * first: an update to a notification that is already posted never opens the
     * full-screen prompt again, a new one does.
     */
    fun resume(context: Context, replace: Boolean) {
        DebugLogger.d(TAG, context.getString(R.string.debug_log_prompt_back_after_call))

        // not when there is nothing to take down: some Android versions run a cancel
        //  late, and it would then remove the prompt that is posted right after it
        if (replace) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(AppController.ARE_YOU_THERE_NOTIFICATION_ID)
        }

        val devicePrefs = getDeviceProtectedPreferences(context)
        AlarmRecovery.repostAreYouThere(
            context,
            getAppSharedPreferences(context),
            devicePrefs,
            devicePrefs.getLong(PrefKeys.NEXT_ALARM_TIMESTAMP, 0L),
            overwrite = true
        )

        // only now: if this is cut short before the prompt is posted, this is what tells
        //  the next run of the timer to post it
        devicePrefs.edit(commit = true) { putBoolean(PrefKeys.PROMPT_PAUSED_FOR_CALL, false) }
    }

    /** Start looking at the call state while a prompt waits for an answer. Safe from any thread. */
    fun watch(context: Context) {

        // nothing to look for, see callState()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val appContext = context.applicationContext

        mainHandler.post {
            poll?.let { mainHandler.removeCallbacks(it) }

            val runnable = object : Runnable {
                override fun run() {
                    try {
                        // under the lock so a prompt that is being posted right now, before
                        //  its final alarm is set, isn't taken for one that is over
                        val waiting = synchronized(AlarmRecovery.stateLock) {
                            getDeviceProtectedPreferences(appContext)
                                .getString(PrefKeys.LAST_ALARM_STAGE, null) == "final" &&
                                getAppSharedPreferences(appContext).getBoolean(PrefKeys.ENABLED, false)
                        }
                        if (!waiting) {
                            poll = null
                            return
                        }

                        if (shouldStepAside(appContext) != isPaused(appContext)) {
                            AreYouThereRepost.repost(appContext)
                        }
                    } catch (e: Exception) {
                        // the one-minute timer starts this again
                        Log.e(TAG, "Failed checking the call state", e)
                        poll = null
                        return
                    }

                    if (poll === this) {
                        mainHandler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }
            poll = runnable
            runnable.run()
        }
    }

    /** The prompt is over: stop looking and forget any pause. Safe from any thread. */
    fun stop(context: Context) {
        mainHandler.post {
            poll?.let { mainHandler.removeCallbacks(it) }
            poll = null
        }

        forgetPause(context)
    }

    /**
     * A prompt that is just going up starts out on screen. A pause can be left on record
     * by a process that was killed or a phone that was restarted during a call, and it
     * would keep the overlay and the full-screen prompt away. The watcher is left alone:
     * the prompt may already be posted, and then nothing would start it again.
     */
    fun forgetPause(context: Context) {
        try {
            synchronized(this) {
                ringingSince = 0L
                endedSince = 0L
            }

            if (isPaused(context) || promptPostedAt(context) != null) {
                getDeviceProtectedPreferences(context).edit(commit = true) {
                    putBoolean(PrefKeys.PROMPT_PAUSED_FOR_CALL, false)
                    remove(PrefKeys.PROMPT_POSTED_AT)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing the paused state", e)
        }
    }
}
