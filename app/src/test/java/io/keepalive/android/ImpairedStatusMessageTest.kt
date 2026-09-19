package io.keepalive.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [impairedStatusMessage], the text shown under the "May Be Impaired" status.
 * A background restriction replaces the usual status lines with instructions the user
 * can act on (issue #194). The secondary-user note (issue #215) is a standing
 * limitation and must stay visible beneath whichever message is showing.
 */
class ImpairedStatusMessageTest {

    private val active = "Last activity detected at: 1:40 PM"
    private val restricted = "Background activity is restricted"
    private val note = "Installed in a secondary user profile"

    @Test fun `a secondary user keeps the usual status lines and gets the note beneath them`() {
        assertEquals("$active\n\n$note", impairedStatusMessage(active, null, note))
    }

    @Test fun `a background restriction replaces the usual status lines`() {
        assertEquals(restricted, impairedStatusMessage(active, restricted, null))
    }

    @Test fun `a restricted secondary user sees both warnings`() {
        assertEquals("$restricted\n\n$note", impairedStatusMessage(active, restricted, note))
    }

    @Test fun `the note is not added twice when the status is refreshed again`() {
        val once = impairedStatusMessage(active, null, note)

        assertEquals(once, impairedStatusMessage(once, null, note))
    }

    @Test fun `without either condition the message is left alone`() {
        assertEquals(active, impairedStatusMessage(active, null, null))
    }
}
