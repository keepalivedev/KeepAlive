package io.keepalive.android.receivers

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.AppController
import io.keepalive.android.doAlertCheck
import io.keepalive.android.getAppSharedPreferences
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

private const val ALERT_FUNCTIONS_KT = "io.keepalive.android.AlertFunctionsKt"

/**
 * Tests the `enabled`-preference gate in [AlarmReceiver.onReceive] and the
 * stage wiring. The deep logic of [doAlertCheck] is covered elsewhere —
 * here we mock it to confirm *whether* and *with what stage* it's called.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmReceiverTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        mockkStatic(ALERT_FUNCTIONS_KT)
        every { doAlertCheck(any<Context>(), any()) } returns Unit
        // Default: app enabled
        getAppSharedPreferences(appCtx).edit()
            .putBoolean("enabled", true)
            .commit()
    }

    @After fun tearDown() {
        unmockkStatic(ALERT_FUNCTIONS_KT)
    }

    @Test fun `onReceive short-circuits when app is disabled`() {
        getAppSharedPreferences(appCtx).edit().putBoolean("enabled", false).commit()
        val intent = Intent().putExtra("AlarmStage", "periodic")

        AlarmReceiver().onReceive(appCtx, intent)

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `onReceive calls doAlertCheck with periodic stage from intent`() {
        val intent = Intent().putExtra("AlarmStage", "periodic")

        AlarmReceiver().onReceive(appCtx, intent)

        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
    }

    @Test fun `onReceive calls doAlertCheck with final stage from intent`() {
        val intent = Intent().putExtra("AlarmStage", "final")

        AlarmReceiver().onReceive(appCtx, intent)

        verify(exactly = 1) { doAlertCheck(any<Context>(), "final") }
    }

    @Test fun `onReceive with no AlarmStage extra defaults to periodic`() {
        AlarmReceiver().onReceive(appCtx, Intent())

        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
    }

    @Test fun `onReceive swallows exceptions from doAlertCheck`() {
        // BroadcastReceiver.onReceive must not throw — we log and move on.
        // Verified by asserting onReceive returns normally.
        every { doAlertCheck(any<Context>(), any()) } throws RuntimeException("boom")
        val intent = Intent().putExtra("AlarmStage", "periodic")

        AlarmReceiver().onReceive(appCtx, intent)  // must not throw
    }

    @Test fun `final alarm that fired way after schedule downgrades to periodic`() {
        // The stale-final heuristic: if delaySeconds > followupMinutes*60, stage
        // is downgraded. Followup default is 60 min = 3600s. Simulate a delay
        // of 2 hours.
        val now = System.currentTimeMillis()
        val twoHoursAgo = now - 2 * 60 * 60 * 1000L
        val intent = Intent()
            .putExtra("AlarmStage", "final")
            .putExtra("AlarmTimestamp", twoHoursAgo)

        AlarmReceiver().onReceive(appCtx, intent)

        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
        verify(exactly = 0) { doAlertCheck(any<Context>(), "final") }
    }

    @Test fun `fresh final alarm stays final`() {
        // Fired on time (delaySeconds near 0). The rule keeps it as final.
        val intent = Intent()
            .putExtra("AlarmStage", "final")
            .putExtra("AlarmTimestamp", System.currentTimeMillis())

        AlarmReceiver().onReceive(appCtx, intent)

        verify(exactly = 1) { doAlertCheck(any<Context>(), "final") }
    }

    // --- background SEND_SMS permission check (issue #192) -----------------

    private val enabledContactJson =
        """[{"phoneNumber":"5550100","alertMessage":"test","isEnabled":true,"includeLocation":false}]"""

    private fun seedSmsContact(json: String = enabledContactJson) {
        getAppSharedPreferences(appCtx).edit()
            .putString("PHONE_NUMBER_SETTINGS", json)
            .commit()
    }

    private fun grantPermissions(vararg permissions: String) {
        shadowOf(appCtx as Application).grantPermissions(*permissions)
    }

    private fun hasPermissionNotification(): Boolean {
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return shadowOf(nm).getNotification(null, AppController.PERMISSION_REVOKED_NOTIFICATION_ID) != null
    }

    @Test fun `revoked SMS permission with enabled contact posts a notification`() {
        // POST_NOTIFICATIONS granted so the helper can post; SEND_SMS not granted
        grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        seedSmsContact()

        AlarmReceiver().onReceive(appCtx, Intent().putExtra("AlarmStage", "periodic"))

        assertTrue("permission-revoked notification should be posted",
            hasPermissionNotification())
        assertNotEquals("notified-at marker should be written",
            0L, getAppSharedPreferences(appCtx).getLong("sms_permission_notified_at", 0L))
        // the alert check must still run — the permission check never blocks it
        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
    }

    @Test fun `revoked SMS permission without any enabled contact stays silent`() {
        grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        seedSmsContact("""[{"phoneNumber":"5550100","alertMessage":"test","isEnabled":false,"includeLocation":false}]""")

        AlarmReceiver().onReceive(appCtx, Intent().putExtra("AlarmStage", "periodic"))

        assertTrue("no notification expected when no enabled contact",
            !hasPermissionNotification())
    }

    @Test fun `revoked SMS permission notifies at most once per day`() {
        grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        seedSmsContact()

        AlarmReceiver().onReceive(appCtx, Intent().putExtra("AlarmStage", "periodic"))
        val firstNotifiedAt = getAppSharedPreferences(appCtx).getLong("sms_permission_notified_at", 0L)

        // clear the posted notification, then fire again — the rate limit
        // (not the still-posted guard) must prevent a second post
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()

        AlarmReceiver().onReceive(appCtx, Intent().putExtra("AlarmStage", "periodic"))

        assertTrue("no second notification within 24h", !hasPermissionNotification())
        assertEquals("notified-at marker unchanged on rate-limited run",
            firstNotifiedAt,
            getAppSharedPreferences(appCtx).getLong("sms_permission_notified_at", 0L))
    }

    @Test fun `granted SMS permission clears the notified marker`() {
        grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.SEND_SMS)
        seedSmsContact()
        getAppSharedPreferences(appCtx).edit()
            .putLong("sms_permission_notified_at", 12345L)
            .commit()

        AlarmReceiver().onReceive(appCtx, Intent().putExtra("AlarmStage", "periodic"))

        assertTrue("no notification when permission is granted", !hasPermissionNotification())
        assertEquals("marker cleared once permission is granted again",
            0L, getAppSharedPreferences(appCtx).getLong("sms_permission_notified_at", 0L))
    }
}
