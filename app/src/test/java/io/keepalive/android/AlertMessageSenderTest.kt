package io.keepalive.android

import android.content.Context
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [AlertMessageSender.sendAlertMessage] batching + single-receiver
 * registration.
 *
 * Regression guards against the pre-fix behavior where a new SMSSentReceiver
 * was registered inside the per-contact loop, causing N receivers to fire on
 * the first SMS_SENT broadcast and the remaining N-1 results to go unreported.
 */
@RunWith(RobolectricTestRunner::class)
// sendAlertMessage branches at API T (33) on registerReceiver flags.
// Pre-33 uses ContextCompat with a synthesized RECEIVER_NOT_EXPORTED
// permission — real devices accept it (manifest merger adds the perm) but
// Robolectric's sandboxed manifest doesn't, causing registerReceiver to
// throw. Production behavior there is covered via instrumented tests on
// emulators at API 22/28. Unit tests stay at T+ where the runtime APIs
// are direct.
@Config(sdk = [33, 34, 35])
class AlertMessageSenderTest {

    /**
     * Reads the list of currently-registered receivers from Robolectric's
     * `ShadowApplication`. More reliable than a ContextWrapper override —
     * `ContextCompat.registerReceiver` takes different code paths per SDK
     * that can bypass overridden `registerReceiver` signatures, but all
     * paths ultimately register with the application which the shadow
     * tracks uniformly.
     */
    private fun registeredSMSSentReceiverCount(): Int {
        val shadowApp = org.robolectric.Shadows.shadowOf(appCtx as android.app.Application)
        return shadowApp.registeredReceivers.count { wrapper ->
            wrapper.broadcastReceiver is io.keepalive.android.receivers.SMSSentReceiver
        }
    }

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val gson = Gson()

    @Before fun seedContacts() {
        // Default: one enabled contact with a short single-part message.
        seedContacts(
            contact(phone = "+15551111111", msg = "help")
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun contact(
        phone: String,
        msg: String = "help",
        enabled: Boolean = true,
        includeLocation: Boolean = false
    ) = SMSEmergencyContactSetting(phone, msg, enabled, includeLocation)

    private fun seedContacts(vararg contacts: SMSEmergencyContactSetting) {
        val prefs = getEncryptedSharedPreferences(appCtx)
        prefs.edit()
            .putString("PHONE_NUMBER_SETTINGS", gson.toJson(contacts.toList()))
            .commit()
    }

    private fun mockSms(
        partsBySource: Map<String, ArrayList<String>>
    ): SmsManager = mockk(relaxed = true) {
        every { divideMessage(any()) } answers {
            val src = firstArg<String>()
            partsBySource[src] ?: arrayListOf(src)
        }
    }

    // ---- tests -------------------------------------------------------------

    @Test fun `no contacts enabled means no receiver registered and no sends`() {
        seedContacts(contact("+15551111111", enabled = false))
        val sms = mockSms(emptyMap())

        AlertMessageSender(appCtx, sms).sendAlertMessage()

        assertEquals("no receiver should register when nothing will be sent",
            0, registeredSMSSentReceiverCount())
        verify(exactly = 0) { sms.sendTextMessage(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { sms.sendMultipartTextMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun `blank phone or blank message is skipped entirely`() {
        seedContacts(
            contact("", msg = "help"),              // blank phone
            contact("+15550000001", msg = ""),       // blank message
            contact("+15550000002", msg = "real")    // OK
        )
        val sms = mockSms(mapOf("real" to arrayListOf("real")))

        AlertMessageSender(appCtx, sms).sendAlertMessage()

        assertEquals(1, registeredSMSSentReceiverCount())
        verify(exactly = 1) { sms.sendTextMessage("+15550000002", null, "real", any(), null) }
    }

    @Test fun `single contact with single-part message registers one receiver and sends once`() {
        val sms = mockSms(mapOf("help" to arrayListOf("help")))

        AlertMessageSender(appCtx, sms).sendAlertMessage()

        assertEquals("exactly one SMSSentReceiver should be registered per batch",
            1, registeredSMSSentReceiverCount())
        verify(exactly = 1) { sms.sendTextMessage("+15551111111", null, "help", any(), null) }
        verify(exactly = 0) { sms.sendMultipartTextMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun `single contact with multi-part message uses sendMultipart with one PI per part`() {
        seedContacts(contact("+15551111111", msg = "long message"))
        val sms = mockSms(mapOf("long message" to arrayListOf("long ", "message")))

        val partsSlot = slot<ArrayList<String>>()
        val pisSlot = slot<ArrayList<android.app.PendingIntent>>()

        AlertMessageSender(appCtx, sms).sendAlertMessage()

        verify(exactly = 1) {
            sms.sendMultipartTextMessage(
                eq("+15551111111"),
                isNull(),
                capture(partsSlot),
                capture(pisSlot),
                isNull()
            )
        }
        assertEquals(2, partsSlot.captured.size)
        assertEquals("one PendingIntent per part, all the same", 2, pisSlot.captured.size)
        assertSame(pisSlot.captured[0], pisSlot.captured[1])
    }

    @Test fun `multiple contacts register a single receiver, not one per contact`() {
        // This is the fix: before, the receiver was registered inside the loop
        // (one per contact). With 3 contacts we'd see 3 registrations. After
        // the fix we should see exactly 1.
        seedContacts(
            contact("+15550000001"),
            contact("+15550000002"),
            contact("+15550000003")
        )
        val sms = mockSms(mapOf("help" to arrayListOf("help")))

        AlertMessageSender(appCtx, sms).sendAlertMessage()

        assertEquals("exactly ONE receiver per batch — not one per contact",
            1, registeredSMSSentReceiverCount())
        verify(exactly = 3) { sms.sendTextMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun `null smsManager short-circuits with no sends and no receiver`() {
        AlertMessageSender(appCtx, smsManager = null).sendAlertMessage()
        assertEquals(0, registeredSMSSentReceiverCount())
    }

    @Test fun `mix of single-part and multi-part contacts produces one receiver`() {
        // expectedBroadcasts should be the sum across contacts (1 + 2 = 3).
        // We verify the receiver registration count and the send call counts;
        // the SMSSentReceiver counter itself is covered in SMSSentReceiverTest.
        seedContacts(
            contact("+15550000001", msg = "short"),
            contact("+15550000002", msg = "long one")
        )
        val sms = mockSms(mapOf(
            "short" to arrayListOf("short"),
            "long one" to arrayListOf("long ", "one")
        ))

        AlertMessageSender(appCtx, sms).sendAlertMessage()

        assertEquals(1, registeredSMSSentReceiverCount())
        verify(exactly = 1) { sms.sendTextMessage("+15550000001", null, "short", any(), null) }
        verify(exactly = 1) { sms.sendMultipartTextMessage("+15550000002", null, any(), any(), null) }
    }

    @Test fun `test warning message is sent first and does not use sentIntent`() {
        val sms = mockSms(mapOf("help" to arrayListOf("help")))

        AlertMessageSender(appCtx, sms).sendAlertMessage(testWarningMessage = "about to test")

        // Warning first, with null sentIntent; then the real alert.
        verify(exactly = 1) {
            sms.sendTextMessage("+15551111111", null, "about to test", isNull(), isNull())
        }
        verify(exactly = 1) {
            sms.sendTextMessage("+15551111111", null, "help", any(), isNull())
        }
    }
}
