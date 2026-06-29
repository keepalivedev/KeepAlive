package io.keepalive.android

import android.content.Context
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented end-to-end test for the "Are you there?" overlay on a real
 * device, with overlay permission GRANTED. Complements the Robolectric
 * [AreYouThereOverlayTest] (which mocks `Settings.canDrawOverlays`)
 * and the other instrumented tests (which deny the permission so the
 * overlay branch of the alert flow is bypassed).
 *
 * Drives [AreYouThereOverlay.show] directly, which adds the overlay window
 * via WindowManager.addView() under the SYSTEM_ALERT_WINDOW grant — no
 * foreground service is involved.
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

    // The overlay renders reliably, but UiAutomator's accessibility tree
    // intermittently omits the overlay window on a headless CI emulator (~1 run
    // in 10). setUp below makes that as unlikely as possible; this rule re-runs a
    // failed attempt (fresh setUp/tearDown each time) so a transient miss doesn't
    // fail the build — a genuine overlay regression still fails every attempt.
    @get:Rule
    val retry = RetryRule(attempts = 3)

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

        // The overlay renders reliably, but UiAutomator can only traverse it
        // when the emulator is awake, unlocked, and not showing another window.
        // This class runs late in a 37-test suite — after tests that place real
        // calls (the dialer can linger) and after the screen may have idled — so
        // force a known-good state before each test instead of hoping:
        //   stayon          -> screen can't idle-off mid-test
        //   wake + dismiss  -> overlay isn't stranded behind the lock screen
        //   pressHome       -> clear any leftover foreground activity (dialer)
        //   waitForIdle     -> let the window / accessibility state settle
        AlertFlowTestUtil.shell("svc power stayon true")
        device.wakeUp()
        AlertFlowTestUtil.shell("wm dismiss-keyguard")
        device.pressHome()
        device.waitForIdle()

        // Make sure no leftover overlay from a prior test is in the way.
        AreYouThereOverlay.dismiss(targetContext)
        Thread.sleep(300)
    }

    @After fun tearDown() {
        // Remove any overlay so it doesn't leak into the next class.
        AreYouThereOverlay.dismiss(targetContext)
        Thread.sleep(300)
    }

    @Test fun overlayAppearsAndDismissesWhenPermissionGranted() {
        // Direct invocation from the instrumentation context. This hits the
        // real WindowManager.addView — i.e., the production code path that
        // Robolectric can't simulate.
        AreYouThereOverlay.show(targetContext, "test message")

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
        AreYouThereOverlay.show(targetContext, message)

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
        AreYouThereOverlay.show(targetContext, "test")

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
