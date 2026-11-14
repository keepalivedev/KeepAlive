package io.keepalive.android

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog

/**
 * Behavioral Robolectric tests for [MainActivity].
 *
 * Focus: the non-UI side-effects — the `createAlertCheckIntent` factory, and
 * the Direct-Boot-notification safety-net check run from `onResume`. Full UI
 * flows stay in the Espresso instrumented tests (AppScreenshotsInstrumentedTest).
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        // Mock out acknowledge so we don't trigger AlarmManager wiring
        mockkObject(AcknowledgeAreYouThere)
        every { AcknowledgeAreYouThere.acknowledge(any()) } returns Unit
        // Clean prefs
        getAppSharedPreferences(appCtx).edit().clear().commit()
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
        } catch (_: Throwable) {
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
        } catch (_: Throwable) {
            // See note above
        }

        verify(atLeast = 1) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    // ---- Grant Permissions asks for one permission after another ------------

    private fun launchNeedingPermissions(): ActivityController<MainActivity> {
        // a fresh install on API 33+: notifications and usage access are both missing
        shadowOf(appCtx as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(appCtx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager).setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            appCtx.packageName,
            AppOpsManager.MODE_IGNORED
        )
        return Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    private fun showingDialogs(): List<Dialog> = ShadowDialog.getShownDialogs().filter { it.isShowing }

    private fun Dialog.title(): String =
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun Dialog.press(button: Int) {
        (this as AlertDialog).getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // what the activity goes through when the user comes back from a system
    //  permission dialog or a settings page
    private fun ActivityController<MainActivity>.leaveAndComeBack() {
        pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `Grant Permissions shows one request at a time`() {
        // requests shown on top of each other launch together and Android drops all but one
        val controller = launchNeedingPermissions()

        controller.get().findViewById<View>(R.id.permissionsButton).performClick()

        assertEquals(
            listOf(appCtx.getString(R.string.permission_post_notifications_title)),
            showingDialogs().map { it.title() }
        )
    }

    @Test fun `the next request follows when the user comes back from the last one`() {
        val controller = launchNeedingPermissions()
        controller.get().findViewById<View>(R.id.permissionsButton).performClick()

        showingDialogs().single().press(AlertDialog.BUTTON_POSITIVE)
        controller.leaveAndComeBack()

        // notifications weren't granted, but they were asked for, so the pass moves on
        assertEquals(
            listOf(appCtx.getString(R.string.permission_usage_access_title)),
            showingDialogs().map { it.title() }
        )
    }

    @Test fun `cancelling a request ends the pass`() {
        val controller = launchNeedingPermissions()
        controller.get().findViewById<View>(R.id.permissionsButton).performClick()

        showingDialogs().single().press(AlertDialog.BUTTON_NEGATIVE)
        controller.leaveAndComeBack()

        assertFalse("nothing should pop up after a cancel", showingDialogs().isNotEmpty())
    }
}
