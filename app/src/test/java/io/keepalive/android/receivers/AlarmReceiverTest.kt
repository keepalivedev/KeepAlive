package io.keepalive.android.receivers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.doAlertCheck
import io.keepalive.android.getEncryptedSharedPreferences
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
        getEncryptedSharedPreferences(appCtx).edit()
            .putBoolean("enabled", true)
            .commit()
    }

    @After fun tearDown() {
        unmockkStatic(ALERT_FUNCTIONS_KT)
    }

    @Test fun `onReceive short-circuits when app is disabled`() {
        getEncryptedSharedPreferences(appCtx).edit().putBoolean("enabled", false).commit()
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
}
