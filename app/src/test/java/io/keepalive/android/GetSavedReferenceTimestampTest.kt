package io.keepalive.android

import io.keepalive.android.testing.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for the tiny preference-lookup helper. */
class GetSavedReferenceTimestampTest {

    @Test fun `prefers last_activity_timestamp when set`() {
        val prefs = FakeSharedPreferences().apply {
            seed("last_activity_timestamp", 1_000L)
            seed("last_check_timestamp", 2_000L)
        }
        assertEquals(1_000L, getSavedReferenceTimestamp(prefs, fallback = 9_000L))
    }

    @Test fun `falls back to last_check_timestamp when no activity timestamp`() {
        val prefs = FakeSharedPreferences().apply {
            seed("last_check_timestamp", 2_000L)
        }
        assertEquals(2_000L, getSavedReferenceTimestamp(prefs, fallback = 9_000L))
    }

    @Test fun `returns fallback when neither is set`() {
        val prefs = FakeSharedPreferences()
        assertEquals(9_000L, getSavedReferenceTimestamp(prefs, fallback = 9_000L))
    }

    @Test fun `negative or zero activity timestamp falls through to check timestamp`() {
        val prefs = FakeSharedPreferences().apply {
            seed("last_activity_timestamp", 0L)
            seed("last_check_timestamp", 2_000L)
        }
        assertEquals(2_000L, getSavedReferenceTimestamp(prefs, fallback = 9_000L))
    }

    @Test fun `negative check timestamp falls through to fallback`() {
        val prefs = FakeSharedPreferences().apply {
            seed("last_activity_timestamp", -1L)
            seed("last_check_timestamp", -1L)
        }
        assertEquals(9_000L, getSavedReferenceTimestamp(prefs, fallback = 9_000L))
    }
}
