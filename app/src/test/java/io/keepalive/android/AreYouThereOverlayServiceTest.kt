package io.keepalive.android

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import android.provider.Settings
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tests [AreYouThereOverlayService]. Verifies the static entry points, and
 * drives the service lifecycle to confirm the I'm-OK button delegates to
 * [AcknowledgeAreYouThere] and the dismiss action does not.
 *
 * Reads the overlay view via reflection on the private `overlayView` field
 * (no public accessor; the production code never needs one).
 */
@RunWith(RobolectricTestRunner::class)
class AreYouThereOverlayServiceTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        mockkObject(AcknowledgeAreYouThere)
        every { AcknowledgeAreYouThere.acknowledge(any()) } returns Unit
        // Robolectric's Settings.canDrawOverlays defaults to false. The service
        // short-circuits in that case. Force-grant via mockkStatic.
        mockkStatic(Settings::class)
        every { Settings.canDrawOverlays(any()) } returns true
    }

    @After fun tearDown() {
        unmockkStatic(Settings::class)
        unmockkObject(AcknowledgeAreYouThere)
        // Drain anything static show() queued so other tests see a clean state.
        val shadow = shadowOf(appCtx as Application)
        while (shadow.nextStartedService != null) { /* peek-and-pop */ }
    }

    private fun showIntent() = Intent(appCtx, AreYouThereOverlayService::class.java).apply {
        action = AreYouThereOverlayService.ACTION_SHOW
        putExtra(AreYouThereOverlayService.EXTRA_MESSAGE, "test message")
    }

    private fun dismissIntent() = Intent(appCtx, AreYouThereOverlayService::class.java).apply {
        action = AreYouThereOverlayService.ACTION_DISMISS
    }

    /** Reads the private `overlayView` field — no public accessor exists. */
    private fun overlayViewOf(service: AreYouThereOverlayService): View? {
        val field = AreYouThereOverlayService::class.java.getDeclaredField("overlayView")
        field.isAccessible = true
        return field.get(service) as View?
    }

    @Test fun `show action inflates an overlay view`() {
        val service = Robolectric.buildService(AreYouThereOverlayService::class.java, showIntent())
            .create()
            .startCommand(0, 1)
            .get()

        assertNotNull("overlayView should be populated after ACTION_SHOW",
            overlayViewOf(service))
    }

    @Test fun `I'm OK button invokes acknowledge and clears the overlay reference`() {
        val service = Robolectric.buildService(AreYouThereOverlayService::class.java, showIntent())
            .create()
            .startCommand(0, 1)
            .get()

        val overlay = overlayViewOf(service)!!
        overlay.findViewById<Button>(R.id.buttonImOk).performClick()

        verify(exactly = 1) { AcknowledgeAreYouThere.acknowledge(any()) }
        assertNull("dismissal must null out the cached view", overlayViewOf(service))
    }

    @Test fun `dismiss action removes the overlay without calling acknowledge`() {
        val controller = Robolectric.buildService(AreYouThereOverlayService::class.java, showIntent())
            .create()
            .startCommand(0, 1)
        val service = controller.get()
        assertNotNull(overlayViewOf(service))

        controller.withIntent(dismissIntent()).startCommand(0, 2)

        assertNull(overlayViewOf(service))
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `show twice does not stack overlays`() {
        val controller = Robolectric.buildService(AreYouThereOverlayService::class.java, showIntent())
            .create()
            .startCommand(0, 1)
        val service = controller.get()
        val first = overlayViewOf(service)
        assertNotNull(first)

        controller.withIntent(showIntent()).startCommand(0, 2)

        // Same view retained — the service explicitly returns early if overlayView != null.
        assertEquals("second show must keep the existing view, not replace it",
            first, overlayViewOf(service))
    }

    @Test fun `overlay message is rendered from the extra`() {
        val service = Robolectric.buildService(AreYouThereOverlayService::class.java, showIntent())
            .create()
            .startCommand(0, 1)
            .get()

        val overlay = overlayViewOf(service)!!
        val messageView = overlay.findViewById<TextView>(R.id.textAreYouThereMessage)
        assertEquals("test message", messageView.text.toString())
    }

    @Test fun `static show dispatches a start intent with ACTION_SHOW and the message extra`() {
        AreYouThereOverlayService.show(appCtx, "hello")

        val intent = shadowOf(appCtx as Application).nextStartedService
        assertEquals(AreYouThereOverlayService.ACTION_SHOW, intent?.action)
        assertEquals("hello", intent?.getStringExtra(AreYouThereOverlayService.EXTRA_MESSAGE))
    }

    @Test fun `static dismiss does not call startForegroundService`() {
        // startForegroundService requires startForeground() within 5s; dismiss
        // should use stopService instead. Robolectric records start* calls
        // under nextStartedService; stopService is not recorded there.
        AreYouThereOverlayService.dismiss(appCtx)

        assertNull("dismiss must not start the service",
            shadowOf(appCtx as Application).nextStartedService)
    }
}
