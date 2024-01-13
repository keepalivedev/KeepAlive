package io.keepalive.android.receivers


import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.keepalive.android.*


class AlarmReceiver : BroadcastReceiver() {

    private val tag = this.javaClass.name

    override fun onReceive(context: Context, intent: Intent) {
        try {
            DebugLogger.d(tag, "Alarm just fired!")

            // shouldn't need to check whether the app is still enabled here because
            //  we will cancel the alarm when the setting is changed

            /*
            if (!PermissionManager(context, null).checkHavePermissions()) {
                Log.d(tag, "We still need some permissions?!")
            }
            */

            // get the current alarm stage from the intent extras
            val alarmStage = getAlarmStage(intent)

            // take action depending on what alarm stage it is
            doAlertCheck(context, alarmStage)

        } catch (e: Exception) {
            Log.e(tag, "Failed while processing alarm:", e)
        }
    }

    private fun getAlarmStage(intent: Intent): String {
        var alarmStage = ""

        // the current alarm stage should be passed in as an extra
        intent.extras?.let {

            alarmStage = it.getString("AlarmStage", "periodic")
            DebugLogger.d(tag, "Alarm stage is $alarmStage")

            // also check when the alarm was supposed to go off and compare to
            //   when it actually did go off
            val alarmTimestamp = it.getLong("AlarmTimestamp", 0)

            // this is just for informational purposes so we can see how well Android respects
            //  the alarm time we set...
            if (alarmTimestamp != 0L) {

                val alarmDtStr = getDateTimeStrFromTimestamp(alarmTimestamp)
                val currentDtStr = getDateTimeStrFromTimestamp(System.currentTimeMillis())
                val timeAgo = (System.currentTimeMillis() - alarmTimestamp) / 1000

                DebugLogger.d(tag, "Time is $currentDtStr, alarm was supposed to go off at $alarmDtStr, " +
                        "which was $timeAgo seconds ago")
            }
        }
        return alarmStage
    }
}