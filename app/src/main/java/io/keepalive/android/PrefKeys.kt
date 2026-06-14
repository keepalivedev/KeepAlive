package io.keepalive.android

/**
 * SharedPreferences key constants. These are the on-disk key names — they must
 * never change or existing users would lose their settings on upgrade.
 *
 * Unit tests intentionally keep using the literal strings so a test fails if a
 * key name is ever changed here by accident.
 */
object PrefKeys {

    // core monitoring settings
    const val ENABLED = "enabled"
    const val TIME_PERIOD_HOURS = "time_period_hours"
    const val FOLLOWUP_TIME_PERIOD_MINUTES = "followup_time_period_minutes"
    const val USE_EXACT_ALARMS = "use_exact_alarms"
    const val AUTO_RESTART_MONITORING = "auto_restart_monitoring"
    const val ARE_YOU_THERE_OVERLAY_ENABLED = "are_you_there_overlay_enabled"
    const val REST_PERIODS = "REST_PERIODS"
    const val APPS_TO_MONITOR = "APPS_TO_MONITOR"

    // alert contacts
    const val PHONE_NUMBER_SETTINGS = "PHONE_NUMBER_SETTINGS"
    const val CONTACT_PHONE = "contact_phone"
    const val LOCATION_ENABLED = "location_enabled"

    // test alert options
    const val TEST_ALERT_SEND_WARNING = "test_alert_send_warning"
    const val TEST_ALERT_WARNING_SMS_MESSAGE = "test_alert_warning_sms_message"

    // webhook settings
    const val WEBHOOK_ENABLED = "webhook_enabled"
    const val WEBHOOK_URL = "webhook_url"
    const val WEBHOOK_METHOD = "webhook_method"
    const val WEBHOOK_INCLUDE_LOCATION = "webhook_include_location"
    const val WEBHOOK_LOCATION_ENABLED = "webhook_location_enabled"
    const val WEBHOOK_TIMEOUT = "webhook_timeout"
    const val WEBHOOK_RETRIES = "webhook_retries"
    const val WEBHOOK_VERIFY_CERTIFICATE = "webhook_verify_certificate"
    const val WEBHOOK_HEADERS = "webhook_headers"

    // runtime state written by the alert pipeline
    const val NEXT_ALARM_TIMESTAMP = "NextAlarmTimestamp"
    const val LAST_ALERT_AT = "LastAlertAt"

    // runtime state in device-protected storage (Direct Boot)
    const val LAST_ALARM_STAGE = "last_alarm_stage"
    const val LAST_ACTIVITY_TIMESTAMP = "last_activity_timestamp"
    const val LAST_CHECK_TIMESTAMP = "last_check_timestamp"
    const val DIRECT_BOOT_NOTIFICATION_PENDING = "direct_boot_notification_pending"

    // UI preferences
    const val LOG_DISPLAY_TEXT_SIZE = "log_display_text_size"
}
