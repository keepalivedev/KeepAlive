package io.keepalive.android

import com.google.gson.Gson
import io.keepalive.android.testing.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the optional, display-only contact label (issue #188): contacts stored
 * before the field existed must keep loading, a contact without a label must be
 * stored exactly as before, and wherever a recipient is shown the label appears
 * together with the number so the number itself can still be verified.
 */
class ContactLabelTest {

    private val prefs = FakeSharedPreferences()

    private fun store(json: String) {
        prefs.edit().putString(PrefKeys.PHONE_NUMBER_SETTINGS, json).commit()
    }

    private fun load(): MutableList<SMSEmergencyContactSetting> =
        loadJSONSharedPreference(prefs, PrefKeys.PHONE_NUMBER_SETTINGS)

    // ---- storage -------------------------------------------------------------

    @Test fun `contacts stored before labels existed still load`() {
        store("""[{"phoneNumber":"5550100","alertMessage":"x","isEnabled":true,"includeLocation":false}]""")

        val contact = load().single()

        assertEquals("5550100", contact.phoneNumber)
        assertNull(contact.label)
    }

    @Test fun `a stored label is loaded`() {
        store("""[{"phoneNumber":"5550100","alertMessage":"x","isEnabled":true,"includeLocation":false,"label":"Daughter"}]""")

        assertEquals("Daughter", load().single().label)
    }

    @Test fun `a contact without a label is stored without a label key`() {
        val contacts = mutableListOf(SMSEmergencyContactSetting("5550100", "x", true, false))

        saveSMSEmergencyContactSettings(prefs, contacts, Gson())

        assertFalse("old installs and new ones must share one format",
            prefs.getString(PrefKeys.PHONE_NUMBER_SETTINGS, "")!!.contains("label"))
    }

    @Test fun `a label survives save and load`() {
        val contacts = mutableListOf(
            SMSEmergencyContactSetting("5550100", "x", true, false, label = "Neighbour"))

        saveSMSEmergencyContactSettings(prefs, contacts, Gson())

        assertEquals("Neighbour", load().single().label)
    }

    // ---- display -------------------------------------------------------------

    @Test fun `display text is the label together with the number`() {
        val contact = SMSEmergencyContactSetting("5550100", "x", true, false, label = "Daughter")

        assertEquals("Daughter: 555-0100", smsContactDisplayText(contact, "555-0100"))
    }

    @Test fun `display text falls back to the number when there is no label`() {
        val noLabel = SMSEmergencyContactSetting("5550100", "x", true, false)
        val blankLabel = SMSEmergencyContactSetting("5550100", "x", true, false, label = "  ")

        assertEquals("555-0100", smsContactDisplayText(noLabel, "555-0100"))
        assertEquals("555-0100", smsContactDisplayText(blankLabel, "555-0100"))
    }

    @Test fun `recipient list joins enabled contacts and skips disabled or empty ones`() {
        val contacts = listOf(
            SMSEmergencyContactSetting("1", "x", true, false, label = "Daughter"),
            SMSEmergencyContactSetting("2", "x", false, false, label = "Son"),
            SMSEmergencyContactSetting("", "x", true, false, label = "Nobody"),
            SMSEmergencyContactSetting("3", "x", true, false)
        )

        assertEquals("Daughter: #1, #3", smsContactsDisplayString(contacts) { "#$it" })
    }
}
