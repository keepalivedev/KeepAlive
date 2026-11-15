package io.keepalive.android

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.receivers.AreYouThereRepostReceiver
import io.keepalive.android.testing.fakeUsageEvent
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Unlocking the phone has always counted as activity, but only at the final check, so
 * the prompt and its sound carried on until then for someone who had plainly come back.
 * The repost timer now looks for an unlock every minute and answers the prompt with it.
 *
 * Lock and unlock events exist from API 28.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36])
class AreYouThereUnlockTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val usm = appCtx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Before fun setUp() {
        shadowOf(appCtx as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        nm.cancelAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        getAppSharedPreferences(appCtx).edit()
            .putBoolean(PrefKeys.ENABLED, true)
            .putBoolean(PrefKeys.ARE_YOU_THERE_OVERLAY_ENABLED, false)
            .commit()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString(PrefKeys.LAST_ALARM_STAGE, "final")
            .putLong(PrefKeys.NEXT_ALARM_TIMESTAMP, System.currentTimeMillis() + 30 * 60_000L)
            .commit()

        // answering resets the alarms, which is covered by AcknowledgeAreYouThereTest
        mockkObject(AcknowledgeAreYouThere)
        every { AcknowledgeAreYouThere.acknowledge(any()) } returns Unit

        AreYouThereCallPause.stop(appCtx)
        idleMain()
    }

    @After fun tearDown() {
        AreYouThereCallPause.stop(appCtx)
        idleMain()
        unmockkObject(AcknowledgeAreYouThere)
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    // posts the prompt and returns when it went up
    private fun postPrompt(): Long {
        AlertNotificationHelper(appCtx).sendNotification(
            "Are you there?",
            "Please respond",
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )
        idleMain()
        return postedPrompt()!!.`when`
    }

    private fun postedPrompt(): Notification? =
        shadowOf(nm).getNotification(null, AppController.ARE_YOU_THERE_NOTIFICATION_ID)

    private fun addEvent(eventType: Int, timestamp: Long, pkg: String = "android") {
        shadowOf(usm).addEvent(
            fakeUsageEvent(packageName = pkg, timeStamp = timestamp, eventType = eventType)
        )
    }

    // some time later, so that an event after the prompt is also before "now"
    private fun fireTick() {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        AreYouThereRepostReceiver().onReceive(appCtx, Intent())
        idleMain()
    }

    private fun assertAnswered() = verify(exactly = 1) { AcknowledgeAreYouThere.acknowledge(any()) }

    private fun assertNotAnswered() = verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }

    @Test fun `an unlock after the prompt went up answers it`() {
        val postedAt = postPrompt()
        addEvent(UsageEvents.Event.KEYGUARD_HIDDEN, postedAt + 1_000L)

        fireTick()

        assertAnswered()
    }

    @Test fun `with no unlock the prompt is reposted as before`() {
        postPrompt()

        fireTick()

        assertNotAnswered()
        assertNotNull(postedPrompt())
    }

    @Test fun `an unlock from before the prompt does not answer it`() {
        // that one is why the prompt went up at all: it was too long ago
        val postedAt = postPrompt()
        addEvent(UsageEvents.Event.KEYGUARD_HIDDEN, postedAt - 60_000L)

        fireTick()

        assertNotAnswered()
    }

    @Test fun `the phone locking does not answer it`() {
        // a phone locks by itself when its screen times out, with nobody there
        val postedAt = postPrompt()
        addEvent(UsageEvents.Event.KEYGUARD_SHOWN, postedAt + 1_000L)

        fireTick()

        assertNotAnswered()
    }

    @Test fun `with apps to monitor an unlock does not answer it`() {
        // there it is using those apps that counts as activity, so an unlock must not
        //  reset the monitoring either
        getAppSharedPreferences(appCtx).edit().putString(
            PrefKeys.APPS_TO_MONITOR,
            """[{"packageName":"com.example.app","appName":"App","lastUsed":0,"className":""}]"""
        ).commit()
        val postedAt = postPrompt()
        addEvent(UsageEvents.Event.KEYGUARD_HIDDEN, postedAt + 1_000L)

        fireTick()

        assertNotAnswered()
    }

    @Test fun `an unlock during a call answers the prompt instead of bringing it back`() {
        val postedAt = postPrompt()
        audioManager.mode = AudioManager.MODE_IN_CALL
        fireTick()
        assertTrue(AreYouThereCallPause.isPaused(appCtx))

        addEvent(UsageEvents.Event.KEYGUARD_HIDDEN, postedAt + 40_000L)
        audioManager.mode = AudioManager.MODE_NORMAL
        fireTick()

        assertAnswered()
    }

    @Test fun `an unlock from during a call still counts once the prompt is back`() {
        // the prompt that comes back is a new notification with a later time. the unlock
        //  can show up in the usage events only after that
        val postedAt = postPrompt()
        audioManager.mode = AudioManager.MODE_IN_CALL
        fireTick()
        audioManager.mode = AudioManager.MODE_NORMAL
        fireTick()
        fireTick()
        assertFalse(AreYouThereCallPause.isPaused(appCtx))
        assertTrue("this test needs a prompt that was posted again",
            postedPrompt()!!.`when` > postedAt + 1_000L)

        addEvent(UsageEvents.Event.KEYGUARD_HIDDEN, postedAt + 1_000L)
        fireTick()

        assertAnswered()
    }

    @Test fun `an unlock during a call is seen while the call is still on`() {
        val postedAt = postPrompt()
        audioManager.mode = AudioManager.MODE_IN_CALL
        fireTick()

        addEvent(UsageEvents.Event.KEYGUARD_HIDDEN, postedAt + 40_000L)
        fireTick()

        assertAnswered()
    }
}
