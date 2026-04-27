package io.keepalive.android

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.edit

class AlertService : Service() {

    companion object {
        /** Intent extra key used to stamp each alert with a unique trigger time. */
        const val EXTRA_ALERT_TRIGGER_TIMESTAMP = "alert_trigger_timestamp"

        // Step-tracker bitmask constants live in AlertStepRunner.kt so the
        // pure sequencing logic can share them with this service.

        // SharedPreferences keys for the step tracker.
        // The trigger timestamp identifies *which* alert cycle the steps belong to.
        private const val PREF_ALERT_TRIGGER_TIMESTAMP = "AlertTriggerTimestamp"
        private const val PREF_ALERT_STEPS_COMPLETED = "AlertStepsCompleted"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var alertNotificationHelper: AlertNotificationHelper
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private val wakeLockTag = "KeepAlive::AlertWakeLock"
    private val wakeLockTimeout = 120 * 1000L
    private val serviceTimeout = 120 * 1000L

    /** Trigger timestamp from the current intent; used to key step-tracking. */
    private var currentTriggerTimestamp = 0L

    /** Lock protecting the read-modify-write on [PREF_ALERT_STEPS_COMPLETED]. */
    private val stepLock = Any()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        prefs = getEncryptedSharedPreferences(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag)
        // Disable reference counting so repeated acquire() calls don't stack
        // (e.g., back-to-back alert intents, or START_REDELIVER_INTENT redelivery).
        // With refcounting off, each acquire() just (re)extends the timeout and a
        // single release() in onDestroy() fully releases the lock.
        wakeLock.setReferenceCounted(false)
        alertNotificationHelper = AlertNotificationHelper(this)
    }

    override fun onDestroy() {
        super.onDestroy()

        // cancel the timeout handler
        cancelTimeout()

        // if we still have a wake lock, release it
        if (wakeLock.isHeld) {
            DebugLogger.d("AlertService", getString(R.string.debug_log_wake_lock_released))
            wakeLock.release()

        } else {
            Log.d("AlertService", "Wake lock was not held")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {

            // create a notification to show that the service is running
            val notification = createNotification()

            // a lot of this seems unnecessary, the type of service declared doesn't seem to affect
            //  what we are actually able to do in the service...
            var foregroundServiceTypes = 0

            // FOREGROUND_SERVICE_LOCATION is only for 'long-running' location services...

            // if this is API 34+ then add the new short service type
            // https://developer.android.com/about/versions/14/changes/fgs-types-required#short-service
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                foregroundServiceTypes = foregroundServiceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            }

            // Always call startForeground() immediately. Android requires this shortly
            // after startForegroundService() — even if we're about to stop due to dedup.
            ServiceCompat.startForeground(
                this,
                AppController.ALERT_SERVICE_NOTIFICATION_ID,
                notification,
                foregroundServiceTypes
            )

            // --- Idempotent step-tracking guard ---
            // Each alert intent carries a trigger timestamp that identifies the alert
            // cycle.  We track which discrete steps (SMS, call, location, webhook) have
            // been completed for that timestamp as a bitmask in SharedPreferences.
            //
            //  • New trigger → reset the bitmask to 0, run all steps.
            //  • Same trigger, some steps remaining → resume from where we left off.
            //  • Same trigger, all steps complete → duplicate / redelivery, bail out.
            //  • Older trigger → a newer alert already started, bail out.
            //
            // Each step marks its bit (via synchronous commit()) only AFTER the work
            // finishes, so a process-kill before completion leaves the bit unset and
            // the step will be retried on the next delivery.
            val triggerTimestamp = intent?.getLongExtra(EXTRA_ALERT_TRIGGER_TIMESTAMP, 0L) ?: 0L

            if (triggerTimestamp > 0L) {
                val savedTrigger = prefs.getLong(PREF_ALERT_TRIGGER_TIMESTAMP, 0L)
                val savedSteps = prefs.getInt(PREF_ALERT_STEPS_COMPLETED, 0)

                when (decideAlertIntentAction(triggerTimestamp, savedTrigger, savedSteps, ALL_STEPS_COMPLETE)) {
                    AlertIntentAction.Skip -> {
                        DebugLogger.d("AlertService",
                            "Skipping alert intent trigger=$triggerTimestamp " +
                            "(savedTrigger=$savedTrigger, savedSteps=$savedSteps)")
                        stopService()
                        return START_REDELIVER_INTENT
                    }
                    AlertIntentAction.Resume -> {
                        DebugLogger.d("AlertService",
                            "Resuming alert trigger=$triggerTimestamp " +
                            "(completedSteps=$savedSteps)")
                    }
                    AlertIntentAction.NewAlert -> {
                        prefs.edit(commit = true) {
                            putLong(PREF_ALERT_TRIGGER_TIMESTAMP, triggerTimestamp)
                            putInt(PREF_ALERT_STEPS_COMPLETED, 0)
                        }
                    }
                }
            }

            currentTriggerTimestamp = triggerTimestamp

            DebugLogger.d("AlertService", getString(R.string.debug_log_wake_lock_acquired))
            wakeLock.acquire(wakeLockTimeout)

            // start a timeout handler to stop the service if it takes too long
            scheduleTimeout()

            // Run sendAlert on a background thread
            Thread {

                try {

                    // send the alert; this may return before all processing is completed
                    //  (the location callback is async)
                    sendAlert(this, prefs)

                } catch (e: Exception) {
                    DebugLogger.d("AlertService", getString(R.string.debug_log_alert_service_error), e)

                    // if there is an error then stop the service
                    stopService()
                }

            }.start()

        } catch (e: Exception) {
            DebugLogger.d("AlertService", getString(R.string.debug_log_alert_service_error), e)

            stopService()
        }

        // Use START_REDELIVER_INTENT so the OS will restart the service and redeliver
        // the intent if the process is killed mid-alert (e.g., OOM, app update).
        // The step tracker ensures every step runs exactly once: completed steps are
        // skipped and incomplete steps are retried.
        return START_REDELIVER_INTENT
    }

