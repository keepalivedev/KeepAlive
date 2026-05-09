package io.keepalive.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.keepalive.android.receivers.SMSSentReceiver

fun getDefaultSmsSubscriptionId(context: Context): Int {
    var defaultSmsSubscriptionId = -1
    return try {

        defaultSmsSubscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } else {
            // this isn't deprecated but docs say we should use SubscriptionManager?
            SmsManager.getDefaultSmsSubscriptionId()
        }

        // there is no reason to look at subscriptionManager.activeSubscriptionInfoList because
        //  we don't have a way to let the user choose which SIM to use and because checking it
        //  requires READ_PHONE_STATE permissions which we won't have if we are just sending SMS

        defaultSmsSubscriptionId

    } catch (e: Exception) {
        DebugLogger.d("getDefaultSmsSubId", context.getString(R.string.debug_log_failed_getting_sms_sub_id), e)
        defaultSmsSubscriptionId
    }
}

fun getSMSManager(context: Context): SmsManager? {
    return try {

        Log.d("sendAlertMessage", "Trying to get SMS manager using sub id")

        // the subscription id is unique to a SIM card and is basically
        //  the order in which SIMs were used in a given device (1,2,3,etc)
        // https://developer.android.com/identity/user-data-ids#accounts
        val subscriptionId = getDefaultSmsSubscriptionId(context)

        Log.d("sendAlertMessage", "Got default sub id: $subscriptionId")

        // it seems that non-standard android phones that are on SDK < 31
        //  cannot use context.getSystemService(SmsManager::class.java) at all?? seems to
        //  throw errors on Xiaomi and OnePlus devices...
        // https://issuetracker.google.com/issues/242889550
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {

                DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_getting_sms_manager_with_context))
                context.getSystemService(SmsManager::class.java)
            } else {

                DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_getting_sms_manager_with_sub_id, subscriptionId))
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
            }

        } else {
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_getting_default_sms_manager))

                // the deprecation details here have good info on the single vs multi-sim situations
                SmsManager.getDefault()

            } else {
                DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_getting_default_sms_manager_for_sub_id, subscriptionId))

                // this actually still seemed to work when passing -1 as the subscription id...
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        }
    } catch (e: Exception) {
        DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_exception_while_getting_sms_manager, e.localizedMessage), e)

        // if the above fails default to the normal method of getting the SMS manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }
}


