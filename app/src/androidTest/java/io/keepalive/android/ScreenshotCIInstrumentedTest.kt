package io.keepalive.android

import android.app.KeyguardManager
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
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

        Screengrab.screenshot("1-MainScreen")

        // overflow menu -> Settings
        openActionBarOverflowOrOptionsMenu(ctx)
        val settingsString = ctx.getString(R.string.action_settings)
        waitUntilDisplayed(withText(settingsString))
        onView(withText(settingsString)).perform(click())

        waitUntilDisplayed(withId(R.id.restPeriodRow))
        Screengrab.screenshot("2-SettingsScreen")

        // rest period dialog
        onView(withId(R.id.restPeriodRow)).perform(click())
        waitUntilDisplayed(withId(android.R.id.button2))
        Screengrab.screenshot("3-ConfigureRestPeriodScreen")
        onView(withId(android.R.id.button2)).perform(click())

        // call phone number dialog
        waitUntilDisplayed(withId(R.id.callPhoneRow))
        onView(withId(R.id.callPhoneRow)).perform(click())
        onView(ViewMatchers.isRoot()).perform(ViewActions.closeSoftKeyboard())
        waitUntilDisplayed(withId(android.R.id.button2))
        Screengrab.screenshot("4-ConfigureCallPhoneNumberScreen")
        onView(withId(android.R.id.button2)).perform(click())

        // first SMS contact dialog
        waitUntilDisplayed(withId(R.id.recyclerView))
        onView(withId(R.id.recyclerView)).perform(clickFirstView())
        waitUntilDisplayed(withId(android.R.id.button2))
        Screengrab.screenshot("5-AddSMSPhoneNumberScreen")
        onView(withId(android.R.id.button2)).perform(click())

        // full-screen "Are you there?" overlay (headline safety feature).
        // SYSTEM_ALERT_WINDOW is an appop, not a runtime permission.
        cmdExec("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
        AreYouThereOverlay.show(
            ctx,
            String.format(ctx.getString(R.string.initial_check_notification_text), "60")
        )
        // The overlay is a system window outside the activity hierarchy, so we
        // can't poll for it via Espresso — give it a bounded settle.
        Thread.sleep(3000)
        Screengrab.screenshot("6-AreYouThereOverlayScreen")
        AreYouThereOverlay.dismiss(ctx)
        Thread.sleep(1000)

        // webhook config dialog
        waitUntilDisplayed(withId(R.id.alertWebhookRow))
        onView(withId(R.id.alertWebhookRow)).perform(click())
        waitUntilDisplayed(withId(android.R.id.button2))
        Screengrab.screenshot("7-ConfigureWebhookScreen")
        onView(withId(android.R.id.button2)).perform(click())
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

    /** Poll until [matcher] resolves to a displayed view, retrying on a slow runner. */
    private fun waitUntilDisplayed(matcher: Matcher<View>, timeoutMs: Long = 15_000L) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            try {
                onView(matcher).check(matches(isDisplayed()))
                return
            } catch (_: Throwable) {
                Thread.sleep(250)
            }
        }
        // final attempt — let it throw a meaningful failure if still not there
        onView(matcher).check(matches(isDisplayed()))
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
