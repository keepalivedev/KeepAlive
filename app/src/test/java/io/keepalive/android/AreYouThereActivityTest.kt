package io.keepalive.android

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tests the full-screen "Are you there?" prompt activity (issue #182):
 * launched by the system over the lock screen via the notification's
 * full-screen intent; "I'm OK" acknowledges, and the prompt closes itself
 * when it is acknowledged elsewhere or its countdown runs out.
 */
@RunWith(RobolectricTestRunner::class)
class AreYouThereActivityTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        mockkObject(AcknowledgeAreYouThere)
        every { AcknowledgeAreYouThere.acknowledge(any()) } returns Unit

        // a final alarm ten minutes out so the countdown doesn't finish the
        //  activity during the test
        getAppSharedPreferences(appCtx).edit()
            .putLong(PrefKeys.NEXT_ALARM_TIMESTAMP, System.currentTimeMillis() + 10 * 60 * 1000L)
            .commit()
    }

    @After fun tearDown() {
        unmockkObject(AcknowledgeAreYouThere)
        getAppSharedPreferences(appCtx).edit().clear().commit()
    }

    private fun launch(message: String? = null): AreYouThereActivity {
        val intent = Intent(appCtx, AreYouThereActivity::class.java)
        message?.let { intent.putExtra(AreYouThereActivity.EXTRA_MESSAGE, it) }
        val activity = Robolectric.buildActivity(AreYouThereActivity::class.java, intent)
            .setup().get()
        shadowOf(Looper.getMainLooper()).idle()
        return activity
    }

    @Test fun `message extra populates the prompt text`() {
        val activity = launch("Tap to confirm you're OK. Otherwise an alert will be sent in 10 minutes.")

        assertEquals(
            "Tap to confirm you're OK. Otherwise an alert will be sent in 10 minutes.",
            activity.findViewById<TextView>(R.id.textAreYouThereMessage).text.toString()
        )
        // title always comes from the runtime string, same as the overlay
        assertEquals(
            appCtx.getString(R.string.initial_check_notification_title),
            activity.findViewById<TextView>(R.id.textAreYouThereTitle).text.toString()
        )
    }

    @Test fun `im ok button acknowledges and finishes`() {
        val activity = launch()

        activity.findViewById<Button>(R.id.buttonImOk).performClick()

        verify(exactly = 1) { AcknowledgeAreYouThere.acknowledge(any()) }
        assertTrue("activity must finish after acknowledging", activity.isFinishing)
    }

    @Test fun `finishActive closes the showing prompt`() {
        // acknowledged elsewhere (notification tap, BOOT_COMPLETED) or the
        //  final alert fired — the prompt must not stay on screen
        val activity = launch()

        AreYouThereActivity.finishActive()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("finishActive must finish the showing prompt", activity.isFinishing)
    }

    @Test fun `finishActive is a no-op with no prompt showing`() {
        AreYouThereActivity.finishActive()
        shadowOf(Looper.getMainLooper()).idle()
        // no crash = pass
    }

    @Test fun `prompt closes itself when the final alarm time has passed`() {
        // the alert has fired (or no alarm is scheduled at all) — the prompt
        //  is no longer actionable
        getAppSharedPreferences(appCtx).edit()
            .putLong(PrefKeys.NEXT_ALARM_TIMESTAMP, System.currentTimeMillis() - 1000L)
            .commit()

        val activity = launch()

        assertTrue("activity must finish once the countdown target has passed",
            activity.isFinishing)
    }
}
