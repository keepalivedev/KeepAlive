package io.keepalive.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.util.Locale

@SuppressLint("MissingPermission")
fun getDefaultSmsSubscriptionId(context: Context): Int {
    val subscriptionManager =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
    val defaultSmsSubscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()

    // Check if the default SMS subscription ID is valid
    if (defaultSmsSubscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {

        Log.d("getDefaultSmsSubscriptionId","The default sms subscription id is not valid?!")

        subscriptionManager.activeSubscriptionInfoList?.let { activeSubscriptionInfoList ->
            Log.d(
                "getDefaultSmsSubscriptionId",
                "activeSubscriptionInfoList: $activeSubscriptionInfoList"
            )

            // look through the active subscriptions and see if the default is in the list
            for (subscriptionInfo in activeSubscriptionInfoList) {
                if (subscriptionInfo.subscriptionId == defaultSmsSubscriptionId) {
                    return defaultSmsSubscriptionId
                }
            }
        }
    }

    // Fallback to the first active subscription if the default is not valid
    return subscriptionManager.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId
        ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
}

fun sendAlertMessages(context: Context, locationStr: String) {
    Log.d("sendAlertMessage", "Sending alert SMS!")

    // get the preferences and load the SMS contacts
    val prefs = getEncryptedSharedPreferences(context)
    val smsContacts = loadSMSEmergencyContactSettings(prefs)

    Log.d("sendAlertMessage", "Loaded ${smsContacts.size} SMS contacts")

    // a list to track which phone numbers we have sent SMS to so we can include
    //  them in the notification
    val contactNumberList = arrayListOf<String>()

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

    // get the SMS manager
    //val smsManager = context.getSystemService(SmsManager::class.java)

    val smsManager: SmsManager? = try {

        Log.d("sendAlertMessage", "Trying to get SMS manager using subscription id")

        val subscriptionId = getDefaultSmsSubscriptionId(context)

        Log.d("sendAlertMessage", "Got default subscription id: $subscriptionId")

        // it seems that non-standard android phones that are on SDK < 31
        //  cannot use context.getSystemService(SmsManager::class.java) at all?? seems to
        //  throw errors on Xiaomi and OnePlus devices...
        // https://issuetracker.google.com/issues/242889550
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {

                Log.d("sendAlertMessage", "Getting SMS manager with context.getSystemService")
                context.getSystemService(SmsManager::class.java)
            } else {

                Log.d("sendAlertMessage", "Getting SMS manager with context.getSystemService " +
                        "and subscription id $subscriptionId")
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
            }

        } else {
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                Log.d("sendAlertMessage", "Getting default SMS manager")

                // the deprecation details here have good info on the single vs multi-sim situations
                SmsManager.getDefault()

            } else {
                Log.d("sendAlertMessage", "Getting default SMS manager for " +
                        "subscription id $subscriptionId")

                // this actually still seemed to work when passing -1 as the subscription id...
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        }
    } catch (e: Exception) {
        Log.d("sendAlertMessage", "Failed getting SMS manager using subscription id?!", e)

        // if the above fails default to the normal method of getting the SMS manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }

    // if this is an emulator or if the device doesn't have an active SIM plan this can be null
    if (smsManager == null) {
        Log.e("sendAlertMessage", "Failed getting SMS manager?!")

        // if smsManager is null we can't send SMS so send a notification to let the user know
        AlertNotificationHelper(context).sendNotification(
            context.getString(R.string.sms_service_failure_notification_title),
            context.getString(R.string.sms_service_failure_notification_text),
            AppController.SMS_ALERT_SENT_NOTIFICATION_ID
        )

        // there is nothing more we can do so return
        return
    }

    Log.d("sendAlertMessage", "Got SMS manager: $smsManager. subscriptionId is ${smsManager.subscriptionId}")

    // iterate through the contacts and send 1 or 2 SMS messages to each
    for (contact in smsContacts) {

        // if the contact is disabled then skip it
        if (!contact.isEnabled) {
            continue
        }

        Log.d(
            "sendAlertMessage", "Alert message is ${contact.alertMessage}, " +
                    "SMS contact number is ${contact.phoneNumber}"
        )

        // this shouldn't ever happen but just in case...
        if (contact.phoneNumber != "") {

            Log.d("sendAlertMessage", "Sending text message to ${contact.phoneNumber}!")

            try {
                // send the initial alert message defined by the user
                smsManager.sendTextMessage(
                    contact.phoneNumber, null, contact.alertMessage, null, null
                )

                // if enabled, send the location details in a second SMS
                if (contact.includeLocation) {

                    Log.d(
                        "sendAlertMessage",
                        "Sending location message, string is $locationStr"
                    )

                    smsManager.sendTextMessage(
                        contact.phoneNumber, null, locationStr, null, null
                    )
                }

                // format the phone number to make it more readable and add it to the list
                contactNumberList.add(
                    PhoneNumberUtils.formatNumber(
                        contact.phoneNumber,
                        Locale.getDefault().country
                    )
                )
            } catch (e: Exception) {
                Log.e("sendAlertMessage", "Failed sending SMS message?!", e)

                // if we failed while sending the SMS then send a notification
                //  to let the user know
                AlertNotificationHelper(context).sendNotification(
                    context.getString(R.string.sms_alert_failure_notification_title),
                    context.getString(R.string.sms_alert_failure_notification_text),
                    AppController.SMS_ALERT_SENT_NOTIFICATION_ID
                )

                // there is nothing more we can do so return
                return
            }

        } else {
            Log.d("sendAlertMessage", "SMS phone # was blank?!")
        }
    }
    Log.d(
        "sendAlertMessage", "Done sending alert messages, " +
                "there were ${contactNumberList.size}"
    )

    if (contactNumberList.size == 0) {
        Log.d("sendAlertMessage", "Did not send any SMS messages?!")
        return
    }

    // send a notification to make sure the user knows an SMS was sent
    AlertNotificationHelper(context).sendNotification(
        context.getString(R.string.alert_notification_title),
        String.format(
            context.getString(R.string.sms_alert_notification_text),
            contactNumberList.joinToString(",")
        ),
        AppController.SMS_ALERT_SENT_NOTIFICATION_ID
    )
}


fun makeAlertCall(context: Context) {

    val prefs = getEncryptedSharedPreferences(context)

    val phoneContactNumber = prefs.getString("contact_phone", "")

    // if we have a phone number
    if (phoneContactNumber != null && phoneContactNumber != "") {

        Log.d("makeAlarmCall", "starting call intent")

        // build the call intent
        val callIntent = Intent(Intent.ACTION_CALL)
        callIntent.data = Uri.parse("tel:$phoneContactNumber")
        callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        // double check that we have permissions
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            // enable speakerphone on the call
            callIntent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true)

            // launch the call intent
            context.startActivity(callIntent)

            // send a notification to make sure the user knows the call was sent
            AlertNotificationHelper(context).sendNotification(
                context.getString(R.string.alert_notification_title),
                String.format(
                    context.getString(R.string.call_alert_notification_text),

                    // format the phone number to make it more readable
                    PhoneNumberUtils.formatNumber(phoneContactNumber, Locale.getDefault().country)
                ),
                AppController.CALL_ALERT_SENT_NOTIFICATION_ID
            )

        } else {
            Log.d("makeAlarmCall", "don't have call phone permissions?!")
            Toast.makeText(context, "Alert phone call sent!", Toast.LENGTH_SHORT).show()
        }
    } else {
        Log.d("makeAlarmCall", "Phone # was null?!")
    }
}

// trying to find when the phone was last locked or unlocked
fun getLastPhoneActivity(context: Context, maxHours: Float): UsageEvents.Event? {
    var lastInteractiveEvent: UsageEvents.Event? = null

    // todo check for permissions usage stats permissions? this will fail silently and not
    //  return any events if we don't have permissions
    try {
        val end = System.currentTimeMillis()
        val start = (end - 1000 * 60 * 60 * maxHours).toLong()

        Log.d("getLastPhoneActivity", "checking usage stats for the last $maxHours hours")

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // get all events in the time range
        val events = usageStatsManager.queryEvents(start, end)

        // while there are still more events
        while (events.hasNextEvent()) {

            // load the next event
            val event = UsageEvents.Event()
            events.getNextEvent(event)

            // KEYGUARD_HIDDEN seems to fire when the phone is unlocked?? and KEYGUARD_SHOWN when
            //  the phone is locked?? doesn't seem to fire when something else wakes the screen up,
            //  like SCREEN_INTERACTIVE does...  it also fires even if using finger print to unlock?
            val targetEvents = arrayOf(
                UsageEvents.Event.KEYGUARD_HIDDEN,
                UsageEvents.Event.KEYGUARD_SHOWN
            )

            // the target events will only be from android
            if (event.packageName == "android" && event.eventType in targetEvents) {

                // look for the most recent event, though these should be in order from oldest to
                //  most recent so the timestmap check may not be necessary...
                if (lastInteractiveEvent == null || event.timeStamp > lastInteractiveEvent.timeStamp) {
                    lastInteractiveEvent = event
                }
            }
        }

        // print out the last event found, if any
        if (lastInteractiveEvent != null) {
            Log.d(
                "getLastPhoneActivity",
                "Last usage event was ${lastInteractiveEvent.eventType} at" +
                        " ${getDateTimeStrFromTimestamp(lastInteractiveEvent.timeStamp)}"
            )
        } else {
            Log.d(
                "getLastPhoneActivity",
                "No usage events found in the last $maxHours hours"
            )
        }

    } catch (e: Exception) {
        Log.e("getLastPhoneActivity", "Failed getting last phone activity?!", e)
    }
    return lastInteractiveEvent
}


fun doPeriodicCheck(context: Context) {
    val prefs = getEncryptedSharedPreferences(context)

    val checkPeriodHours = prefs.getString("time_period_hours", "12")!!.toFloat()
    val followupPeriodMinutes = prefs.getString("followup_time_period_minutes", "60")!!.toLong()

    Log.d(
        "doPeriodicCheck",
        "check period is $checkPeriodHours hours, " +
                "followup period is $followupPeriodMinutes minutes."
    )

    // check for usage events in the last x hours as defined by the user. if no events are found
    //  in that time range it means that the phone has been idle for at least that long
    val lastInteractiveEvent = getLastPhoneActivity(context, checkPeriodHours)

    // if no events were found then we need to send the alert
    if (lastInteractiveEvent == null) {
        Log.d(
            "doPeriodicCheck",
            "no events found in the last $checkPeriodHours hours?!"
        )

        // send the 'Are you there?' notification
        AlertNotificationHelper(context).sendNotification(
            context.getString(R.string.initial_check_notification_title),
            String.format(
                context.getString(R.string.initial_check_notification_text),
                followupPeriodMinutes
            ),
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )

        // if no events are found then set the alarm so we follow up
        setAlarm(context, (followupPeriodMinutes * 60 * 1000), "final")

    } else {

        // this just here for informational purposes
        val lastEventMsAgo = System.currentTimeMillis() - lastInteractiveEvent.timeStamp
        Log.d("doPeriodicCheck", "last event was ${lastEventMsAgo / 1000} seconds ago")

        // since now know when the last activity was we can set our next alarm for to the
        //  exact time we need to check again
        val newAlarmTimestamp = lastInteractiveEvent.timeStamp + (checkPeriodHours * 60 * 60 * 1000)

        // the milliseconds until our alarm would fire
        val newAlarmInMs = (newAlarmTimestamp - System.currentTimeMillis()).toLong()

        // set a new alarm so we can check again in the future
        setAlarm(context, newAlarmInMs, "periodic")
    }
}