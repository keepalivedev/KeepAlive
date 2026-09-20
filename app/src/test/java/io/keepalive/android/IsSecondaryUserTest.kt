package io.keepalive.android

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins [isSecondaryUser], the check behind the main screen's secondary-profile
 * warning (issue #215): only the owner user is started at boot, so an install in
 * any other user cannot run after a reboot until someone switches to that user.
 */
@RunWith(RobolectricTestRunner::class)
class IsSecondaryUserTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val userManager get() = appCtx.getSystemService(Context.USER_SERVICE) as UserManager

    @Test
    @Config(sdk = [23, 35])
    fun `the owner user is not reported`() {
        shadowOf(userManager).setIsSystemUser(true)

        assertFalse(isSecondaryUser(appCtx))
    }

    @Test
    @Config(sdk = [23, 35])
    fun `any other user is reported`() {
        shadowOf(userManager).setIsSystemUser(false)

        assertTrue(isSecondaryUser(appCtx))
    }

    @Test
    @Config(sdk = [33, 35])
    fun `a work profile is not reported because it starts together with its owner`() {
        shadowOf(userManager).setIsSystemUser(false)
        shadowOf(userManager).setManagedProfile(true)

        assertFalse(isSecondaryUser(appCtx))
    }

    @Test
    @Config(sdk = [28])
    fun `below API 30 a work profile cannot be told apart so it is reported`() {
        shadowOf(userManager).setIsSystemUser(false)
        shadowOf(userManager).setManagedProfile(true)

        assertTrue(isSecondaryUser(appCtx))
    }
}
