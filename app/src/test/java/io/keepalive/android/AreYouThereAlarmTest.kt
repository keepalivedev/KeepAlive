package io.keepalive.android

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.receivers.AreYouThereRepostReceiver
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration

/**
 * As a notification sound the prompt is silent in silent mode, plays only in a headset
 * when one is connected, and stops for the volume keys and the notification shade. So
 * where it can, the app plays the sound itself, as an alarm, from a foreground service,
 * and the notification goes out without one. Where it can't, the notification makes the
 * sound as before.
 *
 * Two API levels plus the ones a test names, for the same reason as
 * [AreYouThereCallPauseTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class AreYouThereAlarmTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val app get() = shadowOf(appCtx as Application)
    private val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val appOps = appCtx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

    @Before fun setUp() {
        app.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        grantExactAlarms(true)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 5, 0)
        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL

        val deadline = System.currentTimeMillis() + 30 * 60_000L
        getAppSharedPreferences(appCtx).edit()
            .putBoolean(PrefKeys.ENABLED, true)
            .putBoolean(PrefKeys.ARE_YOU_THERE_OVERLAY_ENABLED, false)
            .commit()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, "final")
            .putLong(PrefKeys.NEXT_ALARM_TIMESTAMP, deadline)
            .commit()

        // these live in objects, which outlast a test
        AreYouThereCallPause.stop(appCtx)
        AreYouThereAlarm.stop(appCtx)
        AreYouThereAlarm.isPlaying = false
        idleMain()
        app.clearStartedServices()
    }

    @After fun tearDown() {
        AreYouThereAlarmService.soundFactory = { AlarmSound(it) }
        AreYouThereCallPause.stop(appCtx)
        AreYouThereAlarm.isPlaying = false
        idleMain()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    // "Alarms & reminders" for the app, which is an app-op from API 31
    private fun grantExactAlarms(granted: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        shadowOf(appOps).setMode(
            AppOpsManager.permissionToOp(Manifest.permission.SCHEDULE_EXACT_ALARM)!!,
            Process.myUid(),
            appCtx.packageName,
            if (granted) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED
        )
    }

    private fun alarmServiceStarted(): Boolean {
        var started = false
        while (true) {
            val intent = app.nextStartedService ?: break
            if (intent.component?.className == AreYouThereAlarmService::class.java.name) started = true
        }
        return started
    }

    private fun alarmServiceStopped(): Boolean {
        var stopped = false
        while (true) {
            val intent = app.nextStoppedService ?: break
            if (intent.component?.className == AreYouThereAlarmService::class.java.name) stopped = true
        }
        return stopped
    }

    private fun postPrompt() {
        AlertNotificationHelper(appCtx).sendNotification(
            "Are you there?",
            "Please respond",
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )
        idleMain()
    }

    private fun postedPrompt(): Notification? =
        shadowOf(nm).getNotification(null, AppController.ARE_YOU_THERE_NOTIFICATION_ID)

    private fun fireTick() {
        AreYouThereRepostReceiver().onReceive(appCtx, Intent())
        idleMain()
    }

    // what Android does when the app is in the background and no exact alarm just fired
    private fun <T> withStartsRefused(block: () -> T): T {
        mockkStatic(ContextCompat::class)
        try {
            every { ContextCompat.startForegroundService(any(), any()) } throws
                IllegalStateException("startForegroundService() not allowed")
            return block()
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }

    // ---- when the app makes the sound ---------------------------------------

    @Test fun `the app plays the alarm when it can`() {
        assertEquals(AreYouThereAlarm.Start.PLAYING, AreYouThereAlarm.start(appCtx))
        assertTrue(alarmServiceStarted())
    }

    @Test
    @Config(sdk = [34, 36])  // the service type is enforced from API 34
    fun `not without the alarms and reminders grant`() {
        // the service is of the type for continuing an alarm, and starting it without the
        //  grant takes the process down
        grantExactAlarms(false)

        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))
        assertFalse(alarmServiceStarted())
    }

    @Test
    @Config(sdk = [33, 36])  // the exact alarm permission is API 31+
    fun `not when exact alarms can't be scheduled`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))
        assertFalse(alarmServiceStarted())
    }

    @Test fun `not with alarms turned all the way down`() {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))
    }

    @Test fun `it rings in silent mode`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(AreYouThereAlarm.Start.PLAYING, AreYouThereAlarm.start(appCtx))

        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertEquals(AreYouThereAlarm.Start.PLAYING, AreYouThereAlarm.start(appCtx))
    }

    @Test fun `with the setting off silent mode is left to the notification`() {
        getAppSharedPreferences(appCtx).edit().putBoolean("alarm_in_silent_mode", false).commit()

        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))

        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        assertEquals("the setting is about silent mode only",
            AreYouThereAlarm.Start.PLAYING, AreYouThereAlarm.start(appCtx))
    }

    @Test fun `an alarm that is no longer wanted is stopped`() {
        AreYouThereAlarm.start(appCtx)
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        AreYouThereAlarm.start(appCtx)

        assertTrue(alarmServiceStopped())
    }

    // ---- the prompt's notification matches ----------------------------------

    @Test fun `with the alarm playing the notification makes no sound of its own`() {
        postPrompt()

        val posted = postedPrompt()!!
        assertEquals("the repeat flag is what loops the notification sound",
            0, posted.flags and Notification.FLAG_INSISTENT)
        assertTrue("or an update would pop it up over its own full-screen prompt",
            (posted.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertTrue(posted.extras.getBoolean(AreYouThereAlarm.EXTRA_APP_OWNED_SOUND))
        assertEquals(AppController.ARE_YOU_THERE_SILENT_NOTIFICATION_CHANNEL_ID, posted.channelId)

        val channel = nm.getNotificationChannel(AppController.ARE_YOU_THERE_SILENT_NOTIFICATION_CHANNEL_ID)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertEquals("it still has to open the full-screen prompt",
            NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test fun `without the alarm the notification makes the sound as before`() {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        postPrompt()

        val posted = postedPrompt()!!
        assertTrue((posted.flags and Notification.FLAG_INSISTENT) != 0)
        assertEquals(0, posted.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertFalse(posted.extras.getBoolean(AreYouThereAlarm.EXTRA_APP_OWNED_SOUND))
        assertEquals(AppController.ARE_YOU_THERE_NOTIFICATION_CHANNEL_ID, posted.channelId)
    }

    // ---- a start that Android refuses ---------------------------------------

    @Test fun `a refused start leaves the sound to the notification and tries again in seconds`() {
        // the periodic check is usually not an exact alarm, and only those may start a
        //  foreground service from the background. the repost timer is one
        var before = 0L

        withStartsRefused {
            assertEquals(AreYouThereAlarm.Start.REFUSED, AreYouThereAlarm.start(appCtx))
            before = System.currentTimeMillis()
            postPrompt()
        }

        assertTrue((postedPrompt()!!.flags and Notification.FLAG_INSISTENT) != 0)
        val trigger = shadowOf(alarmManager).nextScheduledAlarm!!.alarmClockInfo!!.triggerTime
        assertTrue("was ${trigger - before}ms out", trigger - before in 4_000L..8_000L)
    }

    @Test fun `the timer starts the alarm that was refused and swaps the notification`() {
        withStartsRefused { postPrompt() }
        app.clearStartedServices()

        fireTick()

        assertTrue(alarmServiceStarted())
        val posted = postedPrompt()!!
        assertEquals(0, posted.flags and Notification.FLAG_INSISTENT)
        assertTrue(posted.extras.getBoolean(AreYouThereAlarm.EXTRA_APP_OWNED_SOUND))

        val trigger = shadowOf(alarmManager).nextScheduledAlarm!!.alarmClockInfo!!.triggerTime
        assertTrue("back to once a minute", trigger - System.currentTimeMillis() > 50_000L)
    }

    @Test fun `the timer hands the sound back to the notification when the alarm can't go on`() {
        postPrompt()
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        fireTick()

        assertTrue(alarmServiceStopped())
        val posted = postedPrompt()!!
        assertTrue((posted.flags and Notification.FLAG_INSISTENT) != 0)
        assertFalse(posted.extras.getBoolean(AreYouThereAlarm.EXTRA_APP_OWNED_SOUND))
    }

    @Test fun `with the alarm playing the timer leaves the notification alone`() {
        // the reposts are there to bring a notification sound back
        postPrompt()
        val posted = postedPrompt()

        fireTick()

        assertTrue(posted === postedPrompt())
        assertNotNull("but it keeps running, as the alarm's watchdog",
            shadowOf(alarmManager).nextScheduledAlarm)
    }

    // ---- everything that ends the prompt ends the alarm ----------------------

    @Test fun `answering the prompt stops the alarm`() {
        postPrompt()

        AcknowledgeAreYouThere.dismissPrompt(appCtx)

        assertTrue(alarmServiceStopped())
    }

    @Test fun `a phone call stops the alarm`() {
        postPrompt()
        audioManager.mode = AudioManager.MODE_IN_CALL

        fireTick()

        assertTrue(AreYouThereCallPause.isPaused(appCtx))
        assertTrue(alarmServiceStopped())
    }

    @Test fun `the timer stops the alarm when the prompt is over`() {
        postPrompt()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, "alert_sent")
            .commit()

        fireTick()

        assertTrue(alarmServiceStopped())
    }

    // ---- the service ----------------------------------------------------------

    @Test fun `the service plays the sound until it is stopped`() {
        val sound = mockk<AlarmSound>(relaxed = true)
        AreYouThereAlarmService.soundFactory = { sound }

        val controller = Robolectric.buildService(AreYouThereAlarmService::class.java).create().startCommand(0, 1)

        verify(exactly = 1) { sound.start() }
        assertTrue(AreYouThereAlarm.isPlaying)

        // a second start, from the timer, must not start a second sound
        controller.startCommand(0, 2)
        verify(exactly = 1) { sound.start() }

        controller.destroy()

        verify(exactly = 1) { sound.stop() }
        assertFalse(AreYouThereAlarm.isPlaying)
    }

    @Test fun `the service stops by itself once the prompt is over`() {
        // the alert going out and a restart don't always get to tell it
        AreYouThereAlarmService.soundFactory = { mockk(relaxed = true) }
        val controller = Robolectric.buildService(AreYouThereAlarmService::class.java).create().startCommand(0, 1)
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, "periodic")
            .commit()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        controller.destroy()
    }

    @Test fun `a sound that can't be played hands over to the notification`() {
        // before the first unlock after a restart, for one, the alarm sound can't be read
        val sound = mockk<AlarmSound>(relaxed = true)
        every { sound.start() } throws IllegalStateException("no sound")
        AreYouThereAlarmService.soundFactory = { sound }
        postPrompt()
        assertEquals(0, postedPrompt()!!.flags and Notification.FLAG_INSISTENT)

        val controller = Robolectric.buildService(AreYouThereAlarmService::class.java).create().startCommand(0, 1)
        idleMain()

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertFalse(AreYouThereAlarm.isPlaying)
        assertTrue("the notification has to make the sound now",
            (postedPrompt()!!.flags and Notification.FLAG_INSISTENT) != 0)
        assertEquals("and the alarm is not tried again for this prompt",
            AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))

        AcknowledgeAreYouThere.dismissPrompt(appCtx)
        assertEquals("the next prompt gets its chance",
            AreYouThereAlarm.Start.PLAYING, AreYouThereAlarm.start(appCtx))
        controller.destroy()
    }
}
