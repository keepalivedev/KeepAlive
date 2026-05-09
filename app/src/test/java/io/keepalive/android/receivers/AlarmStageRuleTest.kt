package io.keepalive.android.receivers

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure unit tests for the stale-final downgrade rule. */
class AlarmStageRuleTest {

    @Test fun `periodic stays periodic regardless of delay`() {
        assertEquals("periodic",
            computeEffectiveAlarmStage("periodic", delaySeconds = 10_000_000L, followupMinutes = 60))
    }

    @Test fun `final with no delay stays final`() {
        assertEquals("final",
            computeEffectiveAlarmStage("final", delaySeconds = 0L, followupMinutes = 60))
    }

    @Test fun `final with negative delay stays final`() {
        // delaySeconds <= 0 means the alarm fired early or at time — shouldn't happen
        // on real AlarmManager but defensively keep the stage.
        assertEquals("final",
            computeEffectiveAlarmStage("final", delaySeconds = -5L, followupMinutes = 60))
    }

    @Test fun `final with delay less than followup stays final`() {
        // 30 min delay, 60 min followup → still final
        assertEquals("final",
            computeEffectiveAlarmStage("final", delaySeconds = 30 * 60L, followupMinutes = 60))
    }

    @Test fun `final with delay exactly equal to followup stays final`() {
        // Boundary: equals is NOT greater, so still final.
        assertEquals("final",
            computeEffectiveAlarmStage("final", delaySeconds = 60 * 60L, followupMinutes = 60))
    }

    @Test fun `final with delay one second past followup downgrades to periodic`() {
        assertEquals("periodic",
            computeEffectiveAlarmStage("final", delaySeconds = 60 * 60L + 1, followupMinutes = 60))
    }

    @Test fun `final with very long delay downgrades to periodic`() {
        // e.g. device-off for a day, app update, etc.
        assertEquals("periodic",
            computeEffectiveAlarmStage("final", delaySeconds = 24 * 60 * 60L, followupMinutes = 60))
    }

    @Test fun `rule respects configured followup period`() {
        // With a 15-minute followup window, 20-minute delay is stale.
        assertEquals("periodic",
            computeEffectiveAlarmStage("final", delaySeconds = 20 * 60L, followupMinutes = 15))
        // With a 120-minute followup window, 20-minute delay is fine.
        assertEquals("final",
            computeEffectiveAlarmStage("final", delaySeconds = 20 * 60L, followupMinutes = 120))
    }
}
