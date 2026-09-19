package io.keepalive.android

import android.app.KeyguardManager
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
 * Pins [isOtherUserActive] (issue #215). Usage events are per user, so an install in
 * the owner profile sees nothing while the person works in another profile. Two
 * device-wide signals stand in: this user not being in the foreground, and the
 * keyguard being hidden. Together they mean someone is using the device right now.
 */
@RunWith(RobolectricTestRunner::class)
class IsOtherUserActiveTest {

    private fun contextWith(userForeground: Boolean, keyguardLocked: Boolean): Context {
        val userManager = mockk<UserManager> { every { isUserForeground } returns userForeground }
        val keyguardManager = mockk<KeyguardManager> { every { isKeyguardLocked } returns keyguardLocked }
        return mockk {
            every { getSystemService(Context.USER_SERVICE) } returns userManager
            every { getSystemService(Context.KEYGUARD_SERVICE) } returns keyguardManager
        }
    }

    @Test
    @Config(sdk = [33, 35])
    fun `another user in front with the keyguard hidden means the device is in use`() {
        assertTrue(isOtherUserActive(contextWith(userForeground = false, keyguardLocked = false)))
    }

    @Test
    @Config(sdk = [33, 35])
    fun `this user in front is ordinary usage and is left to the usage events`() {
        assertFalse(isOtherUserActive(contextWith(userForeground = true, keyguardLocked = false)))
    }

    @Test
    @Config(sdk = [33, 35])
    fun `another user in front behind the keyguard is not activity`() {
        assertFalse(isOtherUserActive(contextWith(userForeground = false, keyguardLocked = true)))
    }

    @Test
    @Config(sdk = [28])
    fun `below API 31 the foreground user cannot be read so nothing is reported`() {
        assertFalse(isOtherUserActive(mockk(relaxed = true)))
    }

    @Test
    @Config(sdk = [35])
    fun `a failing system call is treated as no activity`() {
        val userManager = mockk<UserManager> { every { isUserForeground } throws SecurityException("denied") }
        val context = mockk<Context> {
            every { getSystemService(Context.USER_SERVICE) } returns userManager
            every { getSystemService(Context.KEYGUARD_SERVICE) } returns mockk<KeyguardManager>(relaxed = true)
        }

        assertFalse(isOtherUserActive(context))
    }
}
