package io.keepalive.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [validateTimePeriodInput] against the failure modes from issue #184:
 * unbounded values silently saturate the Int minute math (alarm scheduled
 * millennia out while the UI reports monitoring active), and decimal
 * follow-up minutes pass a Float-based validation but are read at runtime
 * with toIntOrNull(), silently falling back to the 60 minute default.
 *
 * Plain JUnit — the validator is a pure function, no Robolectric needed.
 */
class TimeInputValidationTest {

    private val HOURS = PrefKeys.TIME_PERIOD_HOURS
    private val MINUTES = PrefKeys.FOLLOWUP_TIME_PERIOD_MINUTES

    // --- check period (hours) ---

    @Test fun `typical hours value is valid`() {
        assertEquals(TimeInputResult.VALID, validateTimePeriodInput(HOURS, "12"))
    }

    @Test fun `hours may use decimals for short testing periods`() {
        // 0.21 h = 12.6 min — the debug preset in DebugFunctions.kt
        assertEquals(TimeInputResult.VALID, validateTimePeriodInput(HOURS, "0.21"))
    }

    @Test fun `hours below the 10 minute floor are too short`() {
        assertEquals(TimeInputResult.TOO_SHORT, validateTimePeriodInput(HOURS, "0.1"))
    }

    @Test fun `hours at the 7 day ceiling are valid`() {
        // 168 h * 60 = 10080 min, exactly the maximum
        assertEquals(TimeInputResult.VALID, validateTimePeriodInput(HOURS, "168"))
    }

    @Test fun `hours above the 7 day ceiling are too long`() {
        assertEquals(TimeInputResult.TOO_LONG, validateTimePeriodInput(HOURS, "169"))
    }

    @Test fun `the huge value from issue 184 is rejected`() {
        // (hours * 60).toInt() saturates at Int.MAX_VALUE for this input —
        // the alarm would be scheduled ~4,083 years out and never fire
        val result = validateTimePeriodInput(HOURS, "123345467890.12345667890")
        assertEquals(TimeInputResult.TOO_MANY_DECIMALS, result)
    }

    @Test fun `a huge whole hours value is too long`() {
        assertEquals(TimeInputResult.TOO_LONG, validateTimePeriodInput(HOURS, "123345467890"))
    }

    @Test fun `hours with two decimal places are valid`() {
        assertEquals(TimeInputResult.VALID, validateTimePeriodInput(HOURS, "12.34"))
    }

    @Test fun `hours with three decimal places are too precise`() {
        assertEquals(TimeInputResult.TOO_MANY_DECIMALS, validateTimePeriodInput(HOURS, "12.345"))
    }

    // --- follow-up period (minutes) ---

    @Test fun `typical minutes value is valid`() {
        assertEquals(TimeInputResult.VALID, validateTimePeriodInput(MINUTES, "60"))
    }

    @Test fun `minutes below the floor are too short`() {
        assertEquals(TimeInputResult.TOO_SHORT, validateTimePeriodInput(MINUTES, "9"))
    }

    @Test fun `minutes at the floor are valid`() {
        assertEquals(TimeInputResult.VALID, validateTimePeriodInput(MINUTES, "10"))
    }

    @Test fun `minutes at the 7 day ceiling are valid`() {
        assertEquals(TimeInputResult.VALID, validateTimePeriodInput(MINUTES, "10080"))
    }

    @Test fun `minutes above the 7 day ceiling are too long`() {
        assertEquals(TimeInputResult.TOO_LONG, validateTimePeriodInput(MINUTES, "10081"))
    }

    @Test fun `decimal minutes are rejected as non-whole`() {
        // "90.5" parses as a Float but every runtime consumer uses
        // toIntOrNull(), which would silently turn this into 60
        assertEquals(TimeInputResult.WHOLE_MINUTES_REQUIRED, validateTimePeriodInput(MINUTES, "90.5"))
    }

    @Test fun `minutes with a decimal point and trailing zero are rejected`() {
        // toIntOrNull("12.0") is null too — the string is what gets stored
        assertEquals(TimeInputResult.WHOLE_MINUTES_REQUIRED, validateTimePeriodInput(MINUTES, "12.0"))
    }

    // --- unparseable input ---

    @Test fun `empty input is invalid`() {
        assertEquals(TimeInputResult.INVALID, validateTimePeriodInput(HOURS, ""))
        assertEquals(TimeInputResult.INVALID, validateTimePeriodInput(MINUTES, ""))
    }

    @Test fun `a lone decimal point is invalid`() {
        assertEquals(TimeInputResult.INVALID, validateTimePeriodInput(HOURS, "."))
    }

    @Test fun `non-numeric input is invalid`() {
        // can't be typed with the numeric keyboard, but can be pasted
        assertEquals(TimeInputResult.INVALID, validateTimePeriodInput(HOURS, "abc"))
    }

    @Test fun `NaN is invalid rather than passing both bound checks`() {
        // "NaN".toFloatOrNull() parses, and NaN compares false against
        // both the minimum and maximum
        assertEquals(TimeInputResult.INVALID, validateTimePeriodInput(HOURS, "NaN"))
    }
}
