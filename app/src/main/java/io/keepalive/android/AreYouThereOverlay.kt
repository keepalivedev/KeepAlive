package io.keepalive.android

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * Manages the over-other-apps full-screen "Are you there?" overlay.
 *
 * Draws the overlay directly with [WindowManager.addView] under the app's
 * SYSTEM_ALERT_WINDOW grant. This deliberately does NOT use a foreground
 * service: adding an overlay window is governed purely by SYSTEM_ALERT_WINDOW
 * and works from a background context (e.g. the AlarmReceiver), whereas
 * starting a foreground service from the background is blocked on Android 12+
 * unless the app currently holds an exemption. When an alarm fires after the
 * ~10s allow-while-idle window has elapsed there is no exemption, so the old
 * service-based overlay was silently denied on release builds — this avoids
 * that path entirely.
 *
 * State (the View/WindowManager/countdown/wake lock) is held statically. Because
 * everything is rooted at the application Context — never an Activity — there is
 * no Activity leak; the only lifetime concern is removing the view, which
 * [dismiss] handles. All window mutations happen on the main thread so that
 * addView and removeView run on the same Looper.
 *
 * StaticFieldLeak: this object holds the overlay View statically. It's safe here —
 * the View is rooted at the application context (inflated via
 * ContextThemeWrapper(appContext, ...)), never an Activity, so no Activity or
 * window is leaked; the only retained Context lives for the whole process anyway.
 * overlayView is nulled in dismissOnMain() once the window is removed, so a View
 * is held only while the overlay is on screen. A WeakReference would be wrong: the
 * strong reference is required to removeView() the visible overlay.
 */
@SuppressLint("StaticFieldLeak")
object AreYouThereOverlay {

    private const val TAG = "AreYouThereOverlay"

    // Brief screen-on pulse to wake a sleeping/dozing device when the overlay
    // appears. FLAG_KEEP_SCREEN_ON keeps it on afterwards, so the wake lock only
    // needs to live long enough to actually turn the display on.
    private const val SCREEN_WAKE_TIMEOUT_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var countdownRunnable: Runnable? = null
    private var countdownEndRealtimeMs: Long? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /** Show the overlay. Safe to call from any thread, including a background receiver. */
    fun show(context: Context, message: String?) {
        val appContext = context.applicationContext
        mainHandler.post { showOnMain(appContext, message) }
    }

    /** Dismiss the overlay (if it's showing). Safe to call from any thread. */
    fun dismiss(context: Context) {
        mainHandler.post { dismissOnMain() }
    }

