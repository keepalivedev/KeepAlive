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
import android.telephony.TelephonyManager
import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.receivers.AreYouThereRepostReceiver
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * The full-screen prompt covers the lock screen, which leaves an incoming call as a
 * banner whose Answer button asks for the PIN on Android 16, and once the phone is
 * unlocked the overlay covers the call screen's buttons. So for the length of a call
 * [AreYouThereCallPause] takes the whole prompt down, and it posts the prompt again
 * afterwards.
 *
 * Runs on two API levels only, plus the ones a test names. The whole suite runs in one
 * JVM with a small heap, and every extra level of a class that builds views costs it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class AreYouThereCallPauseTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val telephonyManager =
        appCtx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var activity: ActivityController<AreYouThereActivity>? = null

    @Before fun setUp() {
        shadowOf(appCtx as Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.USE_FULL_SCREEN_INTENT
        )
        ShadowSettings.setCanDrawOverlays(true)

        nm.cancelAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        // the final alarm half an hour out. the full-screen prompt reads it from the app's
        //  preferences and closes itself without one
        val deadline = System.currentTimeMillis() + 30 * 60_000L
        getAppSharedPreferences(appCtx).edit()
            .putBoolean(PrefKeys.ENABLED, true)
            .putBoolean(PrefKeys.ARE_YOU_THERE_OVERLAY_ENABLED, true)
            .putLong(PrefKeys.NEXT_ALARM_TIMESTAMP, deadline)
            .commit()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, "final")
            .putLong(PrefKeys.NEXT_ALARM_TIMESTAMP, deadline)
            .commit()

        // the watcher and its timestamps live in an object, which outlasts a test
        AreYouThereCallPause.stop(appCtx)
        idleMain()
    }

    @After fun tearDown() {
        // an activity that is left open keeps its whole view tree alive for the rest of the run
        activity?.let { if (!it.get().isDestroyed) it.destroy() }
        AreYouThereCallPause.stop(appCtx)
        AreYouThereOverlay.dismiss(appCtx)
        idleMain()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

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

    // moves the clock only: the watcher is posted on the main looper and doesn't get to run
    private fun waitWithoutWatcher(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    private fun overlayView(): View? {
        val field = AreYouThereOverlay::class.java.getDeclaredField("overlayView")
        field.isAccessible = true
        return field.get(AreYouThereOverlay) as View?
    }

    private fun showOverlay() {
        AreYouThereOverlay.show(appCtx, "test message", System.currentTimeMillis() + 30 * 60_000L)
        idleMain()
    }

    private fun launchActivity(): AreYouThereActivity {
        val controller = Robolectric.buildActivity(
            AreYouThereActivity::class.java, Intent(appCtx, AreYouThereActivity::class.java)
        ).setup()
        activity = controller
        idleMain()
        return controller.get()
    }

    // ---- a call in progress ------------------------------------------------

    @Test fun `a call in progress takes the whole prompt down`() {
        postPrompt()
        showOverlay()
        val prompt = launchActivity()
        assertFalse(prompt.isFinishing)
        audioManager.mode = AudioManager.MODE_IN_CALL

        fireTick()

        assertTrue(AreYouThereCallPause.isPaused(appCtx))
        assertNull("the overlay would cover the call screen's buttons", overlayView())
        assertTrue("the full-screen prompt would keep the call as a banner", prompt.isFinishing)
        assertNull("only cancelling stops the repeating vibration as well as the sound",
            postedPrompt())
    }

    @Test fun `a call in another app counts as a call`() {
        postPrompt()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        fireTick()

        assertTrue(AreYouThereCallPause.isPaused(appCtx))
    }

    @Test fun `the watcher does not wait for the one-minute timer`() {
        postPrompt()
        showOverlay()
        audioManager.mode = AudioManager.MODE_IN_CALL

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        assertTrue(AreYouThereCallPause.isPaused(appCtx))
        assertNull(overlayView())
    }

    @Test fun `the timer runs more often during a call`() {
        // android stops the process from running a while into a call, so the timer is
        //  the only thing that sees the call end
        postPrompt()
        audioManager.mode = AudioManager.MODE_IN_CALL
        val before = System.currentTimeMillis()

        fireTick()

        val trigger = shadowOf(alarmManager).nextScheduledAlarm!!.alarmClockInfo!!.triggerTime
        assertTrue("was ${trigger - before}ms out", trigger - before in 10_000L..20_000L)
    }

    // ---- ringing -----------------------------------------------------------

    @Test fun `a ringing call is given a moment before the prompt steps aside`() {
        // the call's banner has to be on screen for android to turn it into the full call
        //  screen. stepping aside before that leaves the call as a line in the list
        postPrompt()
        showOverlay()
        audioManager.mode = AudioManager.MODE_RINGTONE

        fireTick()

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertNotNull(overlayView())

        waitWithoutWatcher(2_000L)
        fireTick()

        assertTrue(AreYouThereCallPause.isPaused(appCtx))
        assertNull(overlayView())
        assertNull(postedPrompt())
    }

    @Test fun `a call that stops ringing early leaves the prompt alone`() {
        postPrompt()
        audioManager.mode = AudioManager.MODE_RINGTONE
        fireTick()
        audioManager.mode = AudioManager.MODE_NORMAL
        waitWithoutWatcher(2_000L)

        fireTick()

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertNotNull(postedPrompt())
    }

    // ---- after the call ----------------------------------------------------

    private fun pauseForCall() {
        postPrompt()
        audioManager.mode = AudioManager.MODE_IN_CALL
        fireTick()
        assertTrue(AreYouThereCallPause.isPaused(appCtx))
        assertNull(postedPrompt())
    }

    @Test fun `the prompt comes back after the call`() {
        pauseForCall()
        audioManager.mode = AudioManager.MODE_NORMAL

        fireTick()
        assertTrue("the call screen is still up for a moment", AreYouThereCallPause.isPaused(appCtx))

        waitWithoutWatcher(2_000L)
        fireTick()

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        val posted = postedPrompt()
        assertNotNull(posted)
        assertTrue("the sound has to repeat again",
            (posted!!.flags and Notification.FLAG_INSISTENT) != 0)
        assertTrue((posted.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertNotNull("the overlay is part of the prompt", overlayView())

        val trigger = shadowOf(alarmManager).nextScheduledAlarm!!.alarmClockInfo!!.triggerTime
        assertTrue("back to once a minute", trigger - System.currentTimeMillis() > 50_000L)
    }

    @Test
    @Config(sdk = [28, 33])  // Robolectric can't grant the full-screen intent on 34+
    fun `the prompt that comes back can open over the lock screen again`() {
        pauseForCall()
        audioManager.mode = AudioManager.MODE_NORMAL
        fireTick()
        waitWithoutWatcher(2_000L)

        fireTick()

        assertNotNull(postedPrompt()?.fullScreenIntent)
    }

    @Test fun `the watcher brings the prompt back without waiting for the timer`() {
        pauseForCall()
        audioManager.mode = AudioManager.MODE_NORMAL

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertNotNull(postedPrompt())
    }

    @Test fun `a second call right after the first keeps the prompt away`() {
        pauseForCall()
        audioManager.mode = AudioManager.MODE_NORMAL
        fireTick()
        audioManager.mode = AudioManager.MODE_RINGTONE
        waitWithoutWatcher(2_000L)

        fireTick()

        assertTrue(AreYouThereCallPause.isPaused(appCtx))
        assertNull(postedPrompt())
    }

    // ---- a paused prompt can't get lost -------------------------------------

    @Test fun `a paused prompt keeps its timer although nothing is posted`() {
        // without a notification the timer used to take the prompt for answered and stop.
        //  now it is all that brings the prompt back
        pauseForCall()

        fireTick()

        assertTrue(AreYouThereCallPause.isPaused(appCtx))
        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test fun `a prompt that was posted again during the pause still comes back as new`() {
        // a recovery path can post it while the call is on. an update of that one would
        //  never open the full-screen prompt again
        pauseForCall()
        postPrompt()
        val stray = postedPrompt()
        audioManager.mode = AudioManager.MODE_NORMAL
        fireTick()
        waitWithoutWatcher(2_000L)

        fireTick()

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertNotNull(postedPrompt())
        assertTrue(stray !== postedPrompt())
    }

    @Test fun `a tick that fails still sets the next one`() {
        // the timer is all that brings a paused prompt back
        pauseForCall()
        AreYouThereRepost.cancel(appCtx)
        mockkObject(AreYouThereCallPause)
        try {
            every { AreYouThereCallPause.shouldStepAside(any()) } throws IllegalStateException("boom")

            AreYouThereRepostReceiver().onReceive(appCtx, Intent())
        } finally {
            unmockkObject(AreYouThereCallPause)
        }

        assertNotNull(shadowOf(alarmManager).nextScheduledAlarm)
    }

    @Test fun `pausing and coming back leave the final alarm alone`() {
        val devicePrefs = getDeviceProtectedPreferences(appCtx)
        val deadline = devicePrefs.getLong(PrefKeys.NEXT_ALARM_TIMESTAMP, 0L)

        pauseForCall()
        audioManager.mode = AudioManager.MODE_NORMAL
        fireTick()
        waitWithoutWatcher(2_000L)
        fireTick()

        assertEquals(deadline, devicePrefs.getLong(PrefKeys.NEXT_ALARM_TIMESTAMP, 0L))
        assertEquals("final", devicePrefs.getString(PrefKeys.LAST_ALARM_STAGE, null))
    }

    @Test
    @Config(sdk = [33, 36])  // the exact alarm permission is API 31+
    fun `the prompt stays put when the timer can't be trusted to bring it back`() {
        // without the exact alarm permission android may hold the timer back for an hour,
        //  and the alert would go out with the prompt still down
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        postPrompt()
        showOverlay()
        audioManager.mode = AudioManager.MODE_IN_CALL

        fireTick()

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertNotNull(postedPrompt())
        assertNotNull(overlayView())
    }

    // ---- the last minutes belong to the prompt -------------------------------

    private fun setDeadlineIn(minutes: Int) {
        getDeviceProtectedPreferences(appCtx).edit()
            .putLong(PrefKeys.NEXT_ALARM_TIMESTAMP, System.currentTimeMillis() + minutes * 60_000L)
            .commit()
    }

    @Test fun `the prompt does not step aside when the alert is minutes away`() {
        // a call can outlast the time left to answer, and the alert must not go out from
        //  behind it
        setDeadlineIn(4)
        postPrompt()
        showOverlay()
        audioManager.mode = AudioManager.MODE_IN_CALL

        fireTick()

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertNotNull(overlayView())
        assertNotNull(postedPrompt())
    }

    @Test fun `a paused prompt comes back during the call when the alert gets close`() {
        pauseForCall()
        setDeadlineIn(4)

        fireTick()

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertNotNull(postedPrompt())
        assertNotNull(overlayView())
    }

    // ---- the pause ends with the prompt ------------------------------------

    @Test fun `a new prompt does not inherit a pause that was left behind`() {
        // the process can be killed, or the phone restarted, with the pause on record
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean(PrefKeys.PROMPT_PAUSED_FOR_CALL, true)
            .putLong(PrefKeys.PROMPT_POSTED_AT, 1L)
            .commit()

        ProductionAlertCheckDeps(appCtx).showAreYouThereNotification(30)
        showOverlay()

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertNull(AreYouThereCallPause.promptPostedAt(appCtx))
        assertNotNull(overlayView())
    }

    @Test fun `answering the prompt forgets the pause`() {
        pauseForCall()

        AcknowledgeAreYouThere.dismissPrompt(appCtx)
        idleMain()

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
    }

    @Test fun `the pause is forgotten when the alert went out during the call`() {
        pauseForCall()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, "alert_sent")
            .commit()

        fireTick()

        assertFalse("the next prompt would start out hidden", AreYouThereCallPause.isPaused(appCtx))
    }

    // ---- nothing of the prompt opens during a call -------------------------

    @Test fun `the overlay is not shown while the prompt is paused`() {
        pauseForCall()

        showOverlay()

        assertNull(overlayView())
    }

    @Test fun `the full-screen prompt closes itself while the prompt is paused`() {
        pauseForCall()

        assertTrue(launchActivity().isFinishing)
    }

    @Test fun `a call alone does not keep the overlay away`() {
        // without notifications there is no timer to bring the overlay back after the
        //  call, so only a pause that was recorded may hold it back
        audioManager.mode = AudioManager.MODE_IN_CALL

        showOverlay()

        assertNotNull(overlayView())
    }

    // ---- which calls can be seen -------------------------------------------

    @Test fun `with no call the prompt stays where it is`() {
        assertFalse(AreYouThereCallPause.shouldStepAside(appCtx))
    }

    @Test
    @Config(sdk = [23, 28])
    fun `below API 31 the telephony state shows a call that rings silently`() {
        // with the ringer on silent or vibrate the audio mode stays normal while it rings
        shadowOf(telephonyManager).setCallState(TelephonyManager.CALL_STATE_RINGING)

        assertFalse("still within its moment", AreYouThereCallPause.shouldStepAside(appCtx))
        waitWithoutWatcher(2_000L)

        assertTrue(AreYouThereCallPause.shouldStepAside(appCtx))
    }

    @Test
    @Config(sdk = [33, 36])
    fun `from API 31 the telephony state is only read with the phone permission`() {
        shadowOf(telephonyManager).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        shadowOf(appCtx as Application).denyPermissions(Manifest.permission.READ_PHONE_STATE)

        assertFalse("reading it without the permission throws",
            AreYouThereCallPause.shouldStepAside(appCtx))

        shadowOf(appCtx as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)

        assertTrue(AreYouThereCallPause.shouldStepAside(appCtx))
    }

    @Test fun `the alarm stage decides when the watcher stops`() {
        // the alert going out doesn't dismiss the prompt through AcknowledgeAreYouThere,
        //  so nothing else would end a once-a-second check
        postPrompt()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, "alert_sent")
            .commit()
        audioManager.mode = AudioManager.MODE_IN_CALL

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertEquals("the watcher must be gone",
            Duration.ZERO, shadowOf(Looper.getMainLooper()).nextScheduledTaskTime)
    }
}
