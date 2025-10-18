package io.keepalive.android

import android.Manifest
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.TimeZone

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


class AlertMessageSender(private val context: Context) {
    private val smsManager = getSMSManager(context)
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

                // this shouldn't be able to happen either but just in case...
                if (contact.alertMessage.isEmpty()) {
                    Log.d("sendAlertMessage", "Alert message is blank, skipping...")
                    continue
                }

                DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sending_text_message_to, maskPhoneNumber(contact.phoneNumber)))

                // add try/catch here to be extra safe in case there is an issue
                //  registering the receiver...
                try {

                    // do this for each contact instead of once at the beginning because
                    //  the SMSSentReceiver will unregister it when it gets called
                    // add a receiver for the SMS sent intent so we will get notified of the result
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                        // registerReceiver requires API 26+ but requires the use of
                        //  Context.RECEIVER_NOT_EXPORTED, which isn't available until API 33...
                        context.applicationContext.registerReceiver(
                            SMSSentReceiver(),
                            IntentFilter("SMS_SENT"),
                            Context.RECEIVER_NOT_EXPORTED
                        )

                    } else {
                        ContextCompat.registerReceiver(
                            context.applicationContext,
                            SMSSentReceiver(),
                            IntentFilter("SMS_SENT"),
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                    }
                }
                catch (e: Exception) {
                    DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_failed_registering_sms_sent_receiver), e)
                }

                try {

                    // if there is a test message, send it first
                    if (testWarningMessage != "") {
                        DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sending_warning_sms, maskPhoneNumber(contact.phoneNumber)))

                        // don't include a sentIntent for the test message
                        smsManager.sendTextMessage(contact.phoneNumber, null, testWarningMessage, null, null)
                    }

                    // divide the message into parts based on how long it is
                    // messages with unicode characters have a shorter max length
                    val messageParts = smsManager.divideMessage(contact.alertMessage)
                    Log.d("sendAlertMessage", "Message parts: $messageParts")

                    // only use sendMultipartTextMessage if there is more than 1 part
                    if (messageParts.size > 1) {
                        DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sending_multipart_sms, messageParts.size))

                        // create an array with the same pending intent for each part
                        val sentPIList = ArrayList<PendingIntent>()
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
            } else {
                DebugLogger.d("sendAlertMessage", context.getString(R.string.debug_log_sms_phone_blank))
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

// trying to find when the phone was last locked or unlocked
fun getLastDeviceActivity(context: Context, startTimestamp: Long, monitoredApps: List<String>? = null): UsageEvents.Event? {
    var lastInteractiveEvent: UsageEvents.Event? = null

    // todo check for permissions usage stats permissions? this will fail silently and not
    //  return any events if we don't have permissions
    try {

        DebugLogger.d("getLastDeviceActivity", context.getString(R.string.debug_log_checking_for_activity, getDateTimeStrFromTimestamp(startTimestamp, TimeZone.getDefault().id)))

        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        // get all events between the starting timestamp and now
        val events = usageStatsManager.queryEvents(startTimestamp, System.currentTimeMillis())

        var appsToMonitor = monitoredApps
        val targetEvents: MutableList<Int> = mutableListOf()

        // create a map to translate the event types to strings
        val activityMap: MutableMap<Int, String> = mutableMapOf()

        Log.d("getLastDeviceActivity", "Checking for monitored apps: ${appsToMonitor == null} $appsToMonitor")

        // if no apps were passed in then use the package for monitoring lock/unlock events
        if (appsToMonitor.isNullOrEmpty() && Build.VERSION.SDK_INT >= AppController.MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK) {

            DebugLogger.d("getLastDeviceActivity", context.getString(R.string.debug_log_checking_for_system_events))
            appsToMonitor = listOf("android")

            // KEYGUARD_HIDDEN seems to fire when the phone is unlocked?? and KEYGUARD_SHOWN when
            //  the phone is locked?? doesn't seem to fire when something else wakes the screen up,
            //  like SCREEN_INTERACTIVE does...  it also fires even if using finger print to unlock?
            targetEvents.add(UsageEvents.Event.KEYGUARD_HIDDEN)
            targetEvents.add(UsageEvents.Event.KEYGUARD_SHOWN)

            activityMap[UsageEvents.Event.KEYGUARD_HIDDEN] = "KEYGUARD_HIDDEN"
            activityMap[UsageEvents.Event.KEYGUARD_SHOWN] = "KEYGUARD_SHOWN"

        } else {
            // this is deprecated in API 29 but still seems to be in use?
            targetEvents.add(UsageEvents.Event.MOVE_TO_FOREGROUND)

            activityMap[UsageEvents.Event.MOVE_TO_FOREGROUND] = "MOVE_TO_FOREGROUND"

            // when testing under API 34 this never seems to fire, apps still sending MOVE_TO_FOREGROUND
            // if it is available we should still check it though
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                targetEvents.add(UsageEvents.Event.ACTIVITY_RESUMED)

                activityMap[UsageEvents.Event.ACTIVITY_RESUMED] = "ACTIVITY_RESUMED"
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

            DebugLogger.d("getLastDeviceActivity",
                context.getString(R.string.debug_log_last_device_activity,
                    activityMap[lastInteractiveEvent.eventType],
                    lastInteractiveEvent.packageName,
                    getDateTimeStrFromTimestamp(lastInteractiveEvent.timeStamp)))

        } else {
            // todo make this a debuglogger message
            Log.d(
                "getLastDeviceActivity",
                "No usage events found since ${getDateTimeStrFromTimestamp(startTimestamp)}"
            )
        }

    } catch (e: Exception) {
        DebugLogger.d("getLastDeviceActivity", context.getString(R.string.debug_log_failed_getting_last_phone_activity), e)
    }
    return lastInteractiveEvent
}


fun doAlertCheck(context: Context, alarmStage: String) {

    DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_doing_alert_check, alarmStage))

    val prefs = getEncryptedSharedPreferences(context)

    // get the necessary preferences
    val checkPeriodHours = prefs.getString("time_period_hours", "12")?.toFloatOrNull() ?: 12f
    val followupPeriodMinutes = prefs.getString("followup_time_period_minutes", "60")?.toIntOrNull() ?: 60
    val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(prefs,"REST_PERIODS")
    val appsToMonitor: MutableList<MonitoredAppDetails> = loadJSONSharedPreference(prefs,"APPS_TO_MONITOR")

    // time in the system default timezone, which is what the rest period will be in
    val nowCalendar = Calendar.getInstance()

    Log.d("doAlertCheck", "current local time is ${getDateTimeStrFromTimestamp(nowCalendar.timeInMillis, nowCalendar.timeZone.id)}")

    // time in milliseconds since epoch, store this so we use the same time for everything
    val nowTimestamp = System.currentTimeMillis()

    Log.d(
        "doAlertCheck",
        "check period is $checkPeriodHours hours, " +
                "followup period is $followupPeriodMinutes minutes."
    )

    // assume no rest periods and default to searching for activity starting at
    //  the beginning of the check period
    var activitySearchStartTimestamp = (nowTimestamp - (checkPeriodHours * 1000 * 60 * 60)).toLong()
    var isInRestPeriod = false

    Log.d("doAlertCheck", "activity search start timestamp is " +
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
        activitySearchStartTimestamp = calculateOffsetDateTimeExcludingRestPeriod(
            nowCalendar, (checkPeriodHours * 60).toInt(), restPeriods[0], "backward"
        ).timeInMillis

        Log.d("doAlertCheck", "updating activity search start timestamp to " +
                getDateTimeStrFromTimestamp(activitySearchStartTimestamp))
    }

    // double check that there is still no recent user activity
    val lastInteractiveEvent = getLastDeviceActivity(
        context, activitySearchStartTimestamp, appsToMonitor.map { it.packageName }
    )

    // if this is the final alarm and there is no recent activity then we need to send the alert
    // DO NOT CHECK FOR REST PERIOD HERE; final alarms should never be set during
    //  a rest period so we can assume that the 'are you there?' check was done
    //  outside of a rest period and so we should still send the alert
    if (alarmStage == "final" && lastInteractiveEvent == null) {

        // start a service to send the alert to avoid being killed by the OS or causing an ANR
        Intent(context, AlertService::class.java).also { intent ->

            DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_alert_service_start))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // if auto restart is enabled
        if (prefs.getBoolean("auto_restart_monitoring", false)) {

            DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_auto_restart_monitoring))

            // we can't set the alarm using last activity otherwise it would immediately fire an
            //  'are you there?' check so just base it on the checkPeriodHours and the rest periods
            //setAlarm(context, (checkPeriodHours * 60 * 60 * 1000).toLong(), "periodic", restPeriods)
            setAlarm(context, nowTimestamp, (checkPeriodHours * 60).toInt(), "periodic", restPeriods)
        }
        return
    }

    // todo make this a debuglogger message
    if (lastInteractiveEvent == null && isInRestPeriod) {
        Log.d("doAlertCheck", "No events found but we are in a rest period, not sending alert")
    }

    // if no events were found then we need to send the followup notification and set a final alarm
    // make sure we aren't in a rest period though because we should never initiate the
    //  'are you there?' check during a rest period
    if (lastInteractiveEvent == null && !isInRestPeriod) {
        DebugLogger.d("doAlertCheck", context.getString(R.string.debug_log_no_events_sending_notification, checkPeriodHours))

        val alertNotificationHelper = AlertNotificationHelper(context)

        // send the 'Are you there?' notification
        alertNotificationHelper.sendNotification(
            context.getString(R.string.initial_check_notification_title),
            String.format(
                context.getString(R.string.initial_check_notification_text),
                followupPeriodMinutes.toString()
            ),
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )

        // if no events are found then set the alarm so we follow up; do not adjust the followup
        //  time based on the rest periods
        setAlarm(context, nowTimestamp, followupPeriodMinutes, "final", null)

    } else {
        // use the last event timestamp if we found one, otherwise it means we are in a rest period
        //  so assume that the last activity was checkPeriodHours ago so that the new alarm
        //  gets set for the end of the current rest period
        val lastInteractiveEventTimestamp = lastInteractiveEvent?.timeStamp ?: (nowTimestamp - (checkPeriodHours * 60 * 60 * 1000)).toLong()

        // this just here for informational purposes
        val lastEventMsAgo = nowTimestamp - lastInteractiveEventTimestamp
        Log.d("doAlertCheck", "last event was ${lastEventMsAgo / 1000} seconds ago")

        // set a new alarm so we can check again in the future
        setAlarm(context, lastInteractiveEventTimestamp, (checkPeriodHours * 60).toInt(),
            "periodic", restPeriods)
    }
}