package io.keepalive.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Calendar

/**
 * Pins [formatCountdown] and [formatLastActivityTimestamp], the main-screen
 * time displays simplified in issue #189: no leading zero-hours unit on the
 * countdown, and no date or seconds on a same-day last-activity timestamp.
 */
@RunWith(RobolectricTestRunner::class)
class MainScreenTimeFormatTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    // --- countdown ---

    @Test fun `countdown under an hour drops the hours unit`() {
        // the reported case: "0 hours and 57 minutes" -> "57 min"
        assertEquals("57 min", formatCountdown(ctx, 57))
    }

    @Test fun `countdown with hours shows both units`() {
        assertEquals("1 h 12 min", formatCountdown(ctx, 72))
    }

    @Test fun `countdown of exactly one hour shows zero minutes`() {
        assertEquals("1 h 0 min", formatCountdown(ctx, 60))
    }

    @Test fun `countdown under a minute shows zero minutes`() {
        assertEquals("0 min", formatCountdown(ctx, 0))
    }

    @Test fun `countdown with many hours does not roll over`() {
        assertEquals("26 h 30 min", formatCountdown(ctx, 26 * 60 + 30))
    }

    // --- last activity timestamp ---

    private fun todayAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 42)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun `timestamp from today shows time without date or seconds`() {
        val formatted = formatLastActivityTimestamp(ctx, todayAt(7, 34))

        // no date parts: no year, and short enough to be a bare time
        val year = Calendar.getInstance().get(Calendar.YEAR).toString()
        assertFalse("should not contain the year: $formatted", formatted.contains(year))
        // no seconds: a bare time has exactly one ':' (e.g. "7:34 AM" or "07:34")
        assertEquals("should have no seconds: $formatted", 1, formatted.count { it == ':' })
        assertFalse("should not contain seconds value: $formatted", formatted.contains("42"))
    }

    @Test fun `timestamp from another day includes the date`() {
        val lastWeek = todayAt(7, 34) - 7 * 24 * 60 * 60 * 1000L
        val formatted = formatLastActivityTimestamp(ctx, lastWeek)
        val todayFormatted = formatLastActivityTimestamp(ctx, todayAt(7, 34))

        // the dated version must carry more than the bare time does
        assertNotEquals(todayFormatted, formatted)
        assertTrue("dated version should be longer: '$formatted' vs '$todayFormatted'",
            formatted.length > todayFormatted.length)
    }
}