    // InflateParams: an overlay window has no parent view to attach to, so passing
    //  null as the inflate root is correct here.
    @Suppress("InflateParams")
    private fun showOnMain(appContext: Context, message: String?) {
        if (overlayView != null) return

        if (!canDrawOverlays(appContext)) {
            DebugLogger.d(TAG, "Overlay permission not granted; not showing overlay")
            return
        }

        try {
            // A themed context is mandatory: the layout uses a Material Button and
            // colorPrimary text, which fail to inflate from the bare application
            // context (no MaterialComponents theme). Wrap it in the app theme.
            val themed = ContextThemeWrapper(appContext, R.style.Theme_KeepAlive)
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val view = LayoutInflater.from(themed)
                .inflate(R.layout.overlay_are_you_there, null, false)

            view.findViewById<TextView>(R.id.textAreYouThereTitle).text =
                appContext.getString(R.string.initial_check_notification_title)

            if (!message.isNullOrBlank()) {
                view.findViewById<TextView>(R.id.textAreYouThereMessage).text = message
            }

            view.findViewById<Button>(R.id.buttonImOk).setOnClickListener {
                AcknowledgeAreYouThere.acknowledge(appContext)
                dismissOnMain()
            }

            // Eat taps outside the button. Only the "I'm OK" button acknowledges;
            // tapping around it must not dismiss the overlay, otherwise a user
            // could silently cancel the prompt without being counted as present.
            view.setOnClickListener { /* no-op */ }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            // Lock-screen visibility relies on TYPE_APPLICATION_OVERLAY's natural
            //  z-order (it draws above the keyguard); FLAG_SHOW_WHEN_LOCKED is
            //  deprecated and ignored for non-Activity windows. A *secure* keyguard
            //  still can't be dismissed without the user authenticating — the
            //  overlay is merely visible on top.
            // FLAG_NOT_FOCUSABLE: a focusable system overlay interferes with the
            //  keyguard; non-focusable windows still receive touches, so the
            //  "I'm OK" button and the tap-eating root still work.
            // FLAG_KEEP_SCREEN_ON: keep the screen on while the overlay is visible.
            val flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                title = "KeepAlive - Are you there?"
            }

            // Start a visible countdown until the final alert triggers.
            val followupMins = getAppSharedPreferences(appContext)
                .getString(PrefKeys.FOLLOWUP_TIME_PERIOD_MINUTES, "60")
                ?.toIntOrNull() ?: 60
            startCountdown(appContext, view, followupMins)

            // Wake the screen first so the prompt is actually seen if the device
            //  was asleep/dozing, then attach the window.
            acquireScreenWake(appContext)
            wm.addView(view, params)
            windowManager = wm
            overlayView = view

            DebugLogger.d(TAG, "Overlay shown")
        } catch (t: Throwable) {
            // Never let an overlay failure crash the alert flow — the "Are you
            //  there?" notification is the primary signal and is already posted.
            DebugLogger.d(TAG, "Failed to show overlay: $t", t as? Exception)
            stopCountdown()
            releaseScreenWake()
        }
    }

    private fun dismissOnMain() {
        stopCountdown()
        releaseScreenWake()

        val view = overlayView
        val wm = windowManager
        overlayView = null
        windowManager = null

        try {
            if (view != null) {
                wm?.removeView(view)
                DebugLogger.d(TAG, "Overlay dismissed")
            }
        } catch (t: Throwable) {
            DebugLogger.d(TAG, "Failed to dismiss overlay: $t", t as? Exception)
        }
    }

    // SCREEN_BRIGHT_WAKE_LOCK is deprecated but, paired with ACQUIRE_CAUSES_WAKEUP,
    //  is still the only way to turn the display on from a background component
    //  (PARTIAL_WAKE_LOCK can't wake the screen, and FLAG_TURN_SCREEN_ON is
    //  Activity-only). Acquired with a timeout as a brief pulse; FLAG_KEEP_SCREEN_ON
    //  keeps the screen on for the overlay's lifetime.
    @Suppress("DEPRECATION")
    private fun acquireScreenWake(appContext: Context) {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$TAG:wake"
            )
            wl.setReferenceCounted(false)
            wl.acquire(SCREEN_WAKE_TIMEOUT_MS)
            wakeLock = wl
        } catch (t: Throwable) {
            DebugLogger.d(TAG, "Failed to acquire screen wake lock: $t", t as? Exception)
        }
    }

    private fun releaseScreenWake() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            DebugLogger.d(TAG, "Failed to release screen wake lock: $t", t as? Exception)
        }
        wakeLock = null
    }

    private fun startCountdown(appContext: Context, rootView: View, followupMinutes: Int) {
        stopCountdown()

        val countdownText = rootView.findViewById<TextView>(R.id.textAreYouThereCountdown)

        // Use elapsed realtime so it's unaffected by wall-clock changes.
        countdownEndRealtimeMs = SystemClock.elapsedRealtime() + (followupMinutes * 60_000L)

        fun update() {
            val end = countdownEndRealtimeMs ?: return
            val remainingMs = end - SystemClock.elapsedRealtime()
            val clampedMs = maxOf(0L, remainingMs)

            val totalSeconds = clampedMs / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L

            countdownText.text = appContext.getString(
                R.string.overlay_countdown_format,
                minutes.toInt(),
                seconds.toInt()
            )

            if (clampedMs <= 0L) {
                // stop ticking; the real alert flow is handled by alarms.
                stopCountdown()
            }
        }

        countdownRunnable = object : Runnable {
            override fun run() {
                update()
                // Tick roughly once per second.
                if (countdownRunnable != null) {
                    mainHandler.postDelayed(this, 1000L)
                }
            }
        }

        // update immediately
        mainHandler.post(countdownRunnable!!)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        countdownEndRealtimeMs = null
    }
}
