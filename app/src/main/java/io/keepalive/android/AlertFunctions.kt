package io.keepalive.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import io.keepalive.android.receivers.SMSSentReceiver
import java.util.Calendar
import java.util.Locale

@SuppressLint("MissingPermission")
fun getDefaultSmsSubscriptionId(context: Context): Int {
    val subscriptionManager =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

    val defaultSmsSubscriptionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        SubscriptionManager.getDefaultSmsSubscriptionId()
    } else {
       -1
    }

    // Check if the default SMS subscription ID is valid
    if (defaultSmsSubscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {

        Log.d("getDefaultSmsSubId","The default sms subscription id is not valid?!")

        subscriptionManager.activeSubscriptionInfoList?.let { activeSubscriptionInfoList ->
            Log.d(
                "getDefaultSmsSubId",
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
    return subscriptionManager.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId ?: -1
}

fun getSMSManager(context: Context): SmsManager? {
    return try {

        Log.d("sendAlertMessage", "Trying to get SMS manager using sub id")

        val subscriptionId = getDefaultSmsSubscriptionId(context)

        Log.d("sendAlertMessage", "Got default sub id: $subscriptionId")

        // it seems that non-standard android phones that are on SDK < 31
        //  cannot use context.getSystemService(SmsManager::class.java) at all?? seems to
        //  throw errors on Xiaomi and OnePlus devices...
        // https://issuetracker.google.com/issues/242889550
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {

                DebugLogger.d("sendAlertMessage", "Getting SMS manager with context.getSystemService")
                context.getSystemService(SmsManager::class.java)
            } else {

                DebugLogger.d("sendAlertMessage", "Getting SMS manager with context.getSystemService " +
                        "and sub id $subscriptionId")
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subscriptionId)
            }

        } else {
            if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                DebugLogger.d("sendAlertMessage", "Getting default SMS manager")

                // the deprecation details here have good info on the single vs multi-sim situations
                SmsManager.getDefault()

            } else {
                DebugLogger.d("sendAlertMessage", "Getting default SMS manager for " +
                        "sub id $subscriptionId")

                // this actually still seemed to work when passing -1 as the subscription id...
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        }
    } catch (e: Exception) {
        DebugLogger.d("sendAlertMessage", "Exception while getting SMS manager: ${e.message}", e)

        // if the above fails default to the normal method of getting the SMS manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
    }
}


class AlertMessageSender(private val context: Context) {
    private val smsManager = getSMSManager(context)
    private val prefs = getEncryptedSharedPreferences(context)
    private val smsContacts: MutableList<SMSEmergencyContactSetting> = loadJSONSharedPreference(prefs,
        "PHONE_NUMBER_SETTINGS")

    init {

        // if this is an emulator or if the device doesn't have an active SIM plan this can be null
        if (smsManager == null) {
            DebugLogger.d("AlertMessageSender", "Failed getting SMS manager?!")

            // if smsManager is null we can't send SMS so send a notification to let the user know
            AlertNotificationHelper(context).sendNotification(
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

    fun sendAlertMessage() {

        if (smsManager == null) {
            // there is nothing more we can do so return
            return
        }

        DebugLogger.d("sendAlertMessage", "Sending alert SMS!")

        // create a pending intent for the SMS sent intent to use when sending the SMS
        val sentPI = PendingIntent.getBroadcast(
            context, 0, Intent("SMS_SENT"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // iterate through the contacts and send an SMS message to each
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

                DebugLogger.d("sendAlertMessage", "Sending text message to: ${contact.phoneNumber}")

                try {

                    // do this for each contact instead of once at the beginning because
                    //  the SMSSentReceiver will unregister it when it gets called
                    // add a receiver for the SMS sent intent so we will get notified of the result
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        context.registerReceiver(
                            SMSSentReceiver(),
                            IntentFilter("SMS_SENT"),
                            Context.RECEIVER_NOT_EXPORTED
                        )
                    } else {
                        ContextCompat.registerReceiver(
                            context,
                            SMSSentReceiver(),
                            IntentFilter("SMS_SENT"),
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                    }

                    // divide the message into parts based on how long it is
                    // messages with unicode characters have a shorter max length
                    val messageParts = smsManager.divideMessage(contact.alertMessage)
                    Log.d("sendAlertMessage", "Message parts: $messageParts")

                    // only use sendMultipartTextMessage if there is more than 1 part
                    if (messageParts.size > 1) {
                        DebugLogger.d("sendAlertMessage", "Sending multipart SMS message with ${messageParts.size} parts")

                        // create an array with the same pending intent for each part
                        val sentPIList = ArrayList<PendingIntent>()
                        for (i in messageParts.indices) {
                            sentPIList.add(sentPI)
                        }

                        // send the multipart SMS message
                        smsManager.sendMultipartTextMessage(contact.phoneNumber, null,
                            messageParts, sentPIList, null)
                    } else {

                        DebugLogger.d("sendAlertMessage", "Sending single SMS message")

                        // send a single part SMS message
                        smsManager.sendTextMessage(contact.phoneNumber, null,
                            contact.alertMessage, sentPI, null)
                    }

                } catch (e: Exception) {
                    DebugLogger.d("sendAlertMessage", "Failed sending SMS message to ${contact.phoneNumber}!? ${e.message}", e)

                    // if we failed while sending the SMS then send a notification
                    //  to let the user know
                    AlertNotificationHelper(context).sendNotification(
                        context.getString(R.string.sms_alert_failure_notification_title),
                        context.getString(R.string.sms_alert_failure_notification_text),
                        AppController.SMS_ALERT_SENT_NOTIFICATION_ID
                    )
                }
            } else {
                DebugLogger.d("sendAlertMessage", "SMS phone # was blank?!")
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

                        DebugLogger.d(
                            "sendLocationAlertMsg",
                            "Sending location message to: ${contact.phoneNumber}"
                        )

                        // send the location message
                        smsManager.sendTextMessage(
                            contact.phoneNumber, null, locationStr, null, null
                        )
                    }

                } catch (e: Exception) {
                    DebugLogger.d("sendLocationAlertMsg", "Failed sending location SMS message to ${contact.phoneNumber}!?", e)
                }

            } else {
                DebugLogger.d("sendLocationAlertMsg", "SMS phone # was blank?!")
            }
        }
        Log.d( "sendLocationAlertMsg", "Done sending location alert messages")
    }
}


fun makeAlertCall(context: Context) {

    val prefs = getEncryptedSharedPreferences(context)

    val phoneContactNumber = prefs.getString("contact_phone", "")

    // if we have a phone number
    if (phoneContactNumber != null && phoneContactNumber != "") {

        // double check that we have permissions
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            DebugLogger.d("makeAlarmCall", "Placing Alert phone call to $phoneContactNumber")

            // build the call intent
            val callIntent = Intent(Intent.ACTION_CALL)
            callIntent.data = Uri.parse("tel:$phoneContactNumber")
            callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

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
            DebugLogger.d("makeAlarmCall", "Unable to place call, don't have permissions?!")
        }
    } else {
        Log.d("makeAlarmCall", "Phone # was null?!")
    }
}

// trying to find when the phone was last locked or unlocked
fun getLastPhoneActivity(context: Context, startTimestamp: Long, monitoredApps: List<String>? = null): UsageEvents.Event? {
    var lastInteractiveEvent: UsageEvents.Event? = null

    // todo check for permissions usage stats permissions? this will fail silently and not
    //  return any events if we don't have permissions
    try {

        DebugLogger.d("getLastPhoneActivity", "checking for activity starting at" +
                " ${getDateTimeStrFromTimestamp(startTimestamp)}")

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // get all events between the starting timestamp and now
        val events = usageStatsManager.queryEvents(startTimestamp, System.currentTimeMillis())

        var appsToMonitor = monitoredApps
        val targetEvents: MutableList<Int> = mutableListOf()

        Log.d("getLastPhoneActivity", "Checking for monitored apps: ${appsToMonitor == null} $appsToMonitor")

        // if no apps were passed in then use the package for monitoring lock/unlock events
        if (appsToMonitor.isNullOrEmpty() && Build.VERSION.SDK_INT >= AppController.MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK) {

            DebugLogger.d("getLastPhoneActivity", "Checking for system lock/unlock events")
            appsToMonitor = listOf("android")

            // KEYGUARD_HIDDEN seems to fire when the phone is unlocked?? and KEYGUARD_SHOWN when
            //  the phone is locked?? doesn't seem to fire when something else wakes the screen up,
            //  like SCREEN_INTERACTIVE does...  it also fires even if using finger print to unlock?
            targetEvents.add(UsageEvents.Event.KEYGUARD_HIDDEN)
            targetEvents.add(UsageEvents.Event.KEYGUARD_SHOWN)

        } else {
            // this is deprecated in API 29 but still seems to be in use?
            targetEvents.add(UsageEvents.Event.MOVE_TO_FOREGROUND)

            // when testing under API 34 this never seems to fire, apps still sending MOVE_TO_FOREGROUND
            // if it is available we should still check it though
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                targetEvents.add(UsageEvents.Event.ACTIVITY_RESUMED)
            }

            // we shouldn't have set an alarm if this is < API 28 and no apps to monitor have
            //  been set so just set this to an empty list so it doesn't throw an error...
            appsToMonitor = monitoredApps ?: emptyList()
        }

        // while there are still more events
        while (events.hasNextEvent()) {

            // load the next event
            val event = UsageEvents.Event()
            events.getNextEvent(event)

            // check for any apps that sent any of the target event types
            if (event.packageName in appsToMonitor && event.eventType in targetEvents) {

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
                "No usage events found since ${getDateTimeStrFromTimestamp(startTimestamp)}"
            )
        }

    } catch (e: Exception) {
        DebugLogger.d("getLastPhoneActivity", "Failed getting last phone activity?!", e)
    }
    return lastInteractiveEvent
}


fun doAlertCheck(context: Context, alarmStage: String) {
    val prefs = getEncryptedSharedPreferences(context)

    // get the necessary preferences
    val checkPeriodHours = prefs.getString("time_period_hours", "12")!!.toFloat()
    val followupPeriodMinutes = prefs.getString("followup_time_period_minutes", "60")!!.toLong()
    val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(prefs,"REST_PERIODS")
    val appsToMonitor: MutableList<MonitoredAppInfo> = loadJSONSharedPreference(prefs,"APPS_TO_MONITOR")

    // time in the system default timezone, which is what the rest period will be in
    val nowCalendar = Calendar.getInstance()

    // time in milliseconds since epoch, store this so we use the same time for everything
    val nowTimestamp = System.currentTimeMillis()

    Log.d(
        "doPeriodicCheck",
        "check period is $checkPeriodHours hours, " +
                "followup period is $followupPeriodMinutes minutes."
    )

    // assume no rest periods and default to searching for activity starting at
    //  the beginning of the check period
    var activitySearchStartTimestamp = (nowTimestamp - (checkPeriodHours * 1000 * 60 * 60)).toLong()
    var isInRestPeriod = false

    Log.d("doPeriodicCheck", "activity search start timestamp is " +
            getDateTimeStrFromTimestamp(activitySearchStartTimestamp)
    )

    // if there is a rest period set, determine whether we are currently in a rest period and
    //  adjust the activity search start time accordingly
    if (restPeriods.isNotEmpty()) {

        // get whether we are currently in a rest period
        isInRestPeriod = isWithinRestPeriod(nowCalendar.get(Calendar.HOUR_OF_DAY),
            nowCalendar.get(Calendar.MINUTE), restPeriods[0])

        // get a date in the past that is checkPeriodHours ago while excluding any rest periods
        //  and then convert it to a timestamp
        activitySearchStartTimestamp = calculatePastDateTimeExcludingRestPeriod(
            nowCalendar, checkPeriodHours, restPeriods[0]
        ).timeInMillis

        Log.d("doPeriodicCheck", "updating activity search start timestamp to " +
                getDateTimeStrFromTimestamp(activitySearchStartTimestamp))
    }

    // double check that there is still no recent user activity
    val lastInteractiveEvent = getLastPhoneActivity(
        context, activitySearchStartTimestamp, appsToMonitor.map { it.packageName }
    )

    // if this is the final alarm and there is no recent activity then we need to send the alert
    // DO NOT CHECK FOR REST PERIOD HERE; final alarms should never be set during
    //  a rest period so we can assume that the 'are you there?' check was done
    //  outside of a rest period and so we should still send the alert
    if (alarmStage == "final" && lastInteractiveEvent == null) {
        sendAlert(context, prefs)

        // if auto restart is enabled
        if (prefs.getBoolean("auto_restart_monitoring", false)) {

            // we can't set the alarm using last activity otherwise it would immediately fire an
            //  'are you there?' check so just base it on the checkPeriodHours and the rest periods
            setAlarm(context, (checkPeriodHours * 60 * 60 * 1000).toLong(), "periodic", restPeriods)
        }

        return
    }

    // if no events were found then we need to send the followup notification and set a final alarm
    // make sure we aren't in a rest period though because we should never initiate the
    //  'are you there?' check during a rest period
    if (lastInteractiveEvent == null && !isInRestPeriod) {
        DebugLogger.d(
            "doPeriodicCheck",
            "no events found in the last $checkPeriodHours hours, sending Are you there? notification"
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
        // do not adjust the followup time based on the rest periods
        setAlarm(context, (followupPeriodMinutes * 60 * 1000), "final", null)

    } else {
        // use the last event timestamp if we found one, otherwise it means we are in a rest period
        //  so assume that the last activity was checkPeriodHours ago so that the new alarm
        //  gets set for the end of the current rest period
        val lastInteractiveEventTimestamp = lastInteractiveEvent?.timeStamp ?: (nowTimestamp - (checkPeriodHours * 60 * 60 * 1000)).toLong()

        // this just here for informational purposes
        val lastEventMsAgo = nowTimestamp - lastInteractiveEventTimestamp
        Log.d("doPeriodicCheck", "last event was ${lastEventMsAgo / 1000} seconds ago")

        // since now know when the last activity was we can set our next alarm for the
        //  exact time we need to check again
        val newAlarmTimestamp = lastInteractiveEventTimestamp + (checkPeriodHours * 60 * 60 * 1000)

        // the milliseconds until our alarm would fire
        val newAlarmInMs = (newAlarmTimestamp - nowTimestamp).toLong()

        // set a new alarm so we can check again in the future
        setAlarm(context, newAlarmInMs, "periodic", restPeriods)
    }
}

fun sendAlert(context: Context, prefs: SharedPreferences) {
    DebugLogger.d("sendAlert","Sending alert!")

    // cancel the 'Are you there?' notification
    AlertNotificationHelper(context).cancelNotification(
        AppController.ARE_YOU_THERE_NOTIFICATION_ID
    )

    // send the alert messages
    val alertSender = AlertMessageSender(context)
    alertSender.sendAlertMessage()

    // only get the location if the user has enabled it for at least one
    if (prefs.getBoolean("location_enabled", false)) {

        // just add an extra layer try/catch in case anything unexpected
        //  happens when trying to get the location
        try {

            // attempt to get the location and then execute sendLocationAlertMessage
            val locationHelper = LocationHelper(context) { _, locationStr ->
                alertSender.sendLocationAlertMessage(locationStr)
            }
            locationHelper.getLocationAndExecute()

        } catch (e: Exception) {

            // if we fail for any reason then send the alert messages
            DebugLogger.d("sendAlert", "Failed while attempting to get and send location alert", e)
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