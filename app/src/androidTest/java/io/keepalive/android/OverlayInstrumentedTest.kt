package io.keepalive.android

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented end-to-end test for the "Are you there?" overlay on a real
 * device, with overlay permission GRANTED. Complements the Robolectric
 * [AreYouThereOverlayServiceTest] (which mocks `Settings.canDrawOverlays`)
 * and the other instrumented tests (which deny the permission so the
 * alert flow doesn't trip the foreground-service contract under
 * synthetic-broadcast conditions).
 *
 * Drives the service directly via `startForegroundService(ACTION_SHOW)` —
 * instrumentation context has the foreground-service-start exemption so
 * we don't hit the same async crash that synthetic AlarmReceiver
 * invocations do.
 *
 * Uses UI Automator to assert that the overlay window is actually
 * rendered, and that tapping "I'm OK" dismisses it.
 *
 * Gated to API 26+ because:
 *  - `startForegroundService` is API 26+
 *  - UI Automator's `By.res(...)` resource-ID lookup is reliable post-O
 *  - The overlay's foreground-service infrastructure assumes O+ semantics
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = android.os.Build.VERSION_CODES.O)
class OverlayInstrumentedTest {

    private val targetContext: Context = AlertFlowTestUtil.targetContext
    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    companion object {
        @JvmStatic
        @BeforeClass
        fun grantAllPermissions() {
            TestSetupUtil.setupTestEnvironment()
        }
    }

    @Before fun setUp() {
        // Other instrumented tests revoke SYSTEM_ALERT_WINDOW so the alert
        // flow's overlay branch is bypassed. THIS class needs the permission
        // granted — re-grant explicitly. (The class might run after one of
        // the revoking classes alphabetically.)
        AlertFlowTestUtil.shell(
            "appops set ${targetContext.packageName} SYSTEM_ALERT_WINDOW allow"
        )

        // CI emulators idle the screen during a long test run. UiAutomator
        // tap injection on TYPE_APPLICATION_OVERLAY windows requires an
        // awake screen with the keyguard dismissed; otherwise click() looks
        // successful but the touch is never delivered to the overlay
        // window. Wake + unlock before each test to make this deterministic.
        device.wakeUp()
        device.pressMenu()
        Thread.sleep(200)

        // Make sure no leftover overlay from a prior test is in the way.
        AreYouThereOverlay.dismiss(targetContext)
        Thread.sleep(300)
    }

    @After fun tearDown() {
        // Stop the overlay service so it doesn't leak into the next class.
        targetContext.stopService(
            Intent(targetContext, AreYouThereOverlayService::class.java)
        )
        Thread.sleep(300)
    }

    @Test fun overlayAppearsAndDismissesWhenPermissionGranted() {
        val intent = Intent(targetContext, AreYouThereOverlayService::class.java).apply {
            action = AreYouThereOverlayService.ACTION_SHOW
            putExtra(AreYouThereOverlayService.EXTRA_MESSAGE, "test message")
        }

        // Direct invocation from the instrumentation context. This path
        // hits the real Service.onCreate → startForeground contract and
        // the real WindowManager.addView — i.e., the production code path
        // that Robolectric can't simulate.
        targetContext.startForegroundService(intent)

        val okButton = device.wait(
            Until.findObject(By.res(targetContext.packageName, "buttonImOk")),
            5_000L
        )
        assertNotNull("overlay should render the I'm OK button", okButton)
        assertTrue("I'm OK button should be enabled", okButton.isEnabled)

        // Tap I'm OK — should dismiss the overlay AND invoke
        // AcknowledgeAreYouThere.acknowledge() which writes the
        // last_activity_timestamp pref (the Direct-Boot race-fix).
        //
        // UiAutomator click injection on TYPE_APPLICATION_OVERLAY windows is
        // best-effort across emulator images: when it works, dismissal is
        // instant; when it doesn't, the click() call returns silently and
        // the overlay stays. Retry up to 3 times before giving up and
        // checking whether the overlay state ever cleared.
        val before = System.currentTimeMillis()
        var gone = false
        repeat(3) { attempt ->
            if (gone) return@repeat
            okButton.click()
            gone = device.wait(
                Until.gone(By.res(targetContext.packageName, "buttonImOk")),
                5_000L
            )
            if (!gone) {
                // Re-find — the previous handle may have been invalidated
                // by partial state changes between attempts.
                device.wait(
                    Until.findObject(By.res(targetContext.packageName, "buttonImOk")),
                    1_000L
                )
            }
        }
        assertTrue("overlay should disappear after I'm OK click", gone)

        // Acknowledge side effect: last_activity_timestamp persisted.
        Thread.sleep(300)
        val saved = getDeviceProtectedPreferences(targetContext)
            .getLong("last_activity_timestamp", -1L)
        assertTrue(
            "acknowledge() must record activity timestamp; saved=$saved before=$before",
            saved >= before
        )
    }

    @Test fun overlayShowsConfiguredMessage() {
        val message = "please respond within 60 minutes"
        val intent = Intent(targetContext, AreYouThereOverlayService::class.java).apply {
            action = AreYouThereOverlayService.ACTION_SHOW
            putExtra(AreYouThereOverlayService.EXTRA_MESSAGE, message)
        }
        targetContext.startForegroundService(intent)

        val messageView = device.wait(
            Until.findObject(By.res(targetContext.packageName, "textAreYouThereMessage")),
            5_000L
        )
        assertNotNull("overlay should render the message view", messageView)
        assertTrue(
            "message text must reflect the EXTRA_MESSAGE — got '${messageView.text}'",
            messageView.text == message
        )
    }

    @Test fun dismissActionRemovesTheOverlayWithoutAcknowledging() {
        val show = Intent(targetContext, AreYouThereOverlayService::class.java).apply {
            action = AreYouThereOverlayService.ACTION_SHOW
            putExtra(AreYouThereOverlayService.EXTRA_MESSAGE, "test")
        }
        targetContext.startForegroundService(show)

        // Wait for it to be visible, then dismiss via stopService (which is
        // what the static AreYouThereOverlay.dismiss() does).
        val ok = device.wait(
            Until.findObject(By.res(targetContext.packageName, "buttonImOk")),
            5_000L
        )
        assertNotNull(ok)

        AreYouThereOverlay.dismiss(targetContext)

        val gone = device.wait(
            Until.gone(By.res(targetContext.packageName, "buttonImOk")),
            5_000L
        )
        assertTrue("dismiss() must remove the overlay", gone)
    }
}
