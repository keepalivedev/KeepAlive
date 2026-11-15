package io.keepalive.android

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.testing.FakeSharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Pins [smsPermissionMissing], the check behind asking for the SMS permission as
 * soon as a contact needs it and behind the "SMS Permission Required" main-screen
 * state: setup must not look finished while no SMS alert could be sent.
 */
@RunWith(RobolectricTestRunner::class)
class SmsPermissionMissingTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = FakeSharedPreferences()

    private fun putContacts(json: String) {
        prefs.edit().putString(PrefKeys.PHONE_NUMBER_SETTINGS, json).commit()
    }

    private fun putEnabledContact() {
        putContacts("""[{"phoneNumber":"5550100","alertMessage":"x","isEnabled":true,"includeLocation":false}]""")
    }

    private fun denySms() {
        shadowOf(appCtx as Application).denyPermissions(Manifest.permission.SEND_SMS)
    }

    @Test fun `enabled SMS contact without the permission is missing it`() {
        denySms()
        putEnabledContact()
        assertTrue(smsPermissionMissing(appCtx, prefs))
    }

    @Test fun `enabled SMS contact with the permission is fine`() {
        shadowOf(appCtx as Application).grantPermissions(Manifest.permission.SEND_SMS)
        putEnabledContact()
        assertFalse(smsPermissionMissing(appCtx, prefs))
    }

    @Test fun `no SMS contact needs no permission`() {
        denySms()
        assertFalse(smsPermissionMissing(appCtx, prefs))
    }

    @Test fun `disabled SMS contact needs no permission`() {
        denySms()
        putContacts("""[{"phoneNumber":"5550100","alertMessage":"x","isEnabled":false,"includeLocation":false}]""")
        assertFalse(smsPermissionMissing(appCtx, prefs))
    }
}