    private fun scheduleTimeout() {
        cancelTimeout()
        timeoutRunnable = Runnable {
            Log.d("AlertService", "sendAlert timed out after 2 minutes, stopping service")
            stopService()
        }
        handler.postDelayed(timeoutRunnable!!, serviceTimeout)
    }

    private fun cancelTimeout() {
        if (timeoutRunnable != null) {
            Log.d("AlertService", "Cancelling timeout handler")
            timeoutRunnable?.let { handler.removeCallbacks(it) }
            timeoutRunnable = null
        } else {
            Log.d("AlertService", "No timeout to cancel")
        }
    }

    private fun stopService() {
        DebugLogger.d("AlertService", getString(R.string.debug_log_alert_service_stop))
        stopSelf()
    }

    /**
     * Atomically set [step] in the completed-steps bitmask and persist via commit().
     * Synchronized to prevent lost updates when the alert thread and the location
     * callback thread mark different steps concurrently.
     */
    private fun markStepComplete(step: Int) {
        if (currentTriggerTimestamp <= 0L) return
        synchronized(stepLock) {
            val current = prefs.getInt(PREF_ALERT_STEPS_COMPLETED, 0)
            prefs.edit(commit = true) {
                putInt(PREF_ALERT_STEPS_COMPLETED, current or step)
            }
        }
    }

