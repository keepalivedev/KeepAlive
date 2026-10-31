package io.keepalive.android

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins [isBackgroundRestricted], the check behind the background-restriction
 * warning on the main screen and from the alarm receiver (issue #194).
 */
@RunWith(RobolectricTestRunner::class)
class IsBackgroundRestrictedTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    private fun setRestricted(restricted: Boolean) {
        val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(am).setBackgroundRestricted(restricted)
    }

    @Test
    @Config(sdk = [28, 35])
    fun `restricted background is reported on API 28 and up`() {
        setRestricted(true)

        assertTrue(isBackgroundRestricted(appCtx))
    }

    @Test
    @Config(sdk = [28, 35])
    fun `unrestricted background is not reported`() {
        setRestricted(false)

        assertFalse(isBackgroundRestricted(appCtx))
    }

    @Test
    @Config(sdk = [23])
    fun `below API 28 the setting does not exist so nothing is reported`() {
        setRestricted(true)

        assertFalse(isBackgroundRestricted(appCtx))
    }
}
