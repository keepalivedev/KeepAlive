package io.keepalive.android

/**
 * Validation result for the time period edit dialogs in SettingsActivity.
 */
internal enum class TimeInputResult {
    VALID,

    // below AppController.ALARM_MINIMUM_TIME_PERIOD_MINUTES
    TOO_SHORT,

    // above AppController.ALARM_MAXIMUM_TIME_PERIOD_MINUTES
    TOO_LONG,

    // the follow-up period must be a whole number of minutes because every
    // runtime consumer parses the stored string with toIntOrNull(), which
    // returns null for decimal strings and silently falls back to the
    // 60 minute default
    WHOLE_MINUTES_REQUIRED,

    // the check period is limited to 2 decimal places; more precision is
    // meaningless for alarm scheduling
    TOO_MANY_DECIMALS,

    // not parseable as a number at all
    INVALID
}

/**
 * Validate the check period ("time_period_hours") or follow-up period
 * ("followup_time_period_minutes") as entered in the edit dialog. The raw
 * string is what gets stored, so validation has to guarantee the string
 * itself is usable by every runtime consumer, not just that it parses here.
 */
internal fun validateTimePeriodInput(preferenceKey: String, value: String): TimeInputResult {
    val timeValue = value.toFloatOrNull() ?: return TimeInputResult.INVALID

    // "NaN" parses as a Float but compares false against both bounds
    if (timeValue.isNaN()) {
        return TimeInputResult.INVALID
    }

    val minutes = if (preferenceKey == PrefKeys.TIME_PERIOD_HOURS) {

        // '12.345' -> '345', no '.' -> ''
        if (value.substringAfter('.', "").length > 2) {
            return TimeInputResult.TOO_MANY_DECIMALS
        }
        timeValue * 60
    } else {
        if (value.toIntOrNull() == null) {
            return TimeInputResult.WHOLE_MINUTES_REQUIRED
        }
        timeValue
    }

    return when {
        minutes < AppController.ALARM_MINIMUM_TIME_PERIOD_MINUTES -> TimeInputResult.TOO_SHORT
        minutes > AppController.ALARM_MAXIMUM_TIME_PERIOD_MINUTES -> TimeInputResult.TOO_LONG
        else -> TimeInputResult.VALID
    }
}
