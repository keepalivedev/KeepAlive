package io.keepalive.android

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.lang.ref.WeakReference

/**
 * Full-screen "Are you there?" prompt for the locked / screen-off case.
 *
 * Launched by the system via the notification's full-screen intent (see
 * [AlertNotificationHelper]) — the same mechanism alarm clocks and incoming
 * calls use. App overlay windows are layered *below* the keyguard, so the
 * [AreYouThereOverlay] is invisible while the device is locked (issue #182);
 * an activity with showWhenLocked is the only supported way to draw over it.
 * The keyguard itself stays in place — this is drawn on top of it, and
 * tapping "I'm OK" counts as presence without requiring an unlock, exactly
 * like dismissing an alarm clock.
 *
 * Uses the same layout as the overlay so the two surfaces look identical.
 */
class AreYouThereActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MESSAGE = "AreYouThereMessage"

        private const val TAG = "AreYouThereActivity"

        // the currently-showing instance, if any. weak so a finished activity
        //  can't leak; only used to close the prompt when it is acknowledged
        //  elsewhere or the final alert fires
        private var active: WeakReference<AreYouThereActivity>? = null

        /** Close the prompt if it is showing. Safe to call from any thread. */
        fun finishActive() {
            Handler(Looper.getMainLooper()).post {
                active?.get()?.takeIf { !it.isFinishing }?.let {
                    Log.d(TAG, "Closing active full-screen prompt")
                    it.finish()
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // show over the keyguard and turn the screen on. the manifest attributes
        //  cover API 27+; these calls make the intent explicit and the window
        //  flags cover API < 27
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // same layout as the over-other-apps overlay so the two surfaces match
        setContentView(R.layout.overlay_are_you_there)

        findViewById<TextView>(R.id.textAreYouThereTitle).text =
            getString(R.string.initial_check_notification_title)

        intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { it.isNotBlank() }?.let {
            findViewById<TextView>(R.id.textAreYouThereMessage).text = it
        }

        findViewById<Button>(R.id.buttonImOk).setOnClickListener {
            DebugLogger.d(TAG, getString(R.string.debug_log_full_screen_prompt_acknowledged))

            // cancels the notification, resets monitoring, and (via
            //  finishActive) would close this activity — finish directly anyway
            AcknowledgeAreYouThere.acknowledge(applicationContext)
            finish()
        }

        active = WeakReference(this)

        DebugLogger.d(TAG, getString(R.string.debug_log_full_screen_prompt_shown))

        startCountdown()
    }

    // count down to the final alarm's actual scheduled time. when it is reached
    //  the alert fires and this prompt is no longer actionable, so close it
    //  (the alert pipeline also calls finishActive() as a belt-and-braces)
    private fun startCountdown() {
        val countdownText = findViewById<TextView>(R.id.textAreYouThereCountdown)
        val alarmTimestamp = getAppSharedPreferences(this)
            .getLong(PrefKeys.NEXT_ALARM_TIMESTAMP, 0L)

        val runnable = object : Runnable {
            override fun run() {
                val remainingMs = alarmTimestamp - System.currentTimeMillis()
                if (alarmTimestamp <= 0L || remainingMs <= 0) {
                    finish()
                    return
                }
                val totalSeconds = remainingMs / 1000
                countdownText.text = getString(
                    R.string.overlay_countdown_format,
                    totalSeconds / 60,
                    totalSeconds % 60
                )
                mainHandler.postDelayed(this, 1000L)
            }
        }
        countdownRunnable = runnable
        mainHandler.post(runnable)
    }

    override fun onDestroy() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        if (active?.get() === this) {
            active = null
        }
        super.onDestroy()
    }
}
