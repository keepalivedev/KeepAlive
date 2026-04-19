package io.keepalive.android.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import io.keepalive.android.AlertNotificationHelper
import io.keepalive.android.AppController
import io.keepalive.android.DebugLogger
import io.keepalive.android.R

/**
 * Receives the "SMS_SENT" broadcast for each SMS (or multipart SMS part)
 * dispatched from [AlertMessageSender.sendAlertMessage]. A single instance
 * handles every broadcast from the batch and only unregisters itself once
 * [expectedBroadcasts] results have been received.
 *
 * Note: onReceive runs on the main looper, so the remaining-broadcast counter
 * does not need synchronization.
 */
class SMSSentReceiver(expectedBroadcasts: Int = 1) : BroadcastReceiver() {

    private var remaining: Int = expectedBroadcasts

    override fun onReceive(context: Context, intent: Intent) {

        // Only unregister after every expected broadcast has arrived.
        // A safety-net unregister in AlertMessageSender handles the case
        // where a send throws synchronously and a broadcast never comes.
        remaining--
        if (remaining <= 0) {
            try {
                context.unregisterReceiver(this)
            } catch (e: IllegalArgumentException) {
                DebugLogger.d("SMSSentReceiver", context.getString(R.string.debug_log_sms_receiver_not_registered), e)
            }
        }

        val result = when (resultCode) {
            Activity.RESULT_OK -> "SMS Sent"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Generic failure"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
            SmsManager.RESULT_ERROR_NULL_PDU -> "Null PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio off"
            else -> "Unknown error"
        }

        val notificationHelper = AlertNotificationHelper(context)

        // if it was sent successfully
        if (resultCode == Activity.RESULT_OK) {
            DebugLogger.d("SMSSentReceiver", context.getString(R.string.debug_log_sms_sent))

            notificationHelper.sendNotification(
                context.getString(R.string.alert_notification_title),
                context.getString(R.string.sms_alert_notification_text),
                AppController.SMS_ALERT_SENT_NOTIFICATION_ID
            )

        } else {
            DebugLogger.d("SMSSentReceiver", context.getString(R.string.debug_log_sms_send_error, result))

            // if it failed then let the user know why
            notificationHelper.sendNotification(
                context.getString(R.string.sms_alert_failure_notification_title),
                String.format(
                    context.getString(R.string.sms_alert_failure_notification_text_with_error),
                    result
                ),
                // use a different notification ID in case there are multiple alert contacts
                //  but only one of them fails
                AppController.SMS_ALERT_FAILURE_NOTIFICATION_ID
            )
        }
    }
}