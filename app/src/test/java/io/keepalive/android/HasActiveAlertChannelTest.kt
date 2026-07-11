package io.keepalive.android

import io.keepalive.android.testing.FakeSharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [hasActiveAlertChannel], the check behind the "No Alert Recipient"
 * main-screen state (issue #187): monitoring must not be presented as a
 * healthy green "active" when no alert could actually be delivered.
 */
class HasActiveAlertChannelTest {

    private val prefs = FakeSharedPreferences()

    private fun putContacts(json: String) {
        prefs.edit().putString(PrefKeys.PHONE_NUMBER_SETTINGS, json).commit()
    }

    @Test fun `nothing configured means no channel`() {
        assertFalse(hasActiveAlertChannel(prefs))
    }

    @Test fun `enabled SMS contact with a number is a channel`() {
        putContacts("""[{"phoneNumber":"5550100","alertMessage":"x","isEnabled":true,"includeLocation":false}]""")
        assertTrue(hasActiveAlertChannel(prefs))
    }

    @Test fun `disabled SMS contact is not a channel`() {
        putContacts("""[{"phoneNumber":"5550100","alertMessage":"x","isEnabled":false,"includeLocation":false}]""")
        assertFalse(hasActiveAlertChannel(prefs))
    }

    @Test fun `enabled SMS contact with a blank number is not a channel`() {
        putContacts("""[{"phoneNumber":"","alertMessage":"x","isEnabled":true,"includeLocation":false}]""")
        assertFalse(hasActiveAlertChannel(prefs))
    }

    @Test fun `one enabled contact among disabled ones is a channel`() {
        putContacts(
            """[{"phoneNumber":"5550100","alertMessage":"x","isEnabled":false,"includeLocation":false},
                {"phoneNumber":"5550101","alertMessage":"x","isEnabled":true,"includeLocation":false}]"""
        )
        assertTrue(hasActiveAlertChannel(prefs))
    }

    @Test fun `call contact is a channel`() {
        prefs.edit().putString(PrefKeys.CONTACT_PHONE, "5550102").commit()
        assertTrue(hasActiveAlertChannel(prefs))
    }

    @Test fun `webhook only counts in webhook builds and only when enabled and configured`() {
        prefs.edit()
            .putBoolean(PrefKeys.WEBHOOK_ENABLED, true)
            .putString(PrefKeys.WEBHOOK_URL, "https://example.com/hook")
            .commit()

        if (BuildConfig.INCLUDE_WEBHOOK) {
            assertTrue(hasActiveAlertChannel(prefs))

            // enabled flag off -> not a channel
            prefs.edit().putBoolean(PrefKeys.WEBHOOK_ENABLED, false).commit()
            assertFalse(hasActiveAlertChannel(prefs))

            // enabled but no URL -> not a channel
            prefs.edit()
                .putBoolean(PrefKeys.WEBHOOK_ENABLED, true)
                .putString(PrefKeys.WEBHOOK_URL, "")
                .commit()
            assertFalse(hasActiveAlertChannel(prefs))
        } else {
            // builds without webhook support ignore webhook prefs entirely
            assertFalse(hasActiveAlertChannel(prefs))
        }
    }
}
