package io.keepalive.android

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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

    @Test fun `the label heading is sized to its own text so a wrapped translation is not clipped`() {
        // the dialog's older headings share height by weight; a heading that wraps while
        //  the others stay on one line would get an equal share and lose its second line
        val activity = launch()
        activity.findViewById<Button>(R.id.addButton).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog

        val params = dialog.findViewById<TextView>(R.id.labelDialogTitle)!!.layoutParams
                as LinearLayout.LayoutParams

        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height)
        assertEquals(0f, params.weight, 0f)
    }

    @Test fun `the contact form can scroll so nothing is cut off on a short screen`() {
        // AlertDialog does not scroll a custom view by itself, and the label made the form
        //  taller. the location switch at the bottom has to stay reachable
        val activity = launch()
        activity.findViewById<Button>(R.id.addButton).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog

        var ancestor = dialog.findViewById<View>(R.id.dialogLocationSwitch)!!.parent
        var insideScrollView = false
        while (ancestor != null) {
            if (ancestor is ScrollView) {
                insideScrollView = true
                break
            }
            ancestor = ancestor.parent
        }

        assertTrue("the form should sit inside a ScrollView", insideScrollView)
    }

    @Test fun `adding a contact with a label saves the label`() {
        assertEquals("Daughter", addContactThroughDialog("Daughter").label)
    }

    @Test fun `a blank label is saved as no label`() {
        assertNull(addContactThroughDialog("   ").label)
    }
}
