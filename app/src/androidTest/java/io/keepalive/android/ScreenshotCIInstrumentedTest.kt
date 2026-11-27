package io.keepalive.android

import android.app.KeyguardManager
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.gson.Gson
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.locale.LocaleTestRule
import android.view.View
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * CI variant of [AppScreenshotsInstrumentedTest], hardened for the
 * software-rendered (no-GPU) emulators on GitHub Actions runners. Captures the
 * same 7 screenshots with the same names, so the output drops straight into
 * fastlane/metadata. Driven by the `screenshot_ci` Fastlane lane and
 * `.github/workflows/screenshots.yml`.
 *
 * Why a separate class instead of editing the local test: the local one is
 * tuned for a dev's accelerated emulator; this one trades the fixed
 * `Thread.sleep` timing for behaviour that survives a slow, jank-prone runner:
 *  - keeps the screen awake (`svc power stayon true`) so the device can't
 *    re-lock between per-locale instrumentation passes (the failure that killed
 *    the 2nd locale locally),
 *  - unlocks the keyguard with a verified poll loop ([KeyguardManager]) rather
 *    than fixed sleeps, so a laggy frame can't leave MainActivity behind the
 *    keyguard (NoActivityResumedException),
 *  - waits for MainActivity to be RESUMED and for each target view to actually
 *    be displayed before tapping / capturing.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotCIInstrumentedTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<MainActivity> = ActivityScenarioRule(MainActivity::class.java)

    @Rule
    @JvmField
    val localeTestRule = LocaleTestRule()

    @Test
    fun takeScreenshots() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        loadLocaleSpecificTestSharedPrefs(ctx)

        // Don't touch the UI until MainActivity is actually resumed — i.e. not
        // behind the keyguard or an ANR dialog on a slow runner.
        waitForResumedActivity()
        waitForWindowFocus()

        // the unlock in setUp() is done with key events, which takes the device out of
        //  touch mode, and the focused page then draws Android's grey focus highlight
        //  over itself. no one using the app by touch sees that
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        waitForScreenDrawn()

        Screengrab.screenshot("1-MainScreen")

        // overflow menu -> Settings
        openActionBarOverflowOrOptionsMenu(ctx)
        val settingsString = ctx.getString(R.string.action_settings)
        waitUntilDisplayed(withText(settingsString))
        onView(withText(settingsString)).perform(click())

        waitUntilDisplayed(withId(R.id.restPeriodRow))
        Screengrab.screenshot("2-SettingsScreen")

        // rest period dialog
        openDialog(withId(R.id.restPeriodRow))
        waitUntilDisplayed(withId(android.R.id.button2))
        Screengrab.screenshot("3-ConfigureRestPeriodScreen")
        dismissDialog()

        // call phone number dialog
        waitUntilDisplayed(withId(R.id.callPhoneRow))
        openDialog(withId(R.id.callPhoneRow))
        onView(ViewMatchers.isRoot()).perform(ViewActions.closeSoftKeyboard())
        waitUntilDisplayed(withId(android.R.id.button2))
        Screengrab.screenshot("4-ConfigureCallPhoneNumberScreen")
        dismissDialog()

        // first SMS contact dialog
        waitUntilDisplayed(withId(R.id.recyclerView), scroll = true)
        openDialog(withId(R.id.recyclerView), clickFirstView())
        waitUntilDisplayed(withId(android.R.id.button2))
        Screengrab.screenshot("5-AddSMSPhoneNumberScreen")
        dismissDialog()

        // full-screen "Are you there?" overlay (headline safety feature).
        // SYSTEM_ALERT_WINDOW is an appop, not a runtime permission.
        cmdExec("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
        AreYouThereOverlay.show(
            ctx,
            String.format(ctx.getString(R.string.initial_check_notification_text), "60"),
            System.currentTimeMillis() + 60 * 60_000L
        )
        // The overlay is a system window outside the activity hierarchy, so we
        // can't poll for it via Espresso — give it a bounded settle.
        Thread.sleep(3000)
        Screengrab.screenshot("6-AreYouThereOverlayScreen")
        AreYouThereOverlay.dismiss(ctx)
        Thread.sleep(1000)

        // webhook config dialog
        waitUntilDisplayed(withId(R.id.alertWebhookRow), scroll = true)
        openDialog(withId(R.id.alertWebhookRow))
        waitUntilDisplayed(withId(android.R.id.button2))
        Screengrab.screenshot("7-ConfigureWebhookScreen")
        dismissDialog()
    }

    /** Poll until at least one activity is in the RESUMED stage (or time out). */
    private fun waitForResumedActivity(timeoutMs: Long = 20_000L) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var resumed = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).isNotEmpty()
            }
            if (resumed) return
            Thread.sleep(250)
        }
    }

    /**
     * Poll until the resumed activity's window has focus. RESUMED isn't enough on
     * a slow runner: a system dialog (e.g. an ANR prompt) can still hold focus,
     * and Espresso gives up on the first interaction after 10 seconds. Close
     * system dialogs while waiting, and name the focused window if it never comes.
     */
    private fun waitForWindowFocus(timeoutMs: Long = 60_000L) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            var focused = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                focused = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).any { it.hasWindowFocus() }
            }
            if (focused) return
            cmdExec("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
            Thread.sleep(1000)
        }
        val focus = cmdExec("dumpsys window displays").lineSequence()
            .filter { it.contains("mCurrentFocus") || it.contains("mFocusedApp") }
            .joinToString(" | ") { it.trim() }
        throw AssertionError("MainActivity never got window focus: $focus")
    }

    /**
     * Poll until the screen shows real content and has stopped changing. The first
     * capture could otherwise land on the blank starting window, or on a frame that
     * still has the focus highlight from before touch mode was turned on.
     */
    private fun waitForScreenDrawn(timeoutMs: Long = 15_000L) {
        val end = System.currentTimeMillis() + timeoutMs
        var previous: List<Int>? = null
        while (System.currentTimeMillis() < end) {
            val sample = sampleScreen()
            if (sample != null && sample.toSet().size > 2 && sample == previous) return
            previous = sample
            Thread.sleep(250)
        }
    }

    /** A coarse grid of pixel colours from below the status bar, or null if nothing was captured. */
    private fun sampleScreen(): List<Int>? {
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: return null
        val pixels = ArrayList<Int>()
        for (y in shot.height / 16 until shot.height step shot.height / 32) {
            for (x in 0 until shot.width step shot.width / 32) {
                pixels.add(shot.getPixel(x, y))
            }
        }
        shot.recycle()
        return pixels
    }

    /**
     * Poll until [matcher] resolves to a displayed view, retrying on a slow runner.
     * With [scroll], a view below the fold is scrolled to first: the settings page
     * is taller than a landscape tablet's screen.
     */
    private fun waitUntilDisplayed(
        matcher: Matcher<View>,
        timeoutMs: Long = 15_000L,
        scroll: Boolean = false
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (_: Throwable) {
                if (scroll) {
                    try {
                        onView(matcher).perform(ViewActions.scrollTo())
                    } catch (_: Throwable) { /* not reachable yet — retry */ }
                }
                Thread.sleep(250)
            }
        }
        // final attempt — let it throw a meaningful failure if still not there
        onView(matcher).check(matches(isDisplayed()))
    }

    /**
     * Tap [target] and wait for the dialog it opens. On a slow runner a tap can be
     * lost, or Espresso can oversleep between down and up and turn it into a long
     * press; either way nothing opens. So tap again until a dialog shows.
     */
    private fun openDialog(target: Matcher<View>, tap: ViewAction = click()) {
        val end = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < end) {
            try {
                onView(target).perform(tap)
            } catch (_: Throwable) { /* the dialog may already be in front of it */ }
            val shownBy = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < shownBy) {
                if (isDialogShowing()) return
                Thread.sleep(250)
            }
        }
    }

    /**
     * Dismiss the current dialog. We can't rely on clicking its Cancel button:
     * a tall dialog (e.g. the rest-period editor with two TimePickers) pushes
     * the button bar off a short screen, so the button can be ~4% visible and
     * Espresso refuses to click it. The Back key dismisses a cancelable dialog
     * regardless. Close the keyboard first (text-field dialogs open it, and it
     * would eat the first Back), then press Back until the dialog is gone.
     */
    private fun dismissDialog() {
        try {
            onView(ViewMatchers.isRoot()).perform(ViewActions.closeSoftKeyboard())
        } catch (_: Throwable) { /* no keyboard up — fine */ }
        val end = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < end && isDialogShowing()) {
            pressBack()
            Thread.sleep(500)
        }
    }

    /** True while a dialog (its negative button) is on screen, even if clipped. */
    private fun isDialogShowing(): Boolean = try {
        onView(withId(android.R.id.button2)).check(matches(isDisplayed()))
        true
    } catch (_: Throwable) {
        false
    }

    // click the first child view (as opposed to the item, like with actionOnItemAtPosition())
    private fun clickFirstView(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> =
                allOf(isDisplayed(), isAssignableFrom(RecyclerView::class.java))

            override fun getDescription(): String = "Click on the first SMS contact"

            override fun perform(uiController: UiController, view: View) {
                val recyclerView = view as RecyclerView
                if (recyclerView.childCount > 0) {
                    recyclerView.getChildAt(0).performClick()
                }
            }
        }
    }

    // we can't do this with the other shared prefs because those are loaded before
    //  the test locale is set and the strings will be wrong
    private fun loadLocaleSpecificTestSharedPrefs(context: Context) {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val gson = Gson()

        val smsContactList = listOf(
            SMSEmergencyContactSetting(
                alertMessage = context.getString(R.string.default_alert_message),
                includeLocation = true,
                isEnabled = true,
                phoneNumber = "2345678901"
            ),
            SMSEmergencyContactSetting(
                alertMessage = context.getString(R.string.default_alert_message_alt),
                includeLocation = false,
                isEnabled = true,
                phoneNumber = "+13456789012"
            ),
        )
        with(sharedPrefs.edit()) {
            putString("PHONE_NUMBER_SETTINGS", gson.toJson(smsContactList))
            apply()
        }
    }

    companion object {

        @JvmStatic
        @BeforeClass
        fun setUp() {
            println("CI screenshot test starting up...")

            Screengrab.setDefaultScreenshotStrategy(UiAutomatorScreenshotStrategy())

            TestSetupUtil.setupTestEnvironment()

            // the app only shows the normal "monitoring active" UI if there's a
            //  recent lock/unlock event, so set a PIN and unlock (verified).
            //  This also leaves the screen "stay on" so it can't re-lock during
            //  this locale's captures.
            ensureUnlockedWithRecentActivity()

            loadTestSharedPrefs(InstrumentationRegistry.getInstrumentation().targetContext)

            Thread.sleep(1000)
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            cmdExec("svc power stayon false")
            println("CI screenshot test done.")
        }

        // Set a PIN, generate a real lock/unlock event, and poll the keyguard
        // until it reports unlocked — retrying the PIN entry rather than
        // relying on fixed sleeps landing on the right frame.
        private fun ensureUnlockedWithRecentActivity() {
            // allow the screen to actually sleep so KEYCODE_SLEEP can produce a
            //  lock event (stay-on would otherwise keep it awake and unlocked)
            cmdExec("svc power stayon false")
            cmdExec("locksettings set-pin 1234")
            Thread.sleep(2000)

            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

            // sleep -> wake produces the lock screen (and the keyguard event)
            cmdExec("input keyevent KEYCODE_SLEEP")
            Thread.sleep(1500)
            cmdExec("input keyevent KEYCODE_WAKEUP")
            Thread.sleep(1500)
            cmdExec("wm dismiss-keyguard")
            Thread.sleep(1000)

            val end = System.currentTimeMillis() + 30_000L
            while (System.currentTimeMillis() < end && keyguard.isKeyguardLocked) {
                cmdExec("input keyevent KEYCODE_MENU")
                cmdExec("input text 1234")
                cmdExec("input keyevent KEYCODE_ENTER")
                Thread.sleep(1500)
            }
            println("keyguard locked after unlock: ${keyguard.isKeyguardLocked}")

            // now keep it awake + unlocked for the rest of this locale's captures
            cmdExec("svc power stayon true")
        }

        // load test values into the shared preferences (locale-independent)
        private fun loadTestSharedPrefs(context: Context) {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val gson = Gson()

            val restPeriods = mutableListOf(
                RestPeriod(startHour = 22, startMinute = 0, endHour = 6, endMinute = 0)
            )

            // an empty SMS contact so the main page's 'test SMS alert' button shows;
            //  the real localized contacts are added in the test body
            val smsContactList = listOf(
                SMSEmergencyContactSetting(
                    alertMessage = "",
                    includeLocation = true,
                    isEnabled = true,
                    phoneNumber = "2345678901"
                )
            )

            with(sharedPrefs.edit()) {
                putString("PHONE_NUMBER_SETTINGS", gson.toJson(smsContactList))
                putString("REST_PERIODS", gson.toJson(restPeriods))
                putString("contact_phone", "2345678901")
                putString("time_period_hours", "12")
                putString("followup_time_period_minutes", "60")
                // make it think there's an alarm in the future
                putLong("NextAlarmTimestamp", System.currentTimeMillis() + 1000 * 60 * 60 * 12)
                putBoolean("enabled", true)
                putBoolean("auto_restart_monitoring", true)
                apply()
            }
        }

        // execute an adb-shell-equivalent command via the instrumentation
        private fun cmdExec(cmd: String): String {
            println("Executing command: $cmd")
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(FileInputStream(pfd.fileDescriptor))).use { reader ->
                reader.forEachLine { sb.appendLine(it) }
            }
            pfd.close()
            return sb.toString()
        }
    }
}
