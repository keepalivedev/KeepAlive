package io.keepalive.android

import android.Manifest
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tools.fastlane.screengrab.Screengrab
import tools.fastlane.screengrab.UiAutomatorScreenshotStrategy
import tools.fastlane.screengrab.locale.LocaleTestRule
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import android.view.View
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf


@RunWith(AndroidJUnit4::class)
class AppScreenshotsInstrumentedTest {

    // start in the main activity
    @get:Rule
    var activityRule: ActivityScenarioRule<MainActivity> = ActivityScenarioRule(MainActivity::class.java)

    // don't need to do anything else, just add this rule and specify the locales in the Fastfile
    @Rule @JvmField
    val localeTestRule = LocaleTestRule()

    @Test
    fun testTakeAppScreenshots() {

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        loadLocaleSpecificTestSharedPrefs(targetContext)

        // screenshot of the main screen
        Screengrab.screenshot("1-MainScreen")

        // Open the overflow menu
        openActionBarOverflowOrOptionsMenu(targetContext)

        // this doesn't work for some reason
        //onView(withId(R.id.action_settings)).perform(click())
        Thread.sleep(1000)

        val settingsString = targetContext.getString(R.string.action_settings)
        onView(withText(settingsString)).perform(click())
        Thread.sleep(2000)

        // screenshot of the settings screen
        Screengrab.screenshot("2-SettingsScreen")

        // open the configure rest period screen and take a screenshot
        onView(withId(R.id.restPeriodRow)).perform(click())
        Thread.sleep(2000)
        Screengrab.screenshot("3-ConfigureRestPeriodScreen")
        onView(withId(android.R.id.button2)).perform(click())
        Thread.sleep(2000)

        // open the edit call phone number screen, close the soft keyboard, and take a screenshot
        onView(withId(R.id.callPhoneRow)).perform(click())
        onView(ViewMatchers.isRoot()).perform(ViewActions.closeSoftKeyboard())
        Thread.sleep(2000)
        Screengrab.screenshot("4-ConfigureCallPhoneNumberScreen")
        onView(withId(android.R.id.button2)).perform(click())
        Thread.sleep(2000)

        // click the first SMS contact to open the edit screen and take a screenshot
        onView(withId(R.id.recyclerView)).perform(clickFirstView())
        Thread.sleep(2000)
        Screengrab.screenshot("5-AddSMSPhoneNumberScreen")
        onView(withId(android.R.id.button2)).perform(click())
    }

    // we can't do this with the other shared prefs because those are loaded before
    //  the test locale is set and the strings will be wrong
    private fun loadLocaleSpecificTestSharedPrefs(context: Context) {
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        val gson = Gson()

        // add 2 example SMS contacts
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

    // click the first child view (as opposed to the item, like with actionOnItemAtPosition())
    private fun clickFirstView(): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return allOf(isDisplayed(), isAssignableFrom(RecyclerView::class.java))
            }

            override fun getDescription(): String {
                return "Click on the first SMS contact"
            }

            override fun perform(uiController: UiController, view: View) {
                val recyclerView = view as RecyclerView
                if (recyclerView.childCount > 0) {
                    val firstItem = recyclerView.getChildAt(0)
                    firstItem.performClick()
                }
            }
        }
    }

    companion object {

        // the permissions we need for the app to show up normally
        private val permissions = listOf(

            // to test manually: adb shell pm grant io.keepalive.android android.permission.SEND_SMS
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.SYSTEM_ALERT_WINDOW
        )

        // this runs before the app is launched, though after it is installed?
        // can't do any locale-specific stuff here, because the locale is set after this runs
        @JvmStatic
        @BeforeClass
        fun beforeAll(): Unit {
            println("Test starting up...")

            // in order for the app to appear under normal operation, there has to be a recent
            //  lock/unlock event or it will think that there is no recent activity. this will
            //  check whether the phone is locked or unlocked and toggle it as needed
            // assumes a phone pin of 1234
            toggleDeviceLock()

            Screengrab.setDefaultScreenshotStrategy(UiAutomatorScreenshotStrategy())

            // this just sets the time to 12:30 and removes the notification icons...
            // CleanStatusBar.enableWithDefaults()

            val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

            // to test manually
            // adb shell appops set io.keepalive.android android:get_usage_stats allow
            // adb shell appops set io.keepalive.android android:schedule_exact_alarm allow
            // adb shell appops set io.keepalive.android AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore

            // grant usage stats permissions
            cmdExec("appops set ${targetContext.packageName} android:get_usage_stats allow")

            // grant schedule exact alarm permissions
            cmdExec("appops set ${targetContext.packageName} android:schedule_exact_alarm allow")

            // disable app hibernation restrictions
            cmdExec("appops set ${targetContext.packageName} AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore")

            // preload some settings
            loadTestSharedPrefs(targetContext)

            // grant all the permissions
            permissions.forEach { permission ->
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                    targetContext.packageName,
                    permission
                )
            }

            // brief sleep after doing setup before the app is launched
            Thread.sleep(1000)
        }

        @JvmStatic
        @AfterClass
        fun afterAll(): Unit {
            println("Tests done...")
            //CleanStatusBar.disable()
        }

        // execute a shell command and return the output as a string
        private fun cmdExec(cmd: String): String {
            println("Executing command: $cmd")
            val parcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            val inputStream = FileInputStream(parcelFileDescriptor.fileDescriptor)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val stringBuilder = StringBuilder()

            var line: String? = reader.readLine()
            while (line != null) {
                stringBuilder.append(line)
                stringBuilder.append("\n")
                line = reader.readLine()
            }

            reader.close()
            inputStream.close()
            parcelFileDescriptor.close()

            return stringBuilder.toString()
        }

        private fun toggleDeviceLock() {

            // to ensure we have a PIN, try to clear the existing one and then re-set it
            println(cmdExec("locksettings clear --old 1234"))
            Thread.sleep(1000)
            println(cmdExec("locksettings set-pin 1234"))
            Thread.sleep(5000)

            if (!isDeviceLocked()) {
                println("Locking device...")
                cmdExec("input keyevent KEYCODE_POWER")
                Thread.sleep(1000)

                // this will toggle the screen back on
                cmdExec("input keyevent KEYCODE_POWER")
                Thread.sleep(1000)
            }

            println("Device should be on lock screen, attempting to unlock...")

            // this forces it to the enter pin screen
            println(cmdExec("input keyevent KEYCODE_MENU"))
            Thread.sleep(1000)

            // put in the pin
            println(cmdExec("input text 1234"))
            Thread.sleep(1000)

            // the enter key
            println(cmdExec("input keyevent KEYCODE_ENTER"))
            Thread.sleep(1000)

            println("Done toggling device lock")
        }

        // check whether the device is locked or not
        private fun isDeviceLocked(): Boolean {
            val result = cmdExec("dumpsys power")

            return if (result.contains("mHoldingWakeLockSuspendBlocker=false") && result.contains("mHoldingDisplaySuspendBlocker=false")) {
                println("Device is locked")
                true
            } else {
                println("Device is unlocked")
                false
            }
        }

        // load test values into the shared preferences
        private fun loadTestSharedPrefs(context: Context) {
            val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            val gson = Gson()

            // an example rest period
            val restPeriods = mutableListOf<RestPeriod>()
            restPeriods.add(
                RestPeriod(
                    startHour = 22,
                    startMinute = 0,
                    endHour = 6,
                    endMinute = 0,
                )
            )

            // set an empty SMS contact so that when the main page loads the 'test SMS alert'
            //  button is visible.  actual contacts will be added later
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

                // leave these at the default
                putString("time_period_hours", "12")
                putString("followup_time_period_minutes", "60")

                // make it think there is an alarm in the future
                putLong("NextAlarmTimestamp", System.currentTimeMillis() + 1000 * 60 * 60 * 12)

                // enable monitoring and auto restart
                putBoolean("enabled", true)
                putBoolean("auto_restart_monitoring", true)
                apply()
            }
        }
    }
}