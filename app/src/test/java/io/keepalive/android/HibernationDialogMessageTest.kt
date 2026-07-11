package io.keepalive.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [hibernationDialogMessageResId] (issue #196). The old chain used
 * exact-equality checks (== 31, == 35), so anything newer than 35 fell
 * through to the 32-34 wording. Descending thresholds mean new API levels
 * inherit the newest wording (the suffixless string) automatically.
 */
class HibernationDialogMessageTest {

    @Test fun `API 31 gets its own wording`() {
        assertEquals(R.string.hibernation_dialog_message_api31, hibernationDialogMessageResId(31))
    }

    @Test fun `API 32 through 34 get the api32 wording`() {
        for (sdk in 32..34) {
            assertEquals("sdk $sdk", R.string.hibernation_dialog_message_api32.toLong(),
                hibernationDialogMessageResId(sdk).toLong())
        }
    }

    @Test fun `API 35 gets the newest wording`() {
        assertEquals(R.string.hibernation_dialog_message, hibernationDialogMessageResId(35))
    }

    @Test fun `API levels newer than 35 inherit the newest wording`() {
        // the reported bug: API 37 was shown the 32-34 text
        for (sdk in intArrayOf(36, 37, 40)) {
            assertEquals("sdk $sdk", R.string.hibernation_dialog_message.toLong(),
                hibernationDialogMessageResId(sdk).toLong())
        }
    }
}
