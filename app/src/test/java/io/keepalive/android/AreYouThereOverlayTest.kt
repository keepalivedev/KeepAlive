package io.keepalive.android

import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/**
 * Tests [AreYouThereOverlay], the direct-WindowManager overlay manager (no
 * foreground service). Verifies that show() draws a view, that the "I'm OK"
 * button delegates to [AcknowledgeAreYouThere] while dismiss() does not, that
 * a denied SYSTEM_ALERT_WINDOW short-circuits, and that the screen-wake pulse
 * fires.
 *
 * show()/dismiss() post to the main Looper, so each call is followed by
 * idleMain(). The overlay view is read via reflection on the private
 * `overlayView` field (no public accessor; production never needs one).
 */
@RunWith(RobolectricTestRunner::class)
class AreYouThereOverlayTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        mockkObject(AcknowledgeAreYouThere)
        every { AcknowledgeAreYouThere.acknowledge(any()) } returns Unit
        // Robolectric's Settings.canDrawOverlays defaults to false; the manager
        // short-circuits in that case. Force-grant via mockkStatic.
        mockkStatic(Settings::class)
        every { Settings.canDrawOverlays(any()) } returns true
    }

    @After fun tearDown() {
        // Clear static overlay state so other tests start clean.
        AreYouThereOverlay.dismiss(appCtx)
        idleMain()
        unmockkStatic(Settings::class)
        unmockkObject(AcknowledgeAreYouThere)
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun anHourOut() = System.currentTimeMillis() + 60 * 60_000L

    /** Reads the private `overlayView` field on the singleton — no public accessor exists. */
    private fun overlayView(): View? {
        val field = AreYouThereOverlay::class.java.getDeclaredField("overlayView")
        field.isAccessible = true
        return field.get(AreYouThereOverlay) as View?
    }

    @Test fun `show inflates an overlay view`() {
        AreYouThereOverlay.show(appCtx, "test message", anHourOut())
        idleMain()

        assertNotNull("overlayView should be populated after show()", overlayView())
    }

    @Test fun `show acquires a screen wake lock`() {
        AreYouThereOverlay.show(appCtx, "test message", anHourOut())
        idleMain()

        assertNotNull("show() should acquire a wake lock to wake the screen",
            ShadowPowerManager.getLatestWakeLock())
    }

    @Test fun `I'm OK button invokes acknowledge and clears the overlay reference`() {
        AreYouThereOverlay.show(appCtx, "test message", anHourOut())
        idleMain()

        overlayView()!!.findViewById<Button>(R.id.buttonImOk).performClick()
        idleMain()

        verify(exactly = 1) { AcknowledgeAreYouThere.acknowledge(any()) }
        assertNull("dismissal must null out the cached view", overlayView())
    }

    @Test fun `dismiss removes the overlay without calling acknowledge`() {
        AreYouThereOverlay.show(appCtx, "test message", anHourOut())
        idleMain()
        assertNotNull(overlayView())

        AreYouThereOverlay.dismiss(appCtx)
        idleMain()

        assertNull(overlayView())
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `show twice does not stack overlays`() {
        AreYouThereOverlay.show(appCtx, "test message", anHourOut())
        idleMain()
        val first = overlayView()
        assertNotNull(first)

        AreYouThereOverlay.show(appCtx, "test message", anHourOut())
        idleMain()

        // Same view retained — showOnMain returns early when overlayView != null.
        assertEquals("second show must keep the existing view, not replace it",
            first, overlayView())
    }

    @Test fun `overlay message is rendered from the argument`() {
        AreYouThereOverlay.show(appCtx, "test message", anHourOut())
        idleMain()

        val messageView = overlayView()!!.findViewById<TextView>(R.id.textAreYouThereMessage)
        assertEquals("test message", messageView.text.toString())
    }

    @Test fun `countdown runs to the deadline, not the configured follow-up`() {
        // A prompt restored part-way through its window (reboot, update, watchdog)
        // must not promise the full follow-up period when the alarm is minutes away.
        getAppSharedPreferences(appCtx).edit().putString("followup_time_period_minutes", "60").commit()
        AreYouThereOverlay.show(appCtx, "test message", System.currentTimeMillis() + 5 * 60_000L)
        idleMain()

        val countdown = overlayView()!!.findViewById<TextView>(R.id.textAreYouThereCountdown).text.toString()
        val minutes = Regex("(\\d+):(\\d\\d)").find(countdown)!!.groupValues[1].toInt()
        assertTrue("expected about 5 minutes, got: $countdown", minutes in 4..5)
    }

    // ---- SYSTEM_ALERT_WINDOW denied path -----------------------------------
    //
    // canDrawOverlays() returns false → the manager must NOT add a view to
    // WindowManager (would throw on a real device) and must not acknowledge.

    @Test fun `show does not inflate an overlay when canDrawOverlays is false`() {
        every { Settings.canDrawOverlays(any()) } returns false

        AreYouThereOverlay.show(appCtx, "test message", anHourOut())
        idleMain()

        assertNull("overlay must NOT be inflated when SYSTEM_ALERT_WINDOW is denied",
            overlayView())
    }

    @Test fun `show with overlay denied does not invoke acknowledge`() {
        // Belt-and-suspenders: even if some path slipped through, denied state
        // must never silently call acknowledge() (would falsely mark the user
        // as present).
        every { Settings.canDrawOverlays(any()) } returns false

        AreYouThereOverlay.show(appCtx, "test message", anHourOut())
        idleMain()

        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }
}
