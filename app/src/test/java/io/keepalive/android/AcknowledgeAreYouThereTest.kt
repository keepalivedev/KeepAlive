package io.keepalive.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests [AcknowledgeAreYouThere.acknowledge] — the single handler called when
 * the user responds to the "Are you there?" prompt (I'm-OK button, notification
 * tap, or BOOT_COMPLETED after Direct Boot).
 *
 * Correctness hinges on: the Direct Boot flag is cleared, the last-activity
 * timestamp is updated (so a racing final alarm won't alert), and a fresh
 * periodic alarm is scheduled (which replaces any pending final alarm).
 */
@RunWith(RobolectricTestRunner::class)
// acknowledge() has an `>= N (24)` branch for device-protected prefs;
// matrix exercises pre-N and post-N behavior.
@Config(sdk = [23, 28, 33, 34, 35, 36])
class AcknowledgeAreYouThereTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val UTILITY_FUNCTIONS_KT = "io.keepalive.android.UtilityFunctionsKt"

    @Before fun setUp() {
        mockkStatic(UTILITY_FUNCTIONS_KT)
        // setAlarm hits AlarmManager — mock it out so we can just verify it was called.
        every { setAlarm(any(), any(), any(), any(), any()) } returns Unit
        // Seed prefs with the defaults the acknowledgement reads.
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("time_period_hours", "12")
            .commit()
        // Simulate Direct Boot pending state we expect to clear.
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", true)
            .commit()
    }

    @After fun tearDown() {
        unmockkStatic(UTILITY_FUNCTIONS_KT)
    }

    @Test
    @Config(sdk = [28, 33, 34, 35, 36])  // device-protected storage is API N+
    fun `clears the direct boot notification pending flag on API N+`() {
        AcknowledgeAreYouThere.acknowledge(appCtx)

        assertFalse(getDeviceProtectedPreferences(appCtx)
            .getBoolean("direct_boot_notification_pending", true))
    }

    @Test
    @Config(sdk = [28, 33, 34, 35, 36])
    fun `writes last_activity_timestamp so a racing final alarm sees the user as active`() {
        val before = System.currentTimeMillis()

        AcknowledgeAreYouThere.acknowledge(appCtx)

        val saved = getDeviceProtectedPreferences(appCtx)
            .getLong("last_activity_timestamp", -1L)
        assertTrue("timestamp should be recent (saved=$saved, before=$before)",
            saved >= before)
    }

    @Test fun `schedules a fresh periodic alarm`() {
        val stageSlot = slot<String>()

        AcknowledgeAreYouThere.acknowledge(appCtx)

        verify(exactly = 1) {
            setAlarm(
                eq(appCtx),
                any(),
                any(),
                capture(stageSlot),
                any()
            )
        }
        assertEquals("periodic", stageSlot.captured)
    }

    @Test fun `uses the configured check period for the periodic alarm`() {
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("time_period_hours", "4")
            .commit()
        val periodSlot = slot<Int>()

        AcknowledgeAreYouThere.acknowledge(appCtx)

        verify { setAlarm(any(), any(), capture(periodSlot), any(), any()) }
        assertEquals(4 * 60, periodSlot.captured)
    }

    @Test fun `falls back to 12h check period when preference is unparseable`() {
        getEncryptedSharedPreferences(appCtx).edit()
            .putString("time_period_hours", "not-a-number")
            .commit()
        val periodSlot = slot<Int>()

        AcknowledgeAreYouThere.acknowledge(appCtx)

        verify { setAlarm(any(), any(), capture(periodSlot), any(), any()) }
        assertEquals(12 * 60, periodSlot.captured)
    }

    @Test
    @Config(sdk = [28, 33, 34, 35, 36])
    fun `acknowledgement can be called even when no flag was set`() {
        // Fresh install / never posted prompt: flag is absent. Should be a no-op
        // on the flag but still schedule periodic.
        getDeviceProtectedPreferences(appCtx).edit()
            .remove("direct_boot_notification_pending")
            .commit()

        AcknowledgeAreYouThere.acknowledge(appCtx)

        verify(exactly = 1) { setAlarm(any(), any(), any(), eq("periodic"), any()) }
    }
}
