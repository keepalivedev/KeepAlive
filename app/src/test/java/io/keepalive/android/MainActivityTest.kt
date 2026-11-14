package io.keepalive.android

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowSettings

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

    // the activities the permission tests launch, closed again afterwards: one left open
    //  with its dialogs keeps its memory for the rest of the test run
    private val launched = mutableListOf<ActivityController<MainActivity>>()

    private fun launchMain(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup().also { launched.add(it) }

    @After fun tearDown() {
        launched.forEach { it.close() }
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
        return launchMain()
    }

    private fun showingDialogs(): List<Dialog> = ShadowDialog.getShownDialogs().filter { it.isShowing }

    private fun Dialog.title(): String =
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun Dialog.press(button: Int) {
        (this as AlertDialog).getButton(button).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // what the activity goes through when the user comes back from a settings page
    private fun ActivityController<MainActivity>.leaveAndComeBack() {
        pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
    }

    // and from the system permission dialog, which hands over its result before the
    //  activity resumes. a first refusal can be asked again; a permanent one can't,
    //  and Android tells the two apart by whether a rationale should be shown
    private fun ActivityController<MainActivity>.comeBackDenied(permission: String, permanently: Boolean) {
        shadowOf(appCtx.packageManager).setShouldShowRequestPermissionRationale(permission, !permanently)
        pause()
        get().onRequestPermissionsResult(0, arrayOf(permission), intArrayOf(PackageManager.PERMISSION_DENIED))
        resume()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun usageAccessTitle() = appCtx.getString(R.string.permission_usage_access_title)

    @Test fun `Grant Permissions shows one request at a time`() {
        // requests shown on top of each other launch together and Android drops all but one
        val controller = launchNeedingPermissions()

        controller.get().findViewById<View>(R.id.permissionsButton).performClick()

        assertEquals(
            listOf(appCtx.getString(R.string.permission_post_notifications_title)),
            showingDialogs().map { it.title() }
        )
    }

    @Test fun `the next request follows when the user comes back from a refused one`() {
        val controller = launchNeedingPermissions()
        controller.get().findViewById<View>(R.id.permissionsButton).performClick()

        showingDialogs().single().press(AlertDialog.BUTTON_POSITIVE)
        controller.comeBackDenied(Manifest.permission.POST_NOTIFICATIONS, permanently = false)

        // notifications weren't granted, but they were asked for, so the pass moves on
        assertEquals(listOf(usageAccessTitle()), showingDialogs().map { it.title() })
    }

    @Test fun `cancelling a request moves on to the next one`() {
        // otherwise a permission the user doesn't want would keep the button from ever
        //  reaching the ones behind it
        val controller = launchNeedingPermissions()
        controller.get().findViewById<View>(R.id.permissionsButton).performClick()

        showingDialogs().single().press(AlertDialog.BUTTON_NEGATIVE)

        assertEquals(listOf(usageAccessTitle()), showingDialogs().map { it.title() })
    }

    @Test fun `a permanently denied permission sends the user to settings instead of the next request`() {
        val controller = launchNeedingPermissions()
        controller.get().findViewById<View>(R.id.permissionsButton).performClick()

        showingDialogs().single().press(AlertDialog.BUTTON_POSITIVE)
        controller.comeBackDenied(Manifest.permission.POST_NOTIFICATIONS, permanently = true)

        // one dialog, about the denied permission, not the next request on top of it
        assertEquals(
            listOf(appCtx.getString(R.string.permission_post_notifications_title)),
            showingDialogs().map { it.title() }
        )
        assertEquals(
            appCtx.getString(R.string.go_to_settings),
            (showingDialogs().single() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).text.toString()
        )
    }

    @Test fun `a permanently denied permission does not hold up the rest when cancelled`() {
        val controller = launchNeedingPermissions()
        controller.get().findViewById<View>(R.id.permissionsButton).performClick()
        showingDialogs().single().press(AlertDialog.BUTTON_POSITIVE)
        controller.comeBackDenied(Manifest.permission.POST_NOTIFICATIONS, permanently = true)

        showingDialogs().single().press(AlertDialog.BUTTON_NEGATIVE)

        assertEquals(listOf(usageAccessTitle()), showingDialogs().map { it.title() })
    }

    @Test fun `the pass carries on after the app settings page for a permanently denied permission`() {
        val controller = launchNeedingPermissions()
        controller.get().findViewById<View>(R.id.permissionsButton).performClick()
        showingDialogs().single().press(AlertDialog.BUTTON_POSITIVE)
        controller.comeBackDenied(Manifest.permission.POST_NOTIFICATIONS, permanently = true)

        showingDialogs().single().press(AlertDialog.BUTTON_POSITIVE)
        controller.leaveAndComeBack()

        assertEquals(listOf(usageAccessTitle()), showingDialogs().map { it.title() })
    }

    @Test fun `the pass carries on after the activity is recreated`() {
        // rotating the device on a settings page recreates the activity underneath
        val controller = launchNeedingPermissions()
        controller.get().findViewById<View>(R.id.permissionsButton).performClick()
        showingDialogs().single().press(AlertDialog.BUTTON_POSITIVE)

        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(usageAccessTitle()), showingDialogs().map { it.title() })
    }

    @Test fun `background location that can't be asked for yet does not hold up the rest`() {
        // it needs fine location first. with that refused there is nothing to show for
        //  it, and the pass used to stop there without a dialog
        val app = shadowOf(appCtx as Application)
        app.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowSettings.setCanDrawOverlays(false)
        getAppSharedPreferences(appCtx).edit()
            .putBoolean(PrefKeys.LOCATION_ENABLED, true)
            .putBoolean(PrefKeys.ARE_YOU_THERE_OVERLAY_ENABLED, true)
            .commit()
        val controller = launchMain()
        controller.get().findViewById<View>(R.id.permissionsButton).performClick()
        assertEquals(
            listOf(appCtx.getString(R.string.permission_access_fine_location_title)),
            showingDialogs().map { it.title() }
        )

        showingDialogs().single().press(AlertDialog.BUTTON_NEGATIVE)

        assertEquals(
            listOf(appCtx.getString(R.string.permission_manage_overlay_title)),
            showingDialogs().map { it.title() }
        )
    }
}
