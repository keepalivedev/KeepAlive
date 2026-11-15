package io.keepalive.android

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.receivers.AreYouThereRepostReceiver
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * As a notification sound the prompt is silent in silent mode, plays only in a headset
 * when one is connected, and stops for the volume keys and the notification shade. So
 * where it can, the app plays the sound itself, as an alarm, from a foreground service,
 * and the notification goes out without one. Where it can't, the notification makes the
 * sound as before. What must never happen is a prompt with neither.
 *
 * Under Robolectric a service that is asked for never runs by itself, which is the case
 * of a start that Android drops. A test that needs the alarm up says so with [alarmIsUp]
 * or runs the service.
 *
 * Two API levels plus the ones a test names, for the same reason as
 * [AreYouThereCallPauseTest]. The app only plays the alarm from API 26.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class AreYouThereAlarmTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val app get() = shadowOf(appCtx as Application)
    private val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var service: ServiceController<AreYouThereAlarmService>? = null

    @Before fun setUp() {
        app.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        // Robolectric starts with the alarm volume at 0, where the notification keeps the
        //  sound. that is also why the older prompt tests are untouched by the alarm
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
        resetAlarm()
        AreYouThereCallPause.stop(appCtx)
        idleMain()
        app.clearStartedServices()
        alarmServiceStopped()  // drains the stops the reset above recorded
    }

    @After fun tearDown() {
        service?.destroy()
        AreYouThereAlarmService.soundFactory = { AlarmSound(it) }
        AreYouThereCallPause.stop(appCtx)
        resetAlarm()
        idleMain()
    }

    private fun resetAlarm() {
        AreYouThereAlarm.onServiceAnswered()
        AreYouThereAlarm.onPlaying(false)
        AreYouThereAlarm.stop(appCtx)
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    // what the service reports once it is in the foreground and the sound plays
    private fun alarmIsUp() {
        AreYouThereAlarm.onServiceAnswered()
        AreYouThereAlarm.onPlaying(true)
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

    private fun notificationMakesTheSound() =
        (postedPrompt()!!.flags and Notification.FLAG_INSISTENT) != 0

    private fun fireTick() {
        AreYouThereRepostReceiver().onReceive(appCtx, Intent())
        idleMain()
    }

    // peek: the other accessor takes the alarm off the shadow's list
    private fun msToNextTick() =
        shadowOf(alarmManager).peekNextScheduledAlarm()!!.alarmClockInfo!!.triggerTime -
            System.currentTimeMillis()

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

    // a sound that makes none and reports that it plays
    private fun soundThatPlays(): AlarmSound {
        val sound = mockk<AlarmSound>(relaxed = true)
        every { sound.start(any(), any()) } answers { firstArg<() -> Unit>().invoke() }
        AreYouThereAlarmService.soundFactory = { sound }
        return sound
    }

    private fun runService(): ServiceController<AreYouThereAlarmService> {
        val controller = Robolectric.buildService(AreYouThereAlarmService::class.java).create().startCommand(0, 1)
        service = controller
        idleMain()
        return controller
    }

    // ---- when the app makes the sound ---------------------------------------

    @Test fun `the app asks for its alarm when it can, and has it once the service says so`() {
        assertEquals(AreYouThereAlarm.Start.STARTING, AreYouThereAlarm.start(appCtx))
        assertTrue(alarmServiceStarted())

        alarmIsUp()

        assertEquals(AreYouThereAlarm.Start.PLAYING, AreYouThereAlarm.start(appCtx))
        assertFalse("it must not be asked for twice", alarmServiceStarted())
    }

    @Test
    @Config(sdk = [33, 36])  // the exact alarm permission is API 31+
    fun `not when exact alarms can't be scheduled`() {
        // the service's type needs the permission, and so does the timer that watches it
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))
        assertFalse(alarmServiceStarted())
    }

    @Test fun `not with alarms turned all the way down`() {
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))
    }

    @Test
    @Config(sdk = [36])  // the foreground user can be asked for from API 31
    fun `not for a user who is not the one in front`() {
        // nobody could see the prompt or stop the alarm (issue #215)
        val userManager = appCtx.getSystemService(Context.USER_SERVICE) as UserManager
        shadowOf(userManager).setUserForeground(false)

        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))
    }

    @Test fun `not when the user has turned the soundless channel off`() {
        // the prompt would not be posted at all
        AlertNotificationHelper(appCtx)
        val channel = nm.getNotificationChannel(AppController.ARE_YOU_THERE_SILENT_NOTIFICATION_CHANNEL_ID)
        channel.importance = NotificationManager.IMPORTANCE_NONE
        nm.createNotificationChannel(channel)

        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))
    }

    @Test fun `it rings in silent mode`() {
        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertTrue(AreYouThereAlarm.start(appCtx).appOwned)

        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertTrue(AreYouThereAlarm.start(appCtx).appOwned)
    }

    @Test fun `with the setting off silent mode is left to the notification`() {
        getAppSharedPreferences(appCtx).edit().putBoolean("alarm_in_silent_mode", false).commit()

        audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))

        audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))

        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        assertTrue("the setting is about silent mode only", AreYouThereAlarm.start(appCtx).appOwned)
    }

    @Test fun `an alarm that is no longer wanted is stopped`() {
        AreYouThereAlarm.start(appCtx)
        alarmIsUp()
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))

        assertTrue(alarmServiceStopped())
    }

    // ---- the prompt's notification matches ----------------------------------

    @Test fun `with the app's alarm the notification makes no sound of its own`() {
        postPrompt()

        val posted = postedPrompt()!!
        assertFalse("the repeat flag is what loops the notification sound", notificationMakesTheSound())
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
        assertTrue(notificationMakesTheSound())
        assertEquals(0, posted.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertFalse(posted.extras.getBoolean(AreYouThereAlarm.EXTRA_APP_OWNED_SOUND))
        assertEquals(AppController.ARE_YOU_THERE_NOTIFICATION_CHANNEL_ID, posted.channelId)
    }

    // ---- never a prompt without a sound --------------------------------------

    @Test fun `the timer looks again within seconds of asking for the alarm`() {
        postPrompt()

        assertTrue("was ${msToNextTick()}ms out", msToNextTick() in 4_000L..8_000L)
    }

    @Test fun `an alarm that never comes up hands the sound back to the notification`() {
        // Android can drop a start without a word, and the notification is on the
        //  soundless channel by then
        postPrompt()
        assertFalse(notificationMakesTheSound())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(5))

        fireTick()

        assertTrue(notificationMakesTheSound())
        assertEquals("not tried again for this prompt",
            AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))
        assertTrue("and the timer is back to once a minute", msToNextTick() > 50_000L)
    }

    @Test fun `once the alarm is up the timer goes back to once a minute and leaves the notification alone`() {
        // the reposts are there to bring a notification sound back
        postPrompt()
        val posted = postedPrompt()
        alarmIsUp()

        fireTick()

        assertTrue(posted === postedPrompt())
        assertTrue("it keeps running, as the alarm's watchdog", msToNextTick() > 50_000L)
    }

    @Test fun `a refused start leaves the sound to the notification and tries again in seconds`() {
        // the periodic check is usually not an exact alarm, and only those may start a
        //  foreground service from the background. the repost timer is one
        withStartsRefused {
            assertEquals(AreYouThereAlarm.Start.REFUSED, AreYouThereAlarm.start(appCtx))
            postPrompt()
        }

        assertTrue(notificationMakesTheSound())
        assertTrue("was ${msToNextTick()}ms out", msToNextTick() in 4_000L..8_000L)
    }

    @Test fun `the timer starts the alarm that was refused and swaps the notification`() {
        withStartsRefused { postPrompt() }
        app.clearStartedServices()

        fireTick()

        assertTrue(alarmServiceStarted())
        assertFalse(notificationMakesTheSound())
        assertTrue(postedPrompt()!!.extras.getBoolean(AreYouThereAlarm.EXTRA_APP_OWNED_SOUND))
    }

    @Test fun `the timer hands the sound back to the notification when the alarm can't go on`() {
        postPrompt()
        alarmIsUp()
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        fireTick()

        assertTrue(alarmServiceStopped())
        assertTrue(notificationMakesTheSound())
        assertFalse(postedPrompt()!!.extras.getBoolean(AreYouThereAlarm.EXTRA_APP_OWNED_SOUND))
    }

    // ---- everything that ends the prompt ends the alarm ----------------------

    @Test fun `answering the prompt stops the alarm`() {
        postPrompt()
        alarmIsUp()

        AcknowledgeAreYouThere.dismissPrompt(appCtx)

        assertTrue(alarmServiceStopped())
    }

    @Test fun `a phone call stops the alarm, and the timer looks for it again after the call`() {
        postPrompt()
        alarmIsUp()
        audioManager.mode = AudioManager.MODE_IN_CALL

        fireTick()

        assertTrue(AreYouThereCallPause.isPaused(appCtx))
        assertTrue(alarmServiceStopped())

        // the prompt can come back from code that isn't allowed to start the alarm
        AreYouThereAlarm.onPlaying(false)
        audioManager.mode = AudioManager.MODE_NORMAL
        fireTick()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        withStartsRefused { fireTick() }

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertTrue(notificationMakesTheSound())
        assertTrue("was ${msToNextTick()}ms out", msToNextTick() in 4_000L..8_000L)
    }

    @Test fun `a phone call does not give an alarm that failed another go`() {
        // a service that can't go to the foreground can take the process down, and with
        //  it the overlay that has only just come back
        AreYouThereAlarm.markUnavailable(appCtx)
        postPrompt()
        audioManager.mode = AudioManager.MODE_IN_CALL

        fireTick()

        assertTrue(AreYouThereCallPause.isPaused(appCtx))
        audioManager.mode = AudioManager.MODE_NORMAL
        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))

        AcknowledgeAreYouThere.dismissPrompt(appCtx)
        assertTrue("the next prompt gets its chance", AreYouThereAlarm.start(appCtx).appOwned)
    }

    @Test fun `an alarm that failed before a restart gets another go after it`() {
        // at shutdown the phone's media service goes first, and the player reports that as
        //  a failure of its own
        AreYouThereAlarm.markUnavailable(appCtx)
        assertEquals(AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))

        Settings.Global.putInt(appCtx.contentResolver, Settings.Global.BOOT_COUNT, 7)

        assertTrue(AreYouThereAlarm.start(appCtx).appOwned)
    }

    @Test fun `the timer stops the alarm when the prompt is over`() {
        postPrompt()
        alarmIsUp()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, "alert_sent")
            .commit()

        fireTick()

        assertTrue(alarmServiceStopped())
    }

    // ---- stopping a service that is still on its way up ----------------------

    @Test fun `a stop while the service is on its way up is left to the service`() {
        // stopped from outside before it has gone to the foreground, a service takes the
        //  whole process down
        val sound = soundThatPlays()
        AreYouThereAlarm.start(appCtx)

        AreYouThereAlarm.stop(appCtx)

        assertFalse(alarmServiceStopped())

        val controller = runService()

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        verify(exactly = 0) { sound.start(any(), any()) }
        assertFalse(AreYouThereAlarm.isPlaying)
    }

    @Test fun `once the service is up a stop goes straight to it`() {
        AreYouThereAlarm.start(appCtx)
        alarmIsUp()

        AreYouThereAlarm.stop(appCtx)

        assertTrue(alarmServiceStopped())
    }

    // ---- the service ----------------------------------------------------------

    @Test fun `the service plays the sound until it is stopped`() {
        val sound = soundThatPlays()
        AreYouThereAlarm.start(appCtx)

        val controller = runService()

        verify(exactly = 1) { sound.start(any(), any()) }
        assertTrue(AreYouThereAlarm.isPlaying)
        assertEquals(AreYouThereAlarm.Start.PLAYING, AreYouThereAlarm.start(appCtx))

        // a second start, from the timer, must not start a second sound
        controller.startCommand(0, 2)
        verify(exactly = 1) { sound.start(any(), any()) }

        controller.destroy()
        service = null

        verify(exactly = 1) { sound.stop() }
        assertFalse(AreYouThereAlarm.isPlaying)
    }

    @Test fun `the service stops by itself once the prompt is over`() {
        // the alert going out and a restart don't always get to tell it
        soundThatPlays()
        AreYouThereAlarm.start(appCtx)
        val controller = runService()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, "periodic")
            .commit()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun `a sound that can't be opened hands over to the notification`() {
        val sound = mockk<AlarmSound>(relaxed = true)
        every { sound.start(any(), any()) } throws IllegalStateException("no sound")
        AreYouThereAlarmService.soundFactory = { sound }
        postPrompt()
        assertFalse(notificationMakesTheSound())

        val controller = runService()

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertFalse(AreYouThereAlarm.isPlaying)
        assertTrue(notificationMakesTheSound())
        assertEquals("and the alarm is not tried again for this prompt",
            AreYouThereAlarm.Start.NOT_WANTED, AreYouThereAlarm.start(appCtx))

        AcknowledgeAreYouThere.dismissPrompt(appCtx)
        assertTrue("the next prompt gets its chance", AreYouThereAlarm.start(appCtx).appOwned)
    }

    @Test fun `a sound that stops playing later hands over to the notification`() {
        // the phone's media service can die under a player that has been fine for minutes
        val onError = slot<() -> Unit>()
        val sound = mockk<AlarmSound>(relaxed = true)
        every { sound.start(any(), capture(onError)) } answers { firstArg<() -> Unit>().invoke() }
        AreYouThereAlarmService.soundFactory = { sound }
        postPrompt()
        val controller = runService()
        assertTrue(AreYouThereAlarm.isPlaying)

        onError.captured.invoke()
        idleMain()

        assertFalse(AreYouThereAlarm.isPlaying)
        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(notificationMakesTheSound())
    }

    @Test fun `a sound that never starts playing hands over to the notification`() {
        // it is loaded in the background, and that can hang
        val sound = mockk<AlarmSound>(relaxed = true)
        AreYouThereAlarmService.soundFactory = { sound }
        postPrompt()
        val controller = runService()
        assertFalse(AreYouThereAlarm.isPlaying)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertTrue(shadowOf(controller.get()).isStoppedBySelf)
        assertTrue(notificationMakesTheSound())
        assertNotNull("the timer has to keep running", shadowOf(alarmManager).peekNextScheduledAlarm())
    }
}
