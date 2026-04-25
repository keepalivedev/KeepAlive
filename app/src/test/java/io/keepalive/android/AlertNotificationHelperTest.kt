package io.keepalive.android

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests [AlertNotificationHelper]: channel creation, dedup, cancel, and
 * the "Are you there?" branch that makes the notification ongoing + non-auto-cancel.
 *
 * Matrix covers notification channels (API O/26+), POST_NOTIFICATIONS runtime
 * permission check (API T/33+), and the pre-O branch that uses priority/defaults
 * directly instead of a channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 33, 34, 35])
class AlertNotificationHelperTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before fun setUp() {
        // Robolectric denies POST_NOTIFICATIONS by default on Tiramisu+, which
        // makes sendNotification() silently no-op. Grant it for all tests.
        shadowOf(appCtx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Clean any notifications from previous tests
        nm.cancelAll()
    }

    @Test
    @Config(sdk = [28, 33, 34, 35])  // notification channels are API O (26)+
    fun `constructor creates all expected channels on API 26+`() {
        AlertNotificationHelper(appCtx)

        val channelIds = nm.notificationChannels.map { it.id }.toSet()
        assertTrue(AppController.ARE_YOU_THERE_NOTIFICATION_CHANNEL_ID in channelIds)
        assertTrue(AppController.SMS_SENT_NOTIFICATION_CHANNEL_ID in channelIds)
        assertTrue(AppController.CALL_SENT_NOTIFICATION_CHANNEL_ID in channelIds)
        assertTrue(AppController.ALERT_SERVICE_NOTIFICATION_CHANNEL_ID in channelIds)
    }

    @Test
    @Config(sdk = [23])  // pre-O: channels don't exist yet
    fun `constructor does not crash on API 23 (pre-O, no notification-channel subsystem)`() {
        // The whole notification-channel subsystem is API O+. On 23 the
        // constructor must silently skip channel creation. Verified by
        // constructing the helper without throwing — getNotificationChannels()
        // isn't available to check directly pre-O.
        AlertNotificationHelper(appCtx)  // must not throw
    }

    @Test fun `sendNotification posts with the right title and text`() {
        AlertNotificationHelper(appCtx).sendNotification(
            "Alert Title",
            "Alert body text",
            AppController.SMS_ALERT_SENT_NOTIFICATION_ID
        )

        val active = shadowOf(nm).allNotifications
        assertEquals(1, active.size)
        val notif = active[0]
        val extras = notif.extras
        assertEquals("Alert Title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Alert body text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun `sendNotification is deduplicated by notification id`() {
        val helper = AlertNotificationHelper(appCtx)
        helper.sendNotification("T1", "B1", AppController.SMS_ALERT_SENT_NOTIFICATION_ID)
        helper.sendNotification("T2", "B2", AppController.SMS_ALERT_SENT_NOTIFICATION_ID)

        val active = shadowOf(nm).allNotifications
        assertEquals("second call with the same id should be skipped (overwrite=false default)",
            1, active.size)
        assertEquals("T1", active[0].extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test fun `sendNotification with overwrite=true replaces existing`() {
        val helper = AlertNotificationHelper(appCtx)
        helper.sendNotification("T1", "B1", AppController.WEBHOOK_ALERT_SENT_NOTIFICATION_ID)
        helper.sendNotification("T2", "B2", AppController.WEBHOOK_ALERT_SENT_NOTIFICATION_ID, overwrite = true)

        val active = shadowOf(nm).allNotifications
        assertEquals(1, active.size)
        // On API 24+ posting to same id replaces; the latest content wins.
        assertEquals("T2", active[0].extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test fun `cancelNotification removes an active notification`() {
        val helper = AlertNotificationHelper(appCtx)
        helper.sendNotification("Title", "Body", AppController.SMS_ALERT_SENT_NOTIFICATION_ID)
        assertEquals(1, shadowOf(nm).allNotifications.size)

        helper.cancelNotification(AppController.SMS_ALERT_SENT_NOTIFICATION_ID)

        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test fun `are-you-there notification is ongoing and does not auto-cancel`() {
        AlertNotificationHelper(appCtx).sendNotification(
            "Are you there?",
            "Please respond",
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )

        val notif = shadowOf(nm).allNotifications.single()
        val ongoing = (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val autoCancel = (notif.flags and Notification.FLAG_AUTO_CANCEL) != 0
        assertTrue("are-you-there must be ongoing so it can't be swiped away", ongoing)
        assertFalse("are-you-there must NOT auto-cancel on tap; ack is explicit", autoCancel)
    }

    @Test fun `non-are-you-there notification auto-cancels on tap`() {
        AlertNotificationHelper(appCtx).sendNotification(
            "Sent",
            "SMS delivered",
            AppController.SMS_ALERT_SENT_NOTIFICATION_ID
        )

        val notif = shadowOf(nm).allNotifications.single()
        val autoCancel = (notif.flags and Notification.FLAG_AUTO_CANCEL) != 0
        assertTrue("status notifications auto-cancel on tap", autoCancel)
    }

    @Test fun `notification has a tappable PendingIntent (contentIntent)`() {
        AlertNotificationHelper(appCtx).sendNotification(
            "Alert",
            "Body",
            AppController.SMS_ALERT_SENT_NOTIFICATION_ID
        )

        val notif = shadowOf(nm).allNotifications.single()
        assertNotNull("notification must have a contentIntent so user can open the app",
            notif.contentIntent)
    }
}
