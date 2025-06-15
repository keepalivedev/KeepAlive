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

class AlertService : Service() {

    private lateinit var prefs: SharedPreferences
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var alertNotificationHelper: AlertNotificationHelper
    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private val wakeLockTag = "KeepAlive::AlertWakeLock"
    private val wakeLockTimeout = 120 * 1000L
    private val serviceTimeout = 120 * 1000L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        prefs = getEncryptedSharedPreferences(this)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, wakeLockTag)
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

            // start the service
            ServiceCompat.startForeground(
                this,
                AppController.ALERT_SERVICE_NOTIFICATION_ID,
                notification,
                foregroundServiceTypes
            )

            DebugLogger.d("AlertService", getString(R.string.debug_log_wake_lock_acquired))
            wakeLock.acquire(wakeLockTimeout)

            // start a timeout handler to stop the service if it takes too long
            scheduleTimeout()

            // Run sendAlert on a background thread
            Thread {

                try {

                    // send the alert; this may return before all processing is completed
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

        // restart the service if it is killed; this shouldn't happen though because we have
        //  a wake lock and the OS should be temporarily whitelisting us?
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

        // cancel the 'Are you there?' notification
        AlertNotificationHelper(context).cancelNotification(
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )

        // send the alert messages
        val alertSender = AlertMessageSender(context)
        alertSender.sendAlertMessage()

        // only get the location if the user has enabled it for at least one contact
        //  or for the webhook
        if (prefs.getBoolean("location_enabled", false) || prefs.getBoolean("webhook_location_enabled", false)) {

            // just add an extra layer try/catch in case anything unexpected
            //  happens when trying to get the location
            try {

                // attempt to get the location and then execute sendLocationAlertMessage
                val locationHelper = LocationHelper(context) { _, locationResult ->

                    // this will still check whether location is enabled for each contact
                    //  (in case location is only enabled for the webhook but not any SMS contacts)

                    // for the SMS alerts we just need the formatted location string
                    alertSender.sendLocationAlertMessage(locationResult.formattedLocationString)

                    // if enabled, send a request to the webhook with the location result
                    if (prefs.getBoolean("webhook_enabled", false)) {
                        sendWebhookRequest(context, locationResult)
                    }

                    // stop the service after the location alert is sent
                    stopService()
                }

                locationHelper.getLocationAndExecute()

            } catch (e: Exception) {

                // if we fail for any reason then send the alert messages
                DebugLogger.d("sendAlert", context.getString(R.string.debug_log_sending_alert_failed), e)

                stopService()
            }
        } else {

            // if enabled, send the webhook without the location
            if (prefs.getBoolean("webhook_enabled", false)) {
                sendWebhookRequest(context, null)
            }
        }

        // also make the phone call (if enabled)
        makeAlertCall(context)

        // update prefs to include when the alert was sent
        with(prefs.edit()) {
            putLong("LastAlertAt", System.currentTimeMillis())
            apply()
        }
    }
}