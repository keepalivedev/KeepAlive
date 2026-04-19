package io.keepalive.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.TimeZone

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
