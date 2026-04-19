package io.keepalive.android.receivers

/**
 * Pure rule used by [AlarmReceiver] to decide the effective alarm stage
 * given the declared stage and how late the alarm fired.
 *
 * If a "final" alarm fires significantly later than scheduled, it's likely
 * stale — e.g., from an app update/redeploy, extended device-off, or process
 * death. In that case we downgrade to "periodic" so the user gets a fresh
 * "Are you there?" prompt instead of an immediate real alert.
 *
 * Threshold: delay greater than the follow-up period means the would-be
 * final-alarm fire time is now in the past.
 *
 * Pulled out of [AlarmReceiver] so the rule can be unit-tested without a
 * Context or a clock.
 */
internal fun computeEffectiveAlarmStage(
    declaredStage: String,
    delaySeconds: Long,
    followupMinutes: Int
): String {
    if (declaredStage != "final") return declaredStage
    if (delaySeconds <= 0) return declaredStage
    val maxAcceptableDelaySeconds = followupMinutes * 60L
    return if (delaySeconds > maxAcceptableDelaySeconds) "periodic" else declaredStage
}
