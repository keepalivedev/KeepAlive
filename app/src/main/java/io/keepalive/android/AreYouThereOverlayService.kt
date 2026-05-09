package io.keepalive.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * Manages the over-other-apps full-screen "Are you there?" overlay.
 *
 * Implemented as a Service so we don't keep a static reference to a View/Context.
 */
class AreYouThereOverlayService : Service() {

    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunnable: Runnable? = null
    private var countdownEndRealtimeMs: Long? = null

    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android (O+) contract: if the service was started with
        // startForegroundService(), it MUST call Service.startForeground()
        // within ~5s or the system kills the app with
        // ForegroundServiceDidNotStartInTimeException. Calling
        // startForeground in onCreate is the earliest possible point —
        // onStartCommand may be delayed or skipped in edge cases (process
        // restart on START_NOT_STICKY, OS queueing under load).
        try {
            ensureForeground()
        } catch (t: Throwable) {
            Log.e(TAG, "ensureForeground failed in onCreate", t)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Redundant safety — usually a no-op since onCreate already did it.
        try { ensureForeground() } catch (t: Throwable) {
            Log.e(TAG, "ensureForeground failed in onStartCommand", t)
        }

        try {
            when (intent?.action) {
                ACTION_SHOW -> {
                    val message = intent.getStringExtra(EXTRA_MESSAGE)
                    showOverlay(message)
                }
                ACTION_DISMISS -> {
                    dismissOverlay()
                    stopSelf()
                }
                else -> {
                    Log.d(TAG, "Unknown action: ${intent?.action}")
                    stopSelf()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Overlay service failed", t)
            dismissOverlay()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        dismissOverlay()
        super.onDestroy()
    }

    private fun showOverlay(message: String?) {
        if (overlayView != null) return

        if (!canDrawOverlays(this)) {
            Log.d(TAG, "Overlay permission not granted; not showing overlay")
            // stopForeground+stopSelf — onStartCommand already called
            // startForeground, we must tear that down cleanly.
            stopForegroundIfNeeded()
            stopSelf()
            return
        }

        // ensureForeground() is already invoked in onStartCommand so we're
        // guaranteed the foreground-service contract was honored before any
        // early return. No-op here since isForeground is already true.

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_are_you_there, null, false)

        view.findViewById<TextView>(R.id.textAreYouThereTitle).text =
            getString(R.string.initial_check_notification_title)

        if (!message.isNullOrBlank()) {
            view.findViewById<TextView>(R.id.textAreYouThereMessage).text = message
        }

        view.findViewById<Button>(R.id.buttonImOk).setOnClickListener {
            AcknowledgeAreYouThere.acknowledge(applicationContext)
            dismissOverlay()
            stopSelf()
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

        val flags =
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

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
        // We already know the follow-up duration in minutes from preferences.
        val followupMins = getEncryptedSharedPreferences(applicationContext)
            .getString("followup_time_period_minutes", "60")
            ?.toIntOrNull() ?: 60
        startCountdown(view, followupMins)

        wm.addView(view, params)
        windowManager = wm
        overlayView = view

        Log.d(TAG, "Overlay shown")
    }

    private fun startCountdown(rootView: View, followupMinutes: Int) {
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

            countdownText.text = getString(
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
                    handler.postDelayed(this, 1000L)
                }
            }
        }

        // update immediately
        handler.post(countdownRunnable!!)
    }

    private fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
        countdownEndRealtimeMs = null
    }

    private fun ensureForeground() {
        if (isForeground) return

        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Dedicated channel so the user can manage this separately from other alerts.
            // Importance low because the overlay itself is the attention grabber.
            if (notificationManager.getNotificationChannel(OVERLAY_SERVICE_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    OVERLAY_SERVICE_CHANNEL_ID,
                    "KeepAlive overlay active",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the Are you there? full-screen overlay visible"
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    lightColor = Color.RED
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                }
                notificationManager.createNotificationChannel(channel)
            }
        }

        val openIntent = MainActivity.createAlertCheckIntent(applicationContext).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, OVERLAY_SERVICE_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.initial_check_notification_title))
            .setContentText(getString(R.string.are_you_there_waiting_for_acknowledgement))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        startForeground(OVERLAY_SERVICE_NOTIFICATION_ID, notification)
        isForeground = true
    }

    private fun stopForegroundIfNeeded() {
        if (!isForeground) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop foreground", t)
        }
        isForeground = false
    }

    private fun dismissOverlay() {
        stopCountdown()

        val view = overlayView
        val wm = windowManager
        overlayView = null
        windowManager = null

        try {
            if (view != null) {
                wm?.removeView(view)
                Log.d(TAG, "Overlay dismissed")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to dismiss overlay", t)
        } finally {
            stopForegroundIfNeeded()
        }
    }

    companion object {
        private const val TAG = "AreYouThereOverlaySvc"

        private const val OVERLAY_SERVICE_CHANNEL_ID = "keepalive_overlay_service"
        private const val OVERLAY_SERVICE_NOTIFICATION_ID = 38101

        const val ACTION_SHOW = "io.keepalive.android.overlay.SHOW"
        const val ACTION_DISMISS = "io.keepalive.android.overlay.DISMISS"
        const val EXTRA_MESSAGE = "message"

        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun show(context: Context, message: String?) {
            if (!canDrawOverlays(context)) return
            val i = Intent(context, AreYouThereOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_MESSAGE, message)
            }
            // When triggered from background on Android O+, use startForegroundService.
            // Wrap in try/catch: on API 31+ Android can throw
            // ForegroundServiceStartNotAllowedException if we're not in an
            // exempt state (e.g. background after a non-system-broadcast
            // alarm wake-up). The overlay is supplementary — the
            // "Are you there?" notification is the primary signal, and we
            // must NEVER let an overlay-start failure crash the alert flow.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                Log.w("AreYouThereOverlay",
                    "Failed to start overlay service; the notification is still posted", t)
            }
        }

        fun dismiss(context: Context) {
            // Use stopService() instead of startForegroundService(ACTION_DISMISS).
            // startForegroundService() requires the service to call startForeground()
            // within 5 seconds, but dismiss just needs the service to stop — and
            // onDestroy() already calls dismissOverlay() for cleanup.
            // If the service isn't running, stopService() is a harmless no-op.
            try {
                val i = Intent(context, AreYouThereOverlayService::class.java)
                context.stopService(i)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to stop overlay service", t)
            }
        }
    }
}
