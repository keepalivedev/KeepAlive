package io.keepalive.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale

fun makeAlertCall(context: Context) {
    try {
        val prefs = getEncryptedSharedPreferences(context)
        val alertNotificationHelper = AlertNotificationHelper(context)
        val phoneContactNumber = prefs.getString("contact_phone", "")

        // if we have a phone number
        if (phoneContactNumber != null && phoneContactNumber != "") {

            // double check that we have permissions
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CALL_PHONE
                ) == PackageManager.PERMISSION_GRANTED
            ) {

                DebugLogger.d(
                    "makeAlarmCall",
                    context.getString(
                        R.string.debug_log_placing_alert_phone_call,
                        maskPhoneNumber(phoneContactNumber)
                    )
                )

                // build the call intent
                val callIntent = Intent(Intent.ACTION_CALL)
                callIntent.data = Uri.parse("tel:$phoneContactNumber")
                callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

                // enable speakerphone on the call
                callIntent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)

                // launch the call intent
                context.startActivity(callIntent)

                // send a notification to make sure the user knows the call was sent
                alertNotificationHelper.sendNotification(
                    context.getString(R.string.alert_notification_title),
                    String.format(
                        context.getString(R.string.call_alert_notification_text),

                        // format the phone number to make it more readable
                        PhoneNumberUtils.formatNumber(
                            phoneContactNumber,
                            Locale.getDefault().country
                        )
                    ),
                    AppController.CALL_ALERT_SENT_NOTIFICATION_ID
                )

            } else {
                DebugLogger.d(
                    "makeAlarmCall",
                    context.getString(R.string.debug_log_no_call_phone_permission)
                )
            }
        } else {
            Log.d("makeAlarmCall", "Phone # was null?!")
        }
    } catch (e: Exception) {
        Log.d("makeAlarmCall", "Exception while making alert call: ${e.message}")
    }
}
