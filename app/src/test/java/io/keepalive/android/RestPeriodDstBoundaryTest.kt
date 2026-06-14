package io.keepalive.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Documents how [calculateOffsetDateTimeExcludingRestPeriod] behaves across
 * DST transitions. The function walks wall-clock minutes with Calendar.add(),
 * which moves in real time — so:
 *
 *  - With no rest period in the way, the returned instant is always exactly
 *    offsetMinutes of REAL time after the start, even across a transition.
 *  - A rest period that spans the fall-back transition is one real hour
 *    LONGER than its wall-clock span (the replayed hour is also skipped),
 *    and one spanning spring-forward is one real hour SHORTER.
 *
 * US 2026 transitions (America/Chicago): spring forward Mar 8 02:00→03:00,
 * fall back Nov 1 02:00→01:00.
 */
class RestPeriodDstBoundaryTest {

    private val chicago = TimeZone.getTimeZone("America/Chicago")

    // All scenarios are in 2026 and start at :30 past the hour.
    private fun chicagoCalendar(month: Int, day: Int, hour: Int): Calendar =
        Calendar.getInstance(chicago).apply {
            clear()
            set(2026, month, day, hour, 30, 0)
        }

    private fun wallClock(utcMillis: Long): Pair<Int, Int> {
        val cal = Calendar.getInstance(chicago).apply { timeInMillis = utcMillis }
        return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
    }

    @Test fun `offset across fall-back with no rest period is exact real time`() {
        // Oct 31 23:30 CDT + 240 minutes crosses the Nov 1 fall-back. The rest
        // period (12:00-13:00) is nowhere near the walked range.
        val start = chicagoCalendar(Calendar.OCTOBER, 31, 23)
        val rest = RestPeriod(12, 0, 13, 0)

        val result = calculateOffsetDateTimeExcludingRestPeriod(start, 240, rest, "forward")

        assertEquals(
            "with no rest minutes skipped the alarm must be exactly 240 real minutes out",
            240L * 60_000L, result.timeInMillis - start.timeInMillis
        )
        // 23:30 CDT + 4 real hours = 02:30 CST (the replayed hour already passed)
        assertEquals(2 to 30, wallClock(result.timeInMillis))
    }

    @Test fun `rest period spanning fall-back skips the replayed hour too`() {
        // Nov 1 00:30 CDT, rest 01:00-03:00, offset 60. The walk spends
        // 30 active minutes (00:30-00:59), then skips 01:00-01:59 CDT,
        // 01:00-01:59 CST (replayed hour), and 02:00-02:59 CST — 180 real
        // rest minutes for a 2-wall-clock-hour rest period — then counts the
        // remaining 30 active minutes, landing at 03:30 CST.
        val start = chicagoCalendar(Calendar.NOVEMBER, 1, 0)
        val rest = RestPeriod(1, 0, 3, 0)

        val result = calculateOffsetDateTimeExcludingRestPeriod(start, 60, rest, "forward")

        assertEquals(
            "60 active + 180 rest minutes = 240 real minutes",
            240L * 60_000L, result.timeInMillis - start.timeInMillis
        )
        assertEquals(3 to 30, wallClock(result.timeInMillis))
    }

    @Test fun `rest period spanning spring-forward is one real hour shorter`() {
        // Mar 8 00:30 CST, rest 01:00-03:00, offset 60. Wall clock jumps from
        // 01:59 CST straight to 03:00 CDT, so the rest period is only 60 real
        // minutes. 30 active + 60 rest + 30 active = 120 real minutes,
        // landing at 03:30 CDT.
        val start = chicagoCalendar(Calendar.MARCH, 8, 0)
        val rest = RestPeriod(1, 0, 3, 0)

        val result = calculateOffsetDateTimeExcludingRestPeriod(start, 60, rest, "forward")

        assertEquals(
            "60 active + 60 rest minutes = 120 real minutes",
            120L * 60_000L, result.timeInMillis - start.timeInMillis
        )
        assertEquals(3 to 30, wallClock(result.timeInMillis))
    }
}
