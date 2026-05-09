package io.keepalive.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure unit tests for the AlertService dedup rule.
 *
 * The alert intent carries a trigger timestamp; the service persists which
 * steps have completed for that trigger. This rule decides what to do with
 * each incoming intent — critical because the OS redelivers intents under
 * START_REDELIVER_INTENT, and we must not duplicate-send or drop a
 * legitimately newer alert.
 */
class AlertIntentActionTest {

    /** Matches AlertService.ALL_STEPS_COMPLETE — SMS|CALL|LOCATION|WEBHOOK = 1|2|4|8 = 15. */
    private val ALL = 1 or 2 or 4 or 8

    @Test fun `empty state accepts a new trigger`() {
        val action = decideAlertIntentAction(
            triggerTimestamp = 1_000L,
            savedTrigger = 0L,
            savedSteps = 0,
            allStepsCompleteMask = ALL
        )
        assertEquals(AlertIntentAction.NewAlert, action)
    }

    @Test fun `newer trigger replaces an older one`() {
        val action = decideAlertIntentAction(
            triggerTimestamp = 2_000L,
            savedTrigger = 1_000L,
            savedSteps = ALL,  // older alert fully done
            allStepsCompleteMask = ALL
        )
        assertEquals(AlertIntentAction.NewAlert, action)
    }

    @Test fun `same trigger with all steps complete is a duplicate and is skipped`() {
        val action = decideAlertIntentAction(
            triggerTimestamp = 1_000L,
            savedTrigger = 1_000L,
            savedSteps = ALL,
            allStepsCompleteMask = ALL
        )
        assertEquals(AlertIntentAction.Skip, action)
    }

    @Test fun `same trigger with some steps missing is resumed`() {
        val action = decideAlertIntentAction(
            triggerTimestamp = 1_000L,
            savedTrigger = 1_000L,
            savedSteps = 1 or 2,  // SMS + call done, location/webhook missing
            allStepsCompleteMask = ALL
        )
        assertEquals(AlertIntentAction.Resume, action)
    }

    @Test fun `same trigger with no steps done yet is resumed`() {
        // The tracker was initialized but nothing completed before the process
        // died. OS redelivers — we should continue, not restart as "new".
        val action = decideAlertIntentAction(
            triggerTimestamp = 1_000L,
            savedTrigger = 1_000L,
            savedSteps = 0,
            allStepsCompleteMask = ALL
        )
        assertEquals(AlertIntentAction.Resume, action)
    }

    @Test fun `stale trigger is skipped when a newer one was already started`() {
        val action = decideAlertIntentAction(
            triggerTimestamp = 500L,
            savedTrigger = 1_000L,
            savedSteps = 0,
            allStepsCompleteMask = ALL
        )
        assertEquals(AlertIntentAction.Skip, action)
    }

    @Test fun `zero or negative trigger falls through to new alert`() {
        // Defensive: shouldn't happen in practice — callers guard with triggerTimestamp > 0.
        assertEquals(AlertIntentAction.NewAlert,
            decideAlertIntentAction(0L, 1_000L, ALL, ALL))
        assertEquals(AlertIntentAction.NewAlert,
            decideAlertIntentAction(-1L, 1_000L, ALL, ALL))
    }

    @Test fun `partial completion with non-default mask is resumed not skipped`() {
        // Regression guard: if ALL_STEPS_COMPLETE ever changes, make sure the
        // "complete" test is `savedSteps AND mask == mask`, not savedSteps == mask.
        val newMask = 1 or 2 or 4 or 8 or 16  // imagine a 5th step was added
        val action = decideAlertIntentAction(
            triggerTimestamp = 1_000L,
            savedTrigger = 1_000L,
            savedSteps = 1 or 2 or 4 or 8,  // old ALL without the new bit
            allStepsCompleteMask = newMask
        )
        assertEquals(AlertIntentAction.Resume, action)
    }
}
