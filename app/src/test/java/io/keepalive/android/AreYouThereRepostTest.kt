package io.keepalive.android

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.receivers.AreYouThereRepostReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Android plays every notification sound through one shared player, so the next
 * notification with a sound stops the prompt's repeating sound for good. Posting
 * the prompt again brings it back, which [AreYouThereRepost] does on a timer for
 * as long as the prompt is waiting for an answer.
 *
 * Starts at API 23 because the active notifications can't be read before that,
 * see the instrumented matrix for API 22.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 33, 35, 36])
class AreYouThereRepostTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarm get() = shadowOf(alarmManager)

    @Before fun setUp() {
        shadowOf(appCtx as Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.USE_FULL_SCREEN_INTENT
        )
        nm.cancelAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        getAppSharedPreferences(appCtx).edit()
            .putBoolean(PrefKeys.ENABLED, true)
            .putBoolean(PrefKeys.ARE_YOU_THERE_OVERLAY_ENABLED, true)
            .commit()
        saveStage("final")
    }

    private fun saveStage(stage: String) {
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, stage)
            .commit()
    }

    private fun postPrompt() {
        AlertNotificationHelper(appCtx).sendNotification(
            "Are you there?",
            "Please respond",
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )
    }

    private fun postedPrompt(): Notification? =
        shadowOf(nm).getNotification(null, AppController.ARE_YOU_THERE_NOTIFICATION_ID)

    // the alarm that posting the prompt set, so a test only sees what the receiver does
    private fun fireRepost() {
        AreYouThereRepost.cancel(appCtx)
        AreYouThereRepostReceiver().onReceive(appCtx, Intent())
    }

    @Test fun `posting the prompt starts the repeat timer`() {
        postPrompt()

        assertEquals(1, shadowAlarm.scheduledAlarms.size)
    }

    @Test fun `posting any other notification does not start the timer`() {
        AlertNotificationHelper(appCtx).sendNotification(
            "Sent", "SMS delivered", AppController.SMS_ALERT_SENT_NOTIFICATION_ID
        )

        assertEquals(0, shadowAlarm.scheduledAlarms.size)
    }

    @Test
    @Config(sdk = [28, 33])  // Robolectric can't grant the full-screen intent on 34+
    fun `the repost has no full-screen intent`() {
        postPrompt()
        assertNotNull("this test needs a prompt that has one", postedPrompt()?.fullScreenIntent)

        fireRepost()

        assertNull("waking the screen every minute could count as the user being active",
            postedPrompt()?.fullScreenIntent)
    }

    @Test fun `the repost keeps the sound repeating and the prompt ongoing`() {
        postPrompt()

        fireRepost()

        val flags = postedPrompt()!!.flags
        assertTrue("the sound must keep repeating", (flags and Notification.FLAG_INSISTENT) != 0)
        assertTrue("the prompt must stay ongoing", (flags and Notification.FLAG_ONGOING_EVENT) != 0)
    }

    @Test fun `each repost sets the next one about a minute out`() {
        postPrompt()
        val before = System.currentTimeMillis()

        fireRepost()

        val trigger = shadowAlarm.nextScheduledAlarm!!.alarmClockInfo!!.triggerTime
        assertTrue("was ${trigger - before}ms out", trigger - before in 55_000L..65_000L)
    }

    @Test fun `an answered prompt ends the reposts`() {
        // cancelled without the alarm being touched, the alarm could already be on its way
        postPrompt()
        nm.cancel(AppController.ARE_YOU_THERE_NOTIFICATION_ID)

        fireRepost()

        assertNull("an answered prompt must never come back", postedPrompt())
        assertEquals(0, shadowAlarm.scheduledAlarms.size)
    }

    @Test fun `reposts end when the alert has gone out`() {
        postPrompt()
        saveStage("alert_sent")
        val posted = postedPrompt()

        fireRepost()

        assertTrue("the prompt must not be posted again", posted === postedPrompt())
        assertEquals(0, shadowAlarm.scheduledAlarms.size)
    }

    @Test fun `reposts end when the stage moved on`() {
        postPrompt()
        saveStage("periodic")

        fireRepost()

        assertEquals(0, shadowAlarm.scheduledAlarms.size)
    }

    @Test fun `reposts end when monitoring is off`() {
        postPrompt()
        getAppSharedPreferences(appCtx).edit().putBoolean(PrefKeys.ENABLED, false).commit()

        fireRepost()

        assertEquals(0, shadowAlarm.scheduledAlarms.size)
    }

    @Test fun `dismissing the prompt cancels the pending repost`() {
        postPrompt()
        assertEquals(1, shadowAlarm.scheduledAlarms.size)

        AcknowledgeAreYouThere.dismissPrompt(appCtx)

        assertEquals("a pending repost keeps the alarm icon up and wakes the app for nothing",
            0, shadowAlarm.scheduledAlarms.size)
    }

    @Test
    @Config(sdk = [33, 35, 36])  // the exact alarm permission is API 31+
    fun `the timer still runs without the exact alarm permission`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        postPrompt()

        assertEquals("setAlarmClock would throw here and take the prompt's timer with it",
            1, shadowAlarm.scheduledAlarms.size)
        val alarm = shadowAlarm.nextScheduledAlarm!!
        assertNull(alarm.alarmClockInfo)
        assertFalse("the final alarm needs the idle quota that this would use up", alarm.allowWhileIdle)
    }
}
