package io.keepalive.android

import android.content.Context
import android.os.UserManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins [isInBackgroundUser] (issue #215): true only for a full user that is not the one
 * on screen. A profile (work, clone, private space) shares its parent's screen and is
 * never "the foreground user" itself, so for an install inside one the platform's
 * isUserForeground is always false and must not be read as "another user is in front".
 * Both the other-user activity check and the prompt's screen wake-up depend on this.
 */
@RunWith(RobolectricTestRunner::class)
class IsInBackgroundUserTest {

    private fun contextWith(userForeground: Boolean, profile: Boolean = false): Context {
        val userManager = mockk<UserManager> {
            every { isUserForeground } returns userForeground
            every { isManagedProfile } returns profile
            every { isProfile } returns profile
        }
        return mockk {
            every { getSystemService(Context.USER_SERVICE) } returns userManager
        }
    }

    @Test
    @Config(sdk = [33, 35])
    fun `a full user that is not on screen is a background user`() {
        assertTrue(isInBackgroundUser(contextWith(userForeground = false)))
    }

    @Test
    @Config(sdk = [33, 35])
    fun `the user on screen is not a background user`() {
        assertFalse(isInBackgroundUser(contextWith(userForeground = true)))
    }

    @Test
    @Config(sdk = [33, 35])
    fun `a work profile is visible with its parent so it is not a background user`() {
        assertFalse(isInBackgroundUser(contextWith(userForeground = false, profile = true)))
    }

    @Test
    @Config(sdk = [28])
    fun `below API 31 the foreground user cannot be read so nothing is reported`() {
        assertFalse(isInBackgroundUser(mockk(relaxed = true)))
    }

    @Test
    @Config(sdk = [35])
    fun `a failing system call is treated as not in the background`() {
        val userManager = mockk<UserManager> { every { isProfile } throws SecurityException("denied") }
        val context = mockk<Context> {
            every { getSystemService(Context.USER_SERVICE) } returns userManager
        }

        assertFalse(isInBackgroundUser(context))
    }
}
