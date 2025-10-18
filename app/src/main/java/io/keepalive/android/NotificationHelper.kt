package io.keepalive.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat


class AlertNotificationHelper(private val context: Context) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {

        // create a channel for each notification type
        // notification channels are only available in API 26+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            // the 'Are you there?' notification
            createNotificationChannel(
                AppController.ARE_YOU_THERE_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.default_notification_channel_title),
                context.getString(R.string.default_notification_channel_description)
            )

            // notification sent when an SMS Alert is sent
            createNotificationChannel(
                AppController.SMS_SENT_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.sms_sent_notification_channel_title),
                context.getString(R.string.sms_sent_notification_channel_description)
            )

            // notification sent when a Phone Call Alert is sent
            createNotificationChannel(
                AppController.CALL_SENT_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.call_sent_notification_channel_title),
                context.getString(R.string.call_sent_notification_channel_description)
            )

            // service notification used when an alert is being sent
            createNotificationChannel(
                AppController.ALERT_SERVICE_NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.alert_service_notification_channel_title),
                context.getString(R.string.alert_service_notification_channel_description)
            )

            if (BuildConfig.INCLUDE_WEBHOOK) {
                // notification sent when a Webhook Alert is sent
                createNotificationChannel(
                    AppController.WEBHOOK_SENT_NOTIFICATION_CHANNEL_ID,
                    context.getString(R.string.webhook_sent_notification_channel_title),
                    context.getString(R.string.webhook_sent_notification_channel_description)
                )
            }
        }
    }

    companion object {
        operator fun invoke(context: Context): AlertNotificationHelper {
            return AlertNotificationHelper(context)
        }
    }

    @SuppressLint("NewApi")
    private fun createNotificationChannel(
        channelId: String,
        channelTitle: String,
        channelDesc: String
    ) {

        // ONCE THESE SETTINGS ARE SET WE CAN'T MAKE THEM MORE INTRUSIVE UNLESS WE REINSTALL
        //  THE APP ENTIRELY OR ASK THE USER TO GO INTO SETTINGS AND ADJUST THEM MANUALLY
        // ALSO, THE USER COULD COME IN AT ANY TIME AND ADJUST THE SETTINGS AND WE WOULDN'T KNOW

        // check if the channel already exists
        if (notificationManager.getNotificationChannel(channelId) != null) {
            Log.d("createNotifyChannel", "Notification channel already exists for $channelId")
            return
        }

        // this controls the notification settings for the app that can be viewed in the phone settings
        val channel = NotificationChannel(
            channelId,

            // this is what is displayed in the Notification section in the app Settings
            channelTitle,

            NotificationManager.IMPORTANCE_HIGH
        )

        // when a particular notification channel is clicked on it will display this as subtext
        channel.description = channelDesc

        // this works
        channel.setShowBadge(true)
        channel.canShowBadge()

        // couldn't seem to get this to work?
        channel.enableLights(true)
        channel.lightColor = Color.RED

        // this works
        channel.enableVibration(true)
        channel.vibrationPattern = longArrayOf(100, 200, 300, 400, 500)

        // Register the channel with the system
        notificationManager.createNotificationChannel(channel)

        Log.d(
            "createNotifyChannel", "Creating notification channel $channelId: " +
                    "sound: ${channel.sound}, priority ${channel.importance}, " +
                    "vibrate: ${channel.vibrationPattern}, lights: ${channel.lightColor}, " +
                    "badge: ${channel.canShowBadge()}, lockscreen: ${channel.lockscreenVisibility}"
        )

        if (!notificationManager.areNotificationsEnabled()) {
            Log.d("notificationManager", "unable to create notifications?!")
        }
    }

    private fun notificationExists(notificationId: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return notificationManager.activeNotifications.any { it.id == notificationId }
        }
        return false
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun sendNotification(title: String, content: String, notificationId: Int, overwrite: Boolean = false) {
        try {
            Log.d(
                "sendNotification",
                "Sending notification: $title, $content"
            )

            // make sure we have notification permissions on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("sendNotification", "No notification permissions!")
                return
            }

            if (notificationExists(notificationId) && !overwrite) {
                Log.d("sendNotification", "Notification already exists!")
                return
            }

            // start MainActivity if they click on the notification
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

            // if this is the 'Are you there?; notification,  put an extra on the intent
            //  so if the notification is clicked on we can take the appropriate action
            if (notificationId == AppController.ARE_YOU_THERE_NOTIFICATION_ID) {
                intent.putExtra("AlertCheck", true)
            }

            // need to have FLAG_UPDATE_CURRENT or the extras won't be updated
            val pendingIntent: PendingIntent =
                PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

            // get the correct channel id based on which notification id this is
            val notificationChannelId = when (notificationId) {
                AppController.ARE_YOU_THERE_NOTIFICATION_ID -> AppController.ARE_YOU_THERE_NOTIFICATION_CHANNEL_ID
                AppController.SMS_ALERT_SENT_NOTIFICATION_ID -> AppController.SMS_SENT_NOTIFICATION_CHANNEL_ID
                AppController.CALL_ALERT_SENT_NOTIFICATION_ID -> AppController.CALL_SENT_NOTIFICATION_CHANNEL_ID
                AppController.ALERT_SERVICE_NOTIFICATION_ID -> AppController.ALERT_SERVICE_NOTIFICATION_CHANNEL_ID
                AppController.WEBHOOK_ALERT_SENT_NOTIFICATION_ID -> AppController.WEBHOOK_SENT_NOTIFICATION_CHANNEL_ID
                else -> AppController.ARE_YOU_THERE_NOTIFICATION_CHANNEL_ID
            }

            // notification channels were added in API 26
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, notificationChannelId)
            } else {
                // settings are normally controlled via the channels so without them we have to
                //  set whatever we need to here
                Notification.Builder(context)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setVibrate(longArrayOf(100, 200, 300, 400, 500))
                    .setLights(Color.RED, 300, 100)
            }

            builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(content)

                // recent API versions auto expand if the text is too long but older ones don't?
                // regardless, we can set this everytime and it will only expand if needed?
                .setStyle(Notification.BigTextStyle().bigText(content))

                // Set the intent that will fire when the user taps the notification
                .setContentIntent(pendingIntent)

                // auto close the notification when it is touched
                .setAutoCancel(true)

            notificationManager.notify(notificationId, builder.build())

        } catch (e: Exception) {
            Log.e("sendNotification", "Failed sending notification?!", e)
        }
    }
}