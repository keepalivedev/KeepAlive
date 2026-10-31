package io.keepalive.android

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The contact rows on the Settings screen: a contact's optional label replaces the
 * static "Phone Number" heading so the right person can be checked at a glance
 * (issue #188). The number itself always stays visible below it.
 */
@RunWith(RobolectricTestRunner::class)
class PhoneNumberAdapterTest {

    private fun bindRow(contact: SMSEmergencyContactSetting): View {
        // a themed context is needed to inflate the row's SwitchCompat views
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val adapter = PhoneNumberAdapter(
            mutableListOf(contact), getAppSharedPreferences(activity)
        ) {}
        val holder = adapter.onCreateViewHolder(FrameLayout(activity), 0)
        adapter.onBindViewHolder(holder, 0)
        return holder.itemView
    }

    @Test fun `row heading shows the label when the contact has one`() {
        val row = bindRow(SMSEmergencyContactSetting("5550100", "x", true, false, label = "Daughter"))

        assertEquals("Daughter", row.findViewById<TextView>(R.id.phoneNumberTitle).text.toString())
    }

    @Test fun `row heading stays Phone Number when there is no label`() {
        val row = bindRow(SMSEmergencyContactSetting("5550100", "x", true, false))

        assertEquals(row.context.getString(R.string.phone_number_title),
            row.findViewById<TextView>(R.id.phoneNumberTitle).text.toString())
    }
}
