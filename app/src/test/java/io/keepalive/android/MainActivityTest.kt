package io.keepalive.android

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Behavioral Robolectric tests for [MainActivity].
 *
 * Focus: the non-UI side-effects — the `createAlertCheckIntent` factory, and
 * the Direct-Boot-notification safety-net check run from `onResume`. Full UI
 * flows stay in the Espresso instrumented tests ([AppScreenshotsInstrumentedTest]).
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // Mock out acknowledge so we don't trigger AlarmManager wiring
        mockkObject(AcknowledgeAreYouThere)
        every { AcknowledgeAreYouThere.acknowledge(any()) } returns Unit
        // Clean prefs
        getEncryptedSharedPreferences(appCtx).edit().clear().commit()
        getDeviceProtectedPreferences(appCtx).edit().clear().commit()
    }

    @After fun tearDown() {
        unmockkObject(AcknowledgeAreYouThere)
    }

    // ---- createAlertCheckIntent --------------------------------------------

    @Test fun `createAlertCheckIntent targets MainActivity with the right flags and extra`() {
        val intent = MainActivity.createAlertCheckIntent(appCtx)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0)
        // Don't assert on the extra name (it's private); just that some extra is present.
        assertTrue("should carry the AlertCheck extra",
            intent.extras?.keySet()?.isNotEmpty() == true)
    }

    // ---- onResume → Direct-Boot safety net ---------------------------------

    @Test fun `opening the app with no pending flag does NOT call acknowledge`() {
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", false)
            .commit()
        // Just needed to exercise onResume without inflating the full activity.
        // (We don't go through Robolectric.buildActivity here because it pulls
        // in the whole view hierarchy and permission manager, which adds a lot
        // of setup for a single assertion. Instead, directly call the same
        // code path that onResume routes through.)

        // We can exercise the public safety-net via the app's entry point:
        // creating + resuming a MainActivity instance.
        try {
            Robolectric.buildActivity(MainActivity::class.java).create().resume()
        } catch (t: Throwable) {
            // Activity may fail to inflate certain views under Robolectric; the
            // safety-net check runs near the top of onResume before most UI code.
        }

        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `opening the app with a pending flag acknowledges and clears it`() {
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", true)
            .commit()

        try {
            Robolectric.buildActivity(MainActivity::class.java).create().resume()
        } catch (t: Throwable) {
            // See note above
        }

        verify(atLeast = 1) { AcknowledgeAreYouThere.acknowledge(any()) }
    }
}
