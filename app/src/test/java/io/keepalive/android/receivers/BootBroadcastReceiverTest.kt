package io.keepalive.android.receivers

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.AcknowledgeAreYouThere
import io.keepalive.android.doAlertCheck
import io.keepalive.android.getDeviceProtectedPreferences
import io.keepalive.android.getEncryptedSharedPreferences
import io.keepalive.android.isUserUnlocked
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests the boot-intent branching that decides whether to acknowledge a
 * pending "Are you there?" prompt or run a fresh alert check after reboot.
 *
 * Mocks out [doAlertCheck] (covered elsewhere), [AcknowledgeAreYouThere] (same),
 * and [isUserUnlocked] (stateful in Robolectric but brittle to toggle). We
 * only assert on *which* of these gets called per boot scenario.
 */
@RunWith(RobolectricTestRunner::class)
class BootBroadcastReceiverTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val ALERT_FUNCTIONS_KT = "io.keepalive.android.AlertFunctionsKt"
    private val UTILITY_FUNCTIONS_KT = "io.keepalive.android.UtilityFunctionsKt"

    @Before fun setUp() {
        mockkStatic(ALERT_FUNCTIONS_KT)
        mockkStatic(UTILITY_FUNCTIONS_KT)
        mockkObject(AcknowledgeAreYouThere)
        every { doAlertCheck(any<Context>(), any()) } returns Unit
        every { AcknowledgeAreYouThere.acknowledge(any()) } returns Unit
        // Default: app enabled, user unlocked
        every { isUserUnlocked(any()) } returns true
        getEncryptedSharedPreferences(appCtx).edit()
            .putBoolean("enabled", true)
            .commit()
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "periodic")
            .putBoolean("direct_boot_notification_pending", false)
            .commit()
    }

    @After fun tearDown() {
        unmockkStatic(ALERT_FUNCTIONS_KT)
        unmockkStatic(UTILITY_FUNCTIONS_KT)
        unmockkObject(AcknowledgeAreYouThere)
    }

    @Test fun `receiver does nothing when app is disabled`() {
        getEncryptedSharedPreferences(appCtx).edit().putBoolean("enabled", false).commit()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        BootBroadcastReceiver().onReceive(appCtx, intent)

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `receiver ignores unrelated intents`() {
        BootBroadcastReceiver().onReceive(appCtx, Intent("something.else"))

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `BOOT_COMPLETED with pending flag triggers acknowledge`() {
        // User just unlocked — the unlock IS the acknowledgement. We must NOT
        // re-run doAlertCheck with stage=final (which would otherwise alert).
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", true)
            .putString("last_alarm_stage", "final")
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { AcknowledgeAreYouThere.acknowledge(any()) }
        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `BOOT_COMPLETED without pending flag runs doAlertCheck with saved stage`() {
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", false)
            .putString("last_alarm_stage", "periodic")
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
        verify(exactly = 0) { AcknowledgeAreYouThere.acknowledge(any()) }
    }

    @Test fun `BOOT_COMPLETED without pending flag honors a final saved stage`() {
        getDeviceProtectedPreferences(appCtx).edit()
            .putBoolean("direct_boot_notification_pending", false)
            .putString("last_alarm_stage", "final")
            .commit()

        BootBroadcastReceiver().onReceive(appCtx, Intent(Intent.ACTION_BOOT_COMPLETED))

        verify(exactly = 1) { doAlertCheck(any<Context>(), "final") }
    }

    @Test fun `LOCKED_BOOT_COMPLETED when user is unlocked is skipped`() {
        // During an app redeploy/update, both LOCKED_BOOT_COMPLETED and
        // BOOT_COMPLETED fire while the device is already unlocked. The
        // BOOT_COMPLETED handler will take care of things.
        every { isUserUnlocked(any()) } returns true

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.LOCKED_BOOT_COMPLETED"))

        verify(exactly = 0) { doAlertCheck(any<Context>(), any()) }
    }

    @Test fun `LOCKED_BOOT_COMPLETED while still locked runs doAlertCheck with saved stage`() {
        every { isUserUnlocked(any()) } returns false
        getDeviceProtectedPreferences(appCtx).edit()
            .putString("last_alarm_stage", "periodic")
            .commit()

        BootBroadcastReceiver().onReceive(
            appCtx, Intent("android.intent.action.LOCKED_BOOT_COMPLETED"))

        verify(exactly = 1) { doAlertCheck(any<Context>(), "periodic") }
    }
}
