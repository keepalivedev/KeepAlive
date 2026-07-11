package io.keepalive.android

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins [firstPermanentlyDeniedPermission], the decision behind routing the user
 * to app settings when a permission can no longer be requested (set to "Not
 * allowed" in system settings / denied permanently). In that state
 * requestPermissions() silently no-ops, so the OK button dead-ends; the callback
 * must detect it via "denied AND no rationale" and open settings instead.
 *
 * Pure function — plain JUnit, no Robolectric needed.
 */
class FirstPermanentlyDeniedPermissionTest {

    private val GRANTED = PackageManager.PERMISSION_GRANTED
    private val DENIED = PackageManager.PERMISSION_DENIED

    // default: every permission is "known" (has an explanation) and has no rationale
    private fun decide(
        permissions: Array<String>,
        grantResults: IntArray,
        rationale: Set<String> = emptySet(),
        known: Set<String> = permissions.toSet()
    ): String? = firstPermanentlyDeniedPermission(
        permissions,
        grantResults,
        shouldShowRationale = { it in rationale },
        isKnown = { it in known }
    )

    @Test fun `all granted returns null`() {
        assertNull(decide(arrayOf("SEND_SMS"), intArrayOf(GRANTED)))
    }

    @Test fun `denied without rationale is permanently denied`() {
        // the reported case: SEND_SMS set to Not allowed in system settings
        assertEquals("SEND_SMS", decide(arrayOf("SEND_SMS"), intArrayOf(DENIED)))
    }

    @Test fun `denied but rationale still showable is not routed to settings`() {
        // soft denial — the system dialog will still appear on the next request
        assertNull(decide(arrayOf("SEND_SMS"), intArrayOf(DENIED), rationale = setOf("SEND_SMS")))
    }

    @Test fun `denied but unknown permission is ignored`() {
        // a permission we don't have an explanation for is not our concern
        assertNull(decide(arrayOf("com.other.PERM"), intArrayOf(DENIED), known = emptySet()))
    }

    @Test fun `returns the permanently denied one among a mixed result`() {
        val result = decide(
            arrayOf("POST_NOTIFICATIONS", "SEND_SMS"),
            intArrayOf(GRANTED, DENIED)
        )
        assertEquals("SEND_SMS", result)
    }

    @Test fun `empty result is treated as a cancellation`() {
        // Android delivers empty arrays when the interaction is interrupted
        assertNull(decide(arrayOf(), intArrayOf()))
    }

    @Test fun `grantResults shorter than permissions does not crash`() {
        // defensive: never index past grantResults
        assertNull(decide(arrayOf("SEND_SMS"), intArrayOf()))
    }
}
