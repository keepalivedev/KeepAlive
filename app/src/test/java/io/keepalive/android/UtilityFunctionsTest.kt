package io.keepalive.android

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import android.content.SharedPreferences
import com.google.gson.Gson
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Calendar
import java.util.TimeZone


// lets try the easy stuff first...
@RunWith(MockitoJUnitRunner::class)
class UtilityFunctionsTest {

    @Mock
    private lateinit var mockSharedPrefs: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    @Mock
    private lateinit var gson: Gson

    @Test
    fun loadSMSEmergencyContactSettings_correctlyLoadsSettings() {
        // test data
        val testJson = "[{\"phoneNumber\":\"1234567890\",\"alertMessage\":\"Help!\",\"isEnabled\":true,\"includeLocation\":true}]"
        `when`(mockSharedPrefs.getString("PHONE_NUMBER_SETTINGS", null)).thenReturn(testJson)

        // Act
        val result: MutableList<SMSEmergencyContactSetting> = loadJSONSharedPreference(mockSharedPrefs,
            "PHONE_NUMBER_SETTINGS")

        // Assert
        assertNotNull(result)
        assertEquals(1, result.size)
        assertEquals("1234567890", result[0].phoneNumber)
        assertEquals("Help!", result[0].alertMessage)
        assertTrue(result[0].isEnabled)
        assertTrue(result[0].includeLocation)
    }

    @Test
    fun saveSMSEmergencyContactSettings_savesCorrectJsonString() {

        // the settings to save
        val phoneNumberList = mutableListOf(
            SMSEmergencyContactSetting(
                phoneNumber = "123-456-7890",
                alertMessage = "Help!",
                isEnabled = true,
                includeLocation = true
            ),
            SMSEmergencyContactSetting(
                phoneNumber = "098-765-4321",
                alertMessage = "Hello!",
                isEnabled = true,
                includeLocation = false
            )
        )

        `when`(mockSharedPrefs.edit()).thenReturn(mockEditor)
        //`when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)

        val expectedJson = gson.toJson(phoneNumberList)

        // Execute
        saveSMSEmergencyContactSettings(mockSharedPrefs, phoneNumberList, gson)

        // this seems to verify the call but doesn't actually save it to mockSharedPrefs
        verify(mockEditor).putString("PHONE_NUMBER_SETTINGS", expectedJson)
        verify(mockEditor).putBoolean("location_enabled", true)
        verify(mockEditor).apply()
    }
    class TestCalculatePastDateTimeExcludingRestPeriod {

        @Test
        fun longCheckPeriod() {
            val targetDateTime: Calendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, 2024)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 3)
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
            }

            val checkPeriodHours = 48.0f
            val restPeriod = RestPeriod(22, 0, 6, 0) // Rest period from 10 PM to 6 AM

            // Assuming the rest period affects 3 nights, each with 8 hours
            val totalRestMinutes = 8 * 60 * 3
            val checkPeriodMinutes = (checkPeriodHours * 60).toLong()
            val expectedDateTime: Calendar = targetDateTime.clone() as Calendar
            expectedDateTime.add(Calendar.MINUTE, -(checkPeriodMinutes + totalRestMinutes).toInt())

            val result = calculateOffsetDateTimeExcludingRestPeriod(targetDateTime, (checkPeriodHours * 60).toInt(), restPeriod, "backward")
            assertEquals(expectedDateTime.timeInMillis, result.timeInMillis)
            //assertEquals(ZonedDateTime.of(expectedDateTime, ZoneId.systemDefault()).withZoneSameInstant(ZoneId.of("UTC")), result)
        }

        @Test
        fun longRestPeriod() {
            val targetDateTime: Calendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, 2024)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 12)
                set(Calendar.MINUTE, 0)
            }
            val checkPeriodHours = 4.0f
            val restPeriod = RestPeriod(0, 0, 12, 0) // Rest period from 12 AM to 12 PM

            // The entire check period falls within the rest period
            val totalRestMinutes = 12 * 60
            val checkPeriodMinutes = (checkPeriodHours * 60).toLong()
            val expectedDateTime: Calendar = targetDateTime.clone() as Calendar
            expectedDateTime.add(Calendar.MINUTE, -(checkPeriodMinutes + totalRestMinutes).toInt())

            val result = calculateOffsetDateTimeExcludingRestPeriod(targetDateTime, (checkPeriodHours * 60).toInt(), restPeriod, "backward")

            assertEquals(expectedDateTime.timeInMillis, result.timeInMillis)
        }
        @Test
        fun startInRestingPeriod() {
            //val targetDateTime = LocalDateTime.of(2024, 1, 1, 1, 0) // 1 AM, within the rest period
            val targetDateTime: Calendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, 2024)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 1)
                set(Calendar.MINUTE, 0)
            }
            val checkPeriodHours = 6.0f
            val restPeriod = RestPeriod(22, 0, 2, 0) // Rest period from 10 PM to 2 AM

            // we start in a resting period
            val totalRestMinutes = 3 * 60
            val checkPeriodMinutes = (checkPeriodHours * 60).toLong()

            val expectedDateTime: Calendar = targetDateTime.clone() as Calendar
            expectedDateTime.add(Calendar.MINUTE, -(checkPeriodMinutes + totalRestMinutes).toInt())

            val result = calculateOffsetDateTimeExcludingRestPeriod(targetDateTime, (checkPeriodHours * 60).toInt(), restPeriod, "backward")
            assertEquals(expectedDateTime.timeInMillis, result.timeInMillis)
        }

        @Test
        fun restPeriodCrossesMidnight() {
            val targetDateTime: Calendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, 2024)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 6)
                set(Calendar.MINUTE, 0)
            }

            val checkPeriodHours = 6.0f
            val restPeriod = RestPeriod(22, 0, 2, 0) // Rest period from 10 PM to 2 AM

            // Since the target time is 6 AM and the check period is 6 hours, the rest period fully affects the calculation
            val totalRestMinutes = 4 * 60 // 4 hours in minutes (entire duration of the rest period)
            val checkPeriodMinutes = (checkPeriodHours * 60).toLong()

            val expectedDateTime: Calendar = targetDateTime.clone() as Calendar
            expectedDateTime.add(Calendar.MINUTE, -(checkPeriodMinutes + totalRestMinutes).toInt())

            val result = calculateOffsetDateTimeExcludingRestPeriod(targetDateTime, (checkPeriodHours * 60).toInt(), restPeriod, "backward")
            assertEquals(expectedDateTime.timeInMillis, result.timeInMillis)
        }

        @Test
        fun restPeriodEntirelyWithinCheckPeriod() {
            val targetDateTime: Calendar = Calendar.getInstance().apply {
                set(Calendar.YEAR, 2024)
                set(Calendar.MONTH, Calendar.JANUARY)
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 18)
                set(Calendar.MINUTE, 0)
            }

            val checkPeriodHours = 8.0f
            val restPeriod = RestPeriod(14, 0, 16, 0) // Rest period from 2 PM to 4 PM

            // The rest period affects 2 hours of the check period
            val totalRestMinutes = 2 * 60 // 2 hours
            val checkPeriodMinutes = (checkPeriodHours * 60).toLong()

            val expectedDateTime: Calendar = targetDateTime.clone() as Calendar
            expectedDateTime.add(Calendar.MINUTE, -(checkPeriodMinutes + totalRestMinutes).toInt())

            val result = calculateOffsetDateTimeExcludingRestPeriod(targetDateTime, (checkPeriodHours * 60).toInt(), restPeriod, "backward")
            assertEquals(expectedDateTime.timeInMillis, result.timeInMillis)
        }
    }

    class TestAdjustTimestampIfInRestPeriod {

        @Test
        fun timestampWithinRestPeriod() {
            val utcTimestampMillis = Instant.parse("2024-01-01T16:00:00Z").toEpochMilli() // 10 AM CST
            val restPeriod = RestPeriod(9, 0, 11, 0) // Rest period from 9 AM to 11 AM
            val localZoneId = TimeZone.getTimeZone("America/Chicago").id // CST

            val adjustedTimestamp = adjustTimestampIfInRestPeriod(utcTimestampMillis, restPeriod, localZoneId)

            val expectedAdjustedTimestamp = Instant.parse("2024-01-01T17:00:00Z").toEpochMilli() // Expected: 11 AM CST
            assertEquals(expectedAdjustedTimestamp, adjustedTimestamp)
        }

        @Test
        fun timestampOutsideRestPeriod() {
            val utcTimestampMillis =Instant.parse("2024-01-01T18:00:00Z").toEpochMilli() // 12 PM CST
            val restPeriod = RestPeriod(1, 0, 3, 0) // Rest period from 1 AM to 3 AM
            val localZoneId = TimeZone.getTimeZone("America/Chicago").id // CST

            val adjustedTimestamp = adjustTimestampIfInRestPeriod(utcTimestampMillis, restPeriod, localZoneId)

            assertEquals(utcTimestampMillis, adjustedTimestamp) // Expecting original timestamp as it's outside the rest period
        }

        @Test
        fun restPeriodCrossMidnight() {
            val utcTimestampMillis = Instant.parse("2024-01-01T04:30:00Z").toEpochMilli() // 10:30 PM CST previous day
            val restPeriod = RestPeriod(22, 0, 2, 0) // Rest period from 10 PM to 2 AM
            val localZoneId = TimeZone.getTimeZone("America/Chicago").id // CST

            val adjustedTimestamp = adjustTimestampIfInRestPeriod(utcTimestampMillis, restPeriod, localZoneId)

            val expectedAdjustedTimestamp = Instant.parse("2024-01-01T08:00:00Z").toEpochMilli() // Expected: 2 AM CST on the day of the timestamp
            assertEquals(expectedAdjustedTimestamp, adjustedTimestamp)
        }
    }

    class TestIsWithinRestPeriod {

        @Test
        fun timeWithinRestPeriod() {
            val restPeriod = RestPeriod(6, 0, 12, 0) // Rest period from 22:00 to 06:00
            val time = LocalTime.of(8, 30)

            assertTrue(isWithinRestPeriod(time.hour, time.minute, restPeriod))
        }

        @Test
        fun timeWithinRestPeriod_crossingMidnight() {
            val restPeriod = RestPeriod(22, 0, 2, 0) // Rest period from 22:00 to 02:00
            val time = LocalTime.of(0, 30) // Time after midnight

            assertTrue(isWithinRestPeriod(time.hour, time.minute, restPeriod))
        }

        @Test
        fun timeOutsideRestPeriod() {
            val restPeriod = RestPeriod(12, 0, 18, 0) // Rest period from 22:00 to 06:00
            val time = LocalTime.of(21, 0)

            assertFalse(isWithinRestPeriod(time.hour, time.minute, restPeriod))
        }

        @Test
        fun timeOutsideRestPeriod_crossingMidnight() {
            val restPeriod = RestPeriod(22, 0, 6, 0) // Rest period from 22:00 to 06:00
            val time = LocalTime.of(21, 0)

            assertFalse(isWithinRestPeriod(time.hour, time.minute, restPeriod))
        }
    }

    /**
     * `calculateOffsetDateTimeExcludingRestPeriod` walks the calendar one
     * minute at a time via `Calendar.add(Calendar.MINUTE, ±1)`. On DST
     * transition days this is the moment where bugs hide:
     *
     *  - **Spring-forward** (March, 2nd Sunday in US): 2:00 AM EST advances
     *    directly to 3:00 AM EDT — the 2:00–2:59 hour does not exist on
     *    the wall clock. `Calendar.add(MINUTE, 1)` correctly elides the
     *    missing minute (real elapsed time still increments by 1 minute).
     *  - **Fall-back** (November, 1st Sunday in US): 2:00 AM EDT becomes
     *    1:00 AM EST — the 1:00–1:59 hour repeats. `Calendar.add` walks
     *    forward in real time but the wall-clock value goes backward.
     *
     * Both behaviors must produce the SAME final timestamp the algorithm
     * would on a non-DST week — i.e. the user's "skip the rest period
     * then offset N minutes" semantics work identically across DST. If
     * `Calendar.add` were ever swapped for naive `timeInMillis += 60_000`
     * arithmetic, the fall-back day would over-skip the rest period (the
     * 1:00 hour gets visited twice → counted as rest twice → end time off
     * by an hour); the spring-forward day would symmetrically under-skip.
     *
     * These tests pin the current correct behavior so a future refactor
     * that breaks DST semantics fails loudly.
     */
    class TestDstTransitions {

        private val savedTz = TimeZone.getDefault()

        @org.junit.After fun restoreTz() { TimeZone.setDefault(savedTz) }

        /** Set both the JVM default zone AND build a Calendar in that zone. */
        private fun calAt(year: Int, month: Int, day: Int, hour: Int, minute: Int,
                          zoneId: String): Calendar {
            val tz = TimeZone.getTimeZone(zoneId)
            TimeZone.setDefault(tz)
            return Calendar.getInstance(tz).apply {
                clear()
                set(year, month, day, hour, minute, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        @Test
        fun springForwardForwardOffsetEndsAtTheCorrectWallClockTime() {
            // March 10, 2024 — US spring-forward at 2:00 AM EST → 3:00 AM EDT.
            // Start at midnight EST, rest period 22:00–06:00, offset 60 min.
            // Walk: 0:00 EST → 1:59 EST → 3:00 EDT (DST gap skipped) → 5:59 EDT
            //       → 6:00 EDT (rest ends) → 7:00 EDT (60 min offset complete).
            val start = calAt(2024, Calendar.MARCH, 10, 0, 0, "America/New_York")
            val rest = RestPeriod(22, 0, 6, 0)

            val result = calculateOffsetDateTimeExcludingRestPeriod(
                start, offsetMinutes = 60, restPeriod = rest, direction = "forward"
            )

            val expected = calAt(2024, Calendar.MARCH, 10, 7, 0, "America/New_York")
            assertEquals(
                "spring-forward day must end 60 wall-clock min after rest ends, " +
                        "even though the 2:00–2:59 hour was skipped",
                expected.timeInMillis, result.timeInMillis
            )
        }

        @Test
        fun fallBackForwardOffsetEndsAtTheCorrectWallClockTime() {
            // November 3, 2024 — US fall-back at 2:00 AM EDT → 1:00 AM EST.
            // Start at midnight EDT, rest 22:00–06:00, offset 60 min.
            // The wall clock visits the 1:00 hour twice. Both visits are in
            // the rest period, so the loop must skip both — net real time
            // until rest ends = 7 wall-clock hours = 6 + the repeated hour.
            // Ending wall clock should be 6:00 EST + 60 min = 7:00 EST.
            val start = calAt(2024, Calendar.NOVEMBER, 3, 0, 0, "America/New_York")
            val rest = RestPeriod(22, 0, 6, 0)

            val result = calculateOffsetDateTimeExcludingRestPeriod(
                start, offsetMinutes = 60, restPeriod = rest, direction = "forward"
            )

            val expected = calAt(2024, Calendar.NOVEMBER, 3, 7, 0, "America/New_York")
            assertEquals(
                "fall-back day must end at 7:00 EST despite the 1:00 hour " +
                        "repeating during rest",
                expected.timeInMillis, result.timeInMillis
            )
        }

        @Test
        fun nonDstWeekControlMatchesExpectedAlgebra() {
            // Same shape as the spring-forward test, but on a non-DST day.
            // This is the "control" — confirms the DST tests above are
            // checking against the same algebra they should match.
            val start = calAt(2024, Calendar.JULY, 14, 0, 0, "America/New_York")
            val rest = RestPeriod(22, 0, 6, 0)

            val result = calculateOffsetDateTimeExcludingRestPeriod(
                start, offsetMinutes = 60, restPeriod = rest, direction = "forward"
            )

            val expected = calAt(2024, Calendar.JULY, 14, 7, 0, "America/New_York")
            assertEquals(expected.timeInMillis, result.timeInMillis)
        }

        @Test
        fun springForwardBackwardOffsetMatchesNonDst() {
            // Symmetrical: walking BACKWARD across spring-forward.
            // Start at noon, walk back 60 minutes outside rest, plus skip
            // the rest period 22:00–06:00 of the prior day.
            val start = calAt(2024, Calendar.MARCH, 10, 12, 0, "America/New_York")
            val rest = RestPeriod(22, 0, 6, 0)

            val dstResult = calculateOffsetDateTimeExcludingRestPeriod(
                start, offsetMinutes = 60, restPeriod = rest, direction = "backward"
            )

            // Compare against the same shape on a non-DST day:
            val nonDstStart = calAt(2024, Calendar.JULY, 14, 12, 0, "America/New_York")
            val nonDstResult = calculateOffsetDateTimeExcludingRestPeriod(
                nonDstStart, offsetMinutes = 60, restPeriod = rest, direction = "backward"
            )

            // Both should land at the same wall-clock offset from start
            // (the rest period doesn't intersect the walked range, so DST
            // is the only variable).
            val dstOffset = start.timeInMillis - dstResult.timeInMillis
            val nonDstOffset = nonDstStart.timeInMillis - nonDstResult.timeInMillis
            assertEquals(
                "backward 60 min outside rest should consume identical real " +
                        "time on DST and non-DST days",
                nonDstOffset, dstOffset
            )
        }
    }
}