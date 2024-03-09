package io.keepalive.android

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log


class AppController : Application() {
    companion object {
        const val TAG = "AppController"

        // notification channel IDs
        const val ARE_YOU_THERE_NOTIFICATION_CHANNEL_ID = "AlertNotificationChannel"
        const val CALL_SENT_NOTIFICATION_CHANNEL_ID = "CallSentNotificationChannel"
        const val SMS_SENT_NOTIFICATION_CHANNEL_ID = "SMSSentNotificationChannel"

        // notification IDs
        const val ARE_YOU_THERE_NOTIFICATION_ID = 1
        const val SMS_ALERT_SENT_NOTIFICATION_ID = 2
        const val CALL_ALERT_SENT_NOTIFICATION_ID = 3
        const val SMS_ALERT_FAILURE_NOTIFICATION_ID = 4

        // when doing a sanity check to see if we can see ANY events, this is the # of hours
        //  to use with getLastDeviceActivity().  if the user has a higher value set it
        //  will use that instead
        const val LAST_ACTIVITY_MAX_PERIOD_CHECK_HOURS = 48F

        // according to the docs we shouldn't set alarms for less than 10 minutes?
        const val ALARM_MINIMUM_TIME_PERIOD_MINUTES = 10

        // max SMS length as defined by the OS?
        const val SMS_MESSAGE_MAX_LENGTH = 160

        // request code used with AlarmReceiver intent
        const val ACTIVITY_ALARM_REQUEST_CODE = 99

        // request code used with AppHibernationActivity intent
        const val APP_HIBERNATION_ACTIVITY_RESULT_CODE = 98

        // we have to check this several times so use a variable so its more clear what its for
        const val MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK = Build.VERSION_CODES.P
    }

    override fun onCreate() {
        super.onCreate()

        DebugLogger.initialize(this)

        DebugLogger.d(TAG, getString(R.string.debug_log_starting_up))

        // alternative is to check BuildConfig.DEBUG?
        if ((this.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            Log.d(TAG, "We're in debug mode?")
        }
    }
}