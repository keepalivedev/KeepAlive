package io.keepalive.android

/**
 * Outcome of the idempotent dedup guard in [AlertService.onStartCommand].
 *
 * Pulled out as a pure function so it can be unit-tested without spinning up
 * a Service or Robolectric. The mapping:
 *
 *  - New trigger (or fresh state)            → [NewAlert]
 *  - Same trigger, all steps done            → [Skip]
 *  - Same trigger, some steps still pending  → [Resume]
 *  - Older trigger than saved                → [Skip]
 */
sealed class AlertIntentAction {
    /** Brand-new alert cycle: initialize the step tracker and run all steps. */
    object NewAlert : AlertIntentAction()

    /** Same trigger as a partially-completed cycle: re-run only the pending steps. */
    object Resume : AlertIntentAction()

    /** Duplicate/redelivery/stale — do nothing, stop the service. */
    object Skip : AlertIntentAction()
}

/**
 * Decide what to do with an incoming alert intent given the persisted
 * step-tracker state.
 *
 * @param triggerTimestamp   timestamp on the incoming intent
 * @param savedTrigger       the last trigger timestamp persisted (0 if none)
 * @param savedSteps         the last persisted completed-step bitmask
 * @param allStepsCompleteMask bitmask value meaning "every step is done"
 */
internal fun decideAlertIntentAction(
    triggerTimestamp: Long,
    savedTrigger: Long,
    savedSteps: Int,
    allStepsCompleteMask: Int
): AlertIntentAction {
    // No trigger on the intent — treat as a fresh alert. (Callers typically
    // guard against this earlier, but the fallback matches the pre-refactor
    // behavior of reusing the existing tracker.)
    if (triggerTimestamp <= 0L) return AlertIntentAction.NewAlert

    return when {
        triggerTimestamp == savedTrigger &&
                (savedSteps and allStepsCompleteMask) == allStepsCompleteMask ->
            AlertIntentAction.Skip

        triggerTimestamp < savedTrigger ->
            AlertIntentAction.Skip

        triggerTimestamp == savedTrigger ->
            AlertIntentAction.Resume

        else -> AlertIntentAction.NewAlert
    }
}