class AlertMessageSender @JvmOverloads constructor(
    private val context: Context,
    // Injectable for tests. Default resolves the real SmsManager so production
    // callers don't need to care. Pass null or a mock from unit tests.
    private val smsManager: SmsManager? = getSMSManager(context)
) {

    companion object {
        // How long to wait before forcing the SMS_SENT receiver to unregister.
        // The normal path unregisters once all expected broadcasts arrive; this
        // guards against a sendTextMessage call that throws synchronously (so
        // the matching broadcast never comes) leaving the receiver registered.
        private const val SMS_RECEIVER_SAFETY_TIMEOUT_MS = 2 * 60 * 1000L
    }

    private val prefs = getEncryptedSharedPreferences(context)
    private val alertNotificationHelper = AlertNotificationHelper(context)
    private val smsContacts: MutableList<SMSEmergencyContactSetting> = loadJSONSharedPreference(prefs,
        "PHONE_NUMBER_SETTINGS")

    init {

        // if this is an emulator or if the device doesn't have an active SIM plan this can be null
        if (smsManager == null) {
            DebugLogger.d("AlertMessageSender", context.getString(R.string.debug_log_failed_getting_sms_manager))

            // if smsManager is null we can't send SMS so send a notification to let the user know
            alertNotificationHelper.sendNotification(
                context.getString(R.string.sms_service_failure_notification_title),
                context.getString(R.string.sms_service_failure_notification_text),
                AppController.SMS_ALERT_SENT_NOTIFICATION_ID
            )

        } else {
            Log.d("AlertMessageSender", "Got SMS manager: $smsManager. " +
                    "subscriptionId is ${smsManager.subscriptionId}")

            Log.d("AlertMessageSender", "Loaded ${smsContacts.size} SMS contacts")

            // other stuff we could check but as long as the smsManager isn't null then we should
            //  still try to send the SMS to reduce the chances of mistakenly preventing an alert
            //  from being sent
            /*
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

            // returns false on a phone without a SIM
            val isSimReady = telephonyManager.simState == TelephonyManager.SIM_STATE_READY

            // this is just whether the phone has a modem?
            val hasTelephony = packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

            // anything other than 0 means there is a cell network available?
            val networkType = if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                telephonyManager.networkType
            } else {
                -1
            }
            Log.d(tag, "isSimReady: $isSimReady, hasTelephony: $hasTelephony, networkType: $networkType")
            */
        }
    }

    fun sendAlertMessage(testWarningMessage: String = "") {

        if (smsManager == null) {
            // there is nothing more we can do so return
            return
        }

        DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sending_alert_sms))

        // API 34+ requires that a package name be specified for pending and implicit intents
        //  it seemed to work without this though...
        val smsSentIntent = Intent("SMS_SENT").apply {
            setPackage(context.packageName)
        }

        // create a pending intent for the SMS sent intent to use when sending the SMS
        val sentPI = PendingIntent.getBroadcast(
            context, 0, smsSentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Precompute the list of contacts we will actually send to, and the total
        // number of SMS_SENT broadcasts we expect. A multipart message produces
        // one broadcast per part, so we sum divideMessage(...).size across contacts.
        // This lets us register a single receiver for the whole batch instead of
        // one per contact — the old code re-registered inside the loop, so every
        // receiver fired on the first broadcast and all of them unregistered
        // themselves, leaving the remaining results unreported.
        data class PendingSms(val contact: SMSEmergencyContactSetting, val parts: ArrayList<String>)
        val pendingSmsList = mutableListOf<PendingSms>()
        var expectedBroadcasts = 0
        for (contact in smsContacts) {
            if (!contact.isEnabled) continue
            if (contact.phoneNumber.isEmpty()) {
                DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sms_phone_blank))
                continue
            }
            if (contact.alertMessage.isEmpty()) {
                Log.d("sendAlertMessage", "Alert message is blank, skipping...")
                continue
            }
            val parts = smsManager.divideMessage(contact.alertMessage)
            pendingSmsList.add(PendingSms(contact, parts))
            expectedBroadcasts += parts.size
        }

        // Register a single receiver that counts down across the whole batch.
        // If no contacts are pending, skip registration entirely.
        if (expectedBroadcasts > 0) {
            try {
                val receiver = SMSSentReceiver(expectedBroadcasts)
                val appContext = context.applicationContext
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // registerReceiver requires API 26+ but requires the use of
                    //  Context.RECEIVER_NOT_EXPORTED, which isn't available until API 33...
                    appContext.registerReceiver(
                        receiver,
                        IntentFilter("SMS_SENT"),
                        Context.RECEIVER_NOT_EXPORTED
                    )
                } else {
                    ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        IntentFilter("SMS_SENT"),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                }

                // Safety-net unregister: if a sendTextMessage call throws
                // synchronously we will never receive that broadcast, so the
                // counter wouldn't hit zero on its own. Schedule a forced
                // unregister that no-ops if the receiver already cleaned up.
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        appContext.unregisterReceiver(receiver)
                    } catch (e: IllegalArgumentException) {
                        // already unregistered — all broadcasts arrived
                    }
                }, SMS_RECEIVER_SAFETY_TIMEOUT_MS)
            } catch (e: Exception) {
                DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_failed_registering_sms_sent_receiver), e)
            }
        }

        // Now actually send the messages.
        for ((contact, messageParts) in pendingSmsList) {

            Log.d(
                "sendAlertMessage", "Alert message is ${contact.alertMessage}, " +
                        "SMS contact number is ${contact.phoneNumber}"
            )

            DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sending_text_message_to, maskPhoneNumber(contact.phoneNumber)))

            try {

                // if there is a test message, send it first
                if (testWarningMessage != "") {
                    DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sending_warning_sms, maskPhoneNumber(contact.phoneNumber)))

                    // don't include a sentIntent for the test message
                    smsManager.sendTextMessage(contact.phoneNumber, null, testWarningMessage, null, null)
                }

                Log.d("sendAlertMessage", "Message parts: $messageParts")

                // only use sendMultipartTextMessage if there is more than 1 part
                if (messageParts.size > 1) {
                    DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sending_multipart_sms, messageParts.size))

                    // create an array with the same pending intent for each part
                    val sentPIList = ArrayList<PendingIntent>(messageParts.size)
                    for (i in messageParts.indices) {
                        sentPIList.add(sentPI)
                    }

                    // send the multipart SMS message
                    smsManager.sendMultipartTextMessage(contact.phoneNumber, null,
                        messageParts, sentPIList, null)
                } else {

                    DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sending_single_sms))

                    // send a single part SMS message
                    smsManager.sendTextMessage(contact.phoneNumber, null,
                        contact.alertMessage, sentPI, null)
                }

            } catch (e: Exception) {
                DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_failed_sending_sms, maskPhoneNumber(contact.phoneNumber), e.localizedMessage), e)

                // if we failed while sending the SMS then send a notification
                //  to let the user know
                alertNotificationHelper.sendNotification(
                    context.getString(R.string.sms_alert_failure_notification_title),
                    context.getString(R.string.sms_alert_failure_notification_text),
                    AppController.SMS_ALERT_SENT_NOTIFICATION_ID
                )
            }
        }
    }

    fun sendLocationAlertMessage(locationStr: String) {
        if (smsManager == null) {
            return
        }
        Log.d("sendLocationAlertMsg", "Sending location alert SMS! loc string is $locationStr")

        // iterate through the contacts and send 1 or 2 SMS messages to each
        for (contact in smsContacts) {

            // if the contact is disabled then skip it
            if (!contact.isEnabled) {
                continue
            }

            // this shouldn't ever happen but just in case...
            if (contact.phoneNumber != "") {

                try {

                    // if enabled, send the location details
                    if (contact.includeLocation) {

                        DebugLogger.d("sendLocationAlertMsg", context.getString(R.string.debug_log_sending_location_message_to, maskPhoneNumber(contact.phoneNumber)))

                        // send the location message
                        smsManager.sendTextMessage(
                            contact.phoneNumber, null, locationStr, null, null
                        )
                    }

                } catch (e: Exception) {
                    DebugLogger.d("sendLocationAlertMsg", context.getString(R.string.debug_log_failed_sending_location_sms, maskPhoneNumber(contact.phoneNumber)), e)
                }

            } else {
                DebugLogger.d("sendLocationAlertMsg", context.getString(R.string.debug_log_sms_phone_blank))
            }
        }
        Log.d( "sendLocationAlertMsg", "Done sending location alert messages")
    }
}