    /** Check whether [step] has already been completed for the current alert cycle. */
    private fun isStepComplete(step: Int): Boolean {
        if (currentTriggerTimestamp <= 0L) return false
        return prefs.getInt(PREF_ALERT_STEPS_COMPLETED, 0) and step != 0
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, AppController.ALERT_SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.alert_service_notification_title))
            .setContentText(getString(R.string.alert_service_notification_message))
            .setSmallIcon(R.drawable.ic_notification)
            // the OS may delay the visibility of the notification so force it to be visible immediately
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun sendWebhookRequest(context: Context, locationResult: LocationResult?) {
        try {
            val webhookConfigManager = WebhookConfigManager(this, null)
            val webhookConfig = webhookConfigManager.getWebhookConfig()

            val webhookSender = WebhookSender(this, webhookConfig)
            webhookSender.sendRequest(locationResult, callback = webhookCallback(context))

        } catch (e: Exception) {
            DebugLogger.d("sendAlert", context.getString(R.string.debug_log_sending_webhook_failed, e.localizedMessage), e)
        }
    }

    private fun webhookCallback(context: Context) : WebhookCallback {
        return object : WebhookCallback {
            override fun onSuccess(responseCode: Int) {

                alertNotificationHelper.sendNotification(
                    context.getString(R.string.webhook_request_success_notification_title),
                    String.format(
                        context.getString(R.string.webhook_request_success_notification_text),
                        responseCode
                    ),
                    AppController.WEBHOOK_ALERT_SENT_NOTIFICATION_ID,
                    true
                )
            }

            override fun onFailure(responseCode: Int) {

                alertNotificationHelper.sendNotification(
                    context.getString(R.string.webhook_request_failure_notification_title),
                    String.format(
                        context.getString(R.string.webhook_request_failure_code_notification_text),
                        responseCode
                    ),
                    AppController.WEBHOOK_ALERT_SENT_NOTIFICATION_ID,
                    true
                )
            }

            override fun onError(errorMessage: String) {

                alertNotificationHelper.sendNotification(
                    context.getString(R.string.webhook_request_failure_notification_title),
                    String.format(
                        context.getString(R.string.webhook_request_failure_error_notification_text),
                        errorMessage
                    ),
                    AppController.WEBHOOK_ALERT_SENT_NOTIFICATION_ID,
                    true
                )
            }
        }
    }

    private fun sendAlert(context: Context, prefs: SharedPreferences) {
        DebugLogger.d("sendAlert", context.getString(R.string.debug_log_sending_alert))

        val alertSender = AlertMessageSender(context)

        val steps = object : AlertStepOps {
            override fun isComplete(step: Int): Boolean = isStepComplete(step)
            override fun markComplete(step: Int) = markStepComplete(step)
        }

        val dispatcher = object : AlertDispatcher {
            override val isUserUnlocked: Boolean get() = isUserUnlocked(context)
            override val locationNeeded: Boolean
                get() = prefs.getBoolean("location_enabled", false) ||
                        prefs.getBoolean("webhook_location_enabled", false)
            override val webhookEnabled: Boolean
                get() = prefs.getBoolean("webhook_enabled", false)

            override fun cancelAreYouThereNotification() {
                AlertNotificationHelper(context).cancelNotification(
                    AppController.ARE_YOU_THERE_NOTIFICATION_ID
                )
            }
            override fun dismissAreYouThereOverlay() = AreYouThereOverlay.dismiss(context)
            override fun sendSmsAlert() = alertSender.sendAlertMessage()
            override fun makeCall() = makeAlertCall(context)
            override fun writeLastAlertAt(timestamp: Long) {
                prefs.edit(commit = true) { putLong("LastAlertAt", timestamp) }
            }
            override fun requestLocationAsync(onResult: (LocationResult) -> Unit) {
                val helper = LocationHelper(context) { _, locationResult -> onResult(locationResult) }
                helper.getLocationAndExecute()
            }
            override fun sendLocationSms(locationText: String) =
                alertSender.sendLocationAlertMessage(locationText)
            override fun sendWebhook(locationResult: LocationResult?) =
                sendWebhookRequest(context, locationResult)
            override fun stopService() = this@AlertService.stopService()
            override fun now(): Long = System.currentTimeMillis()
        }

        runAlertSteps(steps, dispatcher)
    }
}