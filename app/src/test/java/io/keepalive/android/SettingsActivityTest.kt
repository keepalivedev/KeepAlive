package io.keepalive.android

import android.content.Context
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

/**
 * Robolectric tests for the plain on/off switches on [SettingsActivity].
 * The dialog-driven settings are covered by the instrumented
 * SettingsPersistence tests.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        getAppSharedPreferences(appCtx).edit().clear().commit()
    }

    private fun launch(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    // ---- debug logging switch (issue #166) ---------------------------------

    @Test fun `debug logging switch is on by default`() {
        val activity = launch()

        val switch: SwitchCompat = activity.findViewById(R.id.debugLoggingSwitch)
        assertTrue("logging stays on unless the user turns it off", switch.isChecked)
    }

    @Test fun `turning the debug logging switch off persists the preference`() {
        val activity = launch()

        val switch: SwitchCompat = activity.findViewById(R.id.debugLoggingSwitch)
        switch.isChecked = false

        assertFalse("switch must write the preference the logger reads",
            getAppSharedPreferences(appCtx).getBoolean("debug_logging_enabled", true))
    }

    // ---- contact label (issue #188) ----------------------------------------

    private fun addContactThroughDialog(label: String): SMSEmergencyContactSetting {
        val activity = launch()
        activity.findViewById<Button>(R.id.addButton).performClick()

        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.findViewById<EditText>(R.id.labelInput)!!.setText(label)
        dialog.findViewById<EditText>(R.id.phoneNumberInput)!!.setText("5550100")
        dialog.findViewById<EditText>(R.id.alertMessageInput)!!.setText("Please check on me")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val saved: MutableList<SMSEmergencyContactSetting> =
            loadJSONSharedPreference(getAppSharedPreferences(appCtx), "PHONE_NUMBER_SETTINGS")
        return saved.single()
    }

    @Test fun `adding a contact with a label saves the label`() {
        assertEquals("Daughter", addContactThroughDialog("Daughter").label)
    }

    @Test fun `a blank label is saved as no label`() {
        assertNull(addContactThroughDialog("   ").label)
    }
}
