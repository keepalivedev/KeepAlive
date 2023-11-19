package io.keepalive.android.receivers


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.keepalive.android.*


class AlarmReceiver : BroadcastReceiver() {

    private val tag = this.javaClass.name

    private fun getAlarmStage(intent: Intent): String {
        var alarmStage = ""

        // the current alarm stage should be passed in as an extra
        intent.extras?.let {

            alarmStage = it.getString("AlarmStage", "initial")

            Log.d(tag, "Alarm stage is $alarmStage")

            // check when the alarm was supposed to go off and compare to
            //   when it actually did go off
            val alarmTimestamp = it.getLong("AlarmTimestamp", 0)

            if (alarmTimestamp != 0L) {

                val alarmDtStr = getDateTimeStrFromTimestamp(alarmTimestamp)
                val currentDtStr = getDateTimeStrFromTimestamp(System.currentTimeMillis())

                Log.d(tag, "Time is $currentDtStr, alarm was supposed to go off at $alarmDtStr")

                val timeAgo = (System.currentTimeMillis() - alarmTimestamp) / 1000

                Log.d(tag, "Alarm was supposed to go off $timeAgo seconds ago?!")
            }
        }
        return alarmStage
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.d(tag, "Alarm just fired!")

            val prefs = getEncryptedSharedPreferences(context)

            // shouldn't need to check whether the app is still enabled here because
            //  we will cancel the alarm when the setting is changed

            // take action depending on what stage the alarm is in
            when (getAlarmStage(intent)) {

                // if this is the periodic check then we need to check for recent activity
                "periodic" -> {

                    // todo should we check for permissions whenever we do a periodic check?

                    val haveAllPerms = PermissionManager(context, null).checkHavePermissions()

                    if (!haveAllPerms) {
                        Log.d(tag, "We still need some permissions?!")
                    }

                    // check for recent activity and take action appropriately
                    doPeriodicCheck(context)
                }

                "final" -> {

                    // double check that there is still no recent user activity
                    val lastInteractiveEvent = getLastPhoneActivity(
                        context, prefs.getString("time_period_hours", "12")!!.toFloat()
                    )

                    // if there is still no recent activity then we need to send the alert
                    if (lastInteractiveEvent == null) {
                        Log.d(
                            tag,
                            "This is the final stage alarm and still no activity! Sending alert!!!"
                        )

                        // cancel the 'Are you there?' notification
                        AlertNotificationHelper(context).cancelNotification(
                            AppController.ARE_YOU_THERE_NOTIFICATION_ID
                        )

                        // only get the location if the user has enabled it for at least one
                        if (prefs.getBoolean("location_enabled", false)) {

                            // just add an extra layer try/catch in case anything unexpected
                            //  happens when trying to get the location
                            try {

                                // attempt to get the location and pass it to sendAlertMessages
                                val locationHelper =
                                    LocationHelper(context, ::sendAlertMessages)
                                locationHelper.getLocationAndExecute()

                            } catch (e: Exception) {

                                // if we fail for any reason then send the alert messages
                                Log.e(tag, "Failed to get location:", e)
                                sendAlertMessages(
                                    context,
                                    context.getString(R.string.location_invalid_message)
                                )
                            }
                        } else {

                            // if location isn't enabled then just send the alert
                            sendAlertMessages(context, "")
                        }

                        // also make the phone call (if enabled)
                        makeAlertCall(context)

                        // update prefs to include when the alert was sent
                        with(prefs.edit()) {
                            putLong("LastAlertAt", System.currentTimeMillis())
                            apply()
                        }

                        // this will get hit if the user locked or unlocked their devices but didn't
                        //  click on the 'Are you there notification?', in which case we just need
                        //  to reset the alarm
                    } else {

                        Log.d(
                            tag,
                            "Last interactive event not null? we're alive now? $lastInteractiveEvent"
                        )

                        doPeriodicCheck(context)
                    }
                }

                else -> {
                    Log.d(tag, "Unknown alarm stage?!")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed while processing alarm:", e)
        }
    }
}