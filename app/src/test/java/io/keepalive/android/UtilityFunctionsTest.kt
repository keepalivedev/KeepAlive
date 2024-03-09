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

            val result = calculatePastDateTimeExcludingRestPeriod(targetDateTime, checkPeriodHours, restPeriod)
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

            val result = calculatePastDateTimeExcludingRestPeriod(targetDateTime, checkPeriodHours, restPeriod)

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

            val result = calculatePastDateTimeExcludingRestPeriod(targetDateTime, checkPeriodHours, restPeriod)
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

            val result = calculatePastDateTimeExcludingRestPeriod(targetDateTime, checkPeriodHours, restPeriod)
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

            val result = calculatePastDateTimeExcludingRestPeriod(targetDateTime, checkPeriodHours, restPeriod)
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
}