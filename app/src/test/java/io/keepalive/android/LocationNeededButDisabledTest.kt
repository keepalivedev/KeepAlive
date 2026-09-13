package io.keepalive.android

import android.content.Context
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.testing.FakeSharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins [locationNeededButDisabled], the check behind the "Location Services Off"
 * main-screen state (issue #213): a contact or webhook that includes location
 * while the device's location services are off must not be shown as healthy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28, 35])  // LocationManagerCompat reads Settings.Secure below P
class LocationNeededButDisabledTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val prefs = FakeSharedPreferences()

    private fun setLocationServices(enabled: Boolean) {
        val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setLocationEnabled(enabled)
    }

    @Test fun `contact includes location and services are off`() {
        prefs.edit().putBoolean(PrefKeys.LOCATION_ENABLED, true).commit()
        setLocationServices(false)

        assertTrue(locationNeededButDisabled(appCtx, prefs))
    }

    @Test fun `webhook includes location and services are off`() {
        prefs.edit().putBoolean(PrefKeys.WEBHOOK_LOCATION_ENABLED, true).commit()
        setLocationServices(false)

        assertTrue(locationNeededButDisabled(appCtx, prefs))
    }

    @Test fun `nothing includes location so services being off does not matter`() {
        setLocationServices(false)

        assertFalse(locationNeededButDisabled(appCtx, prefs))
    }

    @Test fun `contact includes location and services are on`() {
        prefs.edit().putBoolean(PrefKeys.LOCATION_ENABLED, true).commit()
        setLocationServices(true)

        assertFalse(locationNeededButDisabled(appCtx, prefs))
    }
}
