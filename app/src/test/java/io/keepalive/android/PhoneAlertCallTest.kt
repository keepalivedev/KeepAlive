package io.keepalive.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Tests [makeAlertCall] — dispatches a `tel:` ACTION_CALL intent, enables the
 * speakerphone extra, and posts a "call sent" notification. Verifies the
 * permission gate and the blank-number guard too.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneAlertCallTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val shadowApp get() = shadowOf(appCtx as Application)

    @Before fun setUp() {
        // POST_NOTIFICATIONS is needed on Tiramisu+ or the status notif gets suppressed.
        shadowApp.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        getEncryptedSharedPreferences(appCtx).edit().clear().commit()
        nm.cancelAll()
        // Drain any previously-queued activities
        while (shadowApp.nextStartedActivity != null) { /* drain */ }
    }

    @Test fun `no-op when phone number is blank`() {
        shadowApp.grantPermissions(Manifest.permission.CALL_PHONE)
        getEncryptedSharedPreferences(appCtx).edit().putString("contact_phone", "").commit()

        makeAlertCall(appCtx)

        assertNull("no tel intent should fire when phone is blank",
            shadowApp.nextStartedActivity)
        assertEquals(0, shadowOf(nm).allNotifications.size)
    }

    @Test fun `no-op when phone number is missing entirely`() {
        shadowApp.grantPermissions(Manifest.permission.CALL_PHONE)
        // no "contact_phone" written

        makeAlertCall(appCtx)

        assertNull(shadowApp.nextStartedActivity)
    }

    @Test fun `no-op when CALL_PHONE permission is not granted`() {
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("contact_phone", "+15551234567")
            .commit()
        // deliberately do NOT grant CALL_PHONE

        makeAlertCall(appCtx)

        assertNull("no call should fire without CALL_PHONE permission",
            shadowApp.nextStartedActivity)
    }

    @Test fun `fires ACTION_CALL with tel scheme for the configured number`() {
        shadowApp.grantPermissions(Manifest.permission.CALL_PHONE)
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("contact_phone", "+15551234567")
            .commit()

        makeAlertCall(appCtx)

        val intent = shadowApp.nextStartedActivity
        assertEquals(Intent.ACTION_CALL, intent?.action)
        assertEquals(Uri.parse("tel:+15551234567"), intent?.data)
    }

    @Test fun `enables speakerphone via TelecomManager extra`() {
        shadowApp.grantPermissions(Manifest.permission.CALL_PHONE)
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("contact_phone", "+15551234567")
            .commit()

        makeAlertCall(appCtx)

        val intent = shadowApp.nextStartedActivity
        assertTrue("speakerphone must be requested",
            intent?.getBooleanExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false) == true)
    }

    @Test fun `uses NEW_TASK flag to launch from a non-activity context`() {
        shadowApp.grantPermissions(Manifest.permission.CALL_PHONE)
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("contact_phone", "+15551234567")
            .commit()

        makeAlertCall(appCtx)

        val intent = shadowApp.nextStartedActivity
        assertTrue("tel intent must have FLAG_ACTIVITY_NEW_TASK",
            (intent!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test fun `posts a call-sent notification`() {
        shadowApp.grantPermissions(Manifest.permission.CALL_PHONE)
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("contact_phone", "+15551234567")
            .commit()

        makeAlertCall(appCtx)

        val active = nm.activeNotifications
        assertEquals("exactly one call-alert status notification", 1, active.size)
        assertEquals(AppController.CALL_ALERT_SENT_NOTIFICATION_ID, active[0].id)
    }
}
