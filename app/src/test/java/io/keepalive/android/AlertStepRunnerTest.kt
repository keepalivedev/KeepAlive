package io.keepalive.android

import io.keepalive.android.testing.FakeAlertDispatcher
import io.keepalive.android.testing.FakeAlertStepOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the alert-dispatch sequencer.
 *
 * Correctness concerns this guards:
 *  1. **Step ordering** — SMS must complete before call, call must complete
 *     before the async location step kicks off. This was a real bug (review
 *     item #1) where the pre-fix code ran the call AFTER firing the async
 *     location helper; a fast callback could stop the service mid-flight.
 *  2. **Idempotency** — with START_REDELIVER_INTENT, a redelivered intent
 *     may re-enter sendAlert; completed steps must not re-execute.
 *  3. **stopService ownership** — the sync path stops the service at the
 *     end; the async-location path defers to the callback. Double-stops
 *     must not happen from the main flow.
 */
class AlertStepRunnerTest {

    private fun sampleLocation() = LocationResult(
        latitude = 40.0,
        longitude = -70.0,
        accuracy = 5f,
        geocodedAddress = "1 Infinite Loop",
        formattedLocationString = "Lat 40 Lon -70"
    )

    // ========================================================================
    // Step ordering
    // ========================================================================

    @Test fun `no-location path runs cancel, dismiss, SMS, call, writeLastAlertAt, stop in order`() {
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(isUserUnlocked = true, locationNeeded = false)

        val asyncPending = runAlertSteps(steps, disp)

        assertFalse("sync path: no async work pending", asyncPending)
        assertEquals(
            listOf(
                "cancelNotification",
                "dismissOverlay",
                "sendSms",
                "makeCall",
                "writeLastAlertAt",
                "stopService"
            ),
            disp.callLog
        )
    }

    @Test fun `call step happens BEFORE async location request`() {
        // The critical ordering guarantee. If this fails, the pre-fix race
        // is back and a cached location fix could stop the service before
        // the phone call dials.
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(
            isUserUnlocked = true,
            locationNeeded = true,
            webhookEnabled = false,
            // Don't auto-fire the callback so we can inspect the state frozen
            // at "sendAlert has kicked off location but callback hasn't run".
        ).apply {
            locationResultToDeliver = null
        }

        runAlertSteps(steps, disp)

        // At the point requestLocationAsync was called, SMS and call must
        // already have completed.
        val smsIdx = disp.callLog.indexOf("sendSms")
        val callIdx = disp.callLog.indexOf("makeCall")
        val reqIdx = disp.callLog.indexOf("requestLocationAsync")
        assertTrue("sms must come first", smsIdx in 0 until callIdx)
        assertTrue("call must come before location request", callIdx < reqIdx)
    }

    @Test fun `does not dismiss overlay when user is locked (Direct Boot)`() {
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(isUserUnlocked = false)

        runAlertSteps(steps, disp)

        assertEquals(1, disp.cancelNotificationCount)
        assertEquals("overlay service isn't directBootAware — must not try to dismiss",
            0, disp.dismissOverlayCount)
    }

    @Test fun `writeLastAlertAt uses the dispatcher clock`() {
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(nowValue = 12345L)

        runAlertSteps(steps, disp)

        assertEquals(12345L, disp.lastAlertAtWritten)
    }

    // ========================================================================
    // Async location path
    // ========================================================================

    @Test fun `returns true when async location is pending`() {
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(
            isUserUnlocked = true,
            locationNeeded = true
        ).apply {
            locationResultToDeliver = null  // don't fire the callback
        }

        val asyncPending = runAlertSteps(steps, disp)

        assertTrue("async work is pending", asyncPending)
        assertEquals("main flow must NOT call stopService — callback owns that",
            0, disp.stopServiceCount)
    }

    @Test fun `callback completes location SMS, webhook, and stops the service`() {
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(
            isUserUnlocked = true,
            locationNeeded = true,
            webhookEnabled = true
        ).apply {
            locationResultToDeliver = sampleLocation()  // auto-fire
        }

        runAlertSteps(steps, disp)

        assertEquals(listOf("Lat 40 Lon -70"), disp.locationSmsSends)
        assertEquals(1, disp.webhookSends.size)
        assertEquals(sampleLocation(), disp.webhookSends[0])
        assertEquals(1, disp.stopServiceCount)
        assertTrue(steps.isComplete(STEP_LOCATION_DONE))
        assertTrue(steps.isComplete(STEP_WEBHOOK_DONE))
    }

    @Test fun `location-only mode (no webhook) still marks webhook done to unblock redelivery`() {
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(
            isUserUnlocked = true,
            locationNeeded = true,
            webhookEnabled = false
        ).apply {
            locationResultToDeliver = sampleLocation()
        }

        runAlertSteps(steps, disp)

        assertEquals(1, disp.locationSmsSends.size)
        assertEquals("no webhook calls expected", 0, disp.webhookSends.size)
        assertTrue(steps.isComplete(STEP_WEBHOOK_DONE))
    }

    @Test fun `location request exception falls back to sync stop with both steps marked`() {
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(
            isUserUnlocked = true,
            locationNeeded = true,
            webhookEnabled = true
        ).apply {
            locationRequestThrows = RuntimeException("location provider died")
        }

        val asyncPending = runAlertSteps(steps, disp)

        assertFalse("exception should fall through to sync stop", asyncPending)
        assertTrue(steps.isComplete(STEP_LOCATION_DONE))
        assertTrue(steps.isComplete(STEP_WEBHOOK_DONE))
        assertEquals(1, disp.stopServiceCount)
    }

    // ========================================================================
    // Webhook-only (no location) path
    // ========================================================================

    @Test fun `webhook only (no location) sends webhook with null location synchronously`() {
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(
            isUserUnlocked = true,
            locationNeeded = false,
            webhookEnabled = true
        )

        val asyncPending = runAlertSteps(steps, disp)

        assertFalse(asyncPending)
        assertEquals(1, disp.webhookSends.size)
        assertNull("webhook fires without location when location feature is off",
            disp.webhookSends[0])
        assertTrue(steps.isComplete(STEP_LOCATION_DONE))
        assertTrue(steps.isComplete(STEP_WEBHOOK_DONE))
        assertEquals(1, disp.stopServiceCount)
    }

    // ========================================================================
    // Idempotency / resume
    // ========================================================================

    @Test fun `SMS step is skipped if already complete`() {
        val steps = FakeAlertStepOps(initial = STEP_SMS_SENT)
        val disp = FakeAlertDispatcher()

        runAlertSteps(steps, disp)

        assertEquals("SMS must not be re-sent", 0, disp.smsSendCount)
        assertEquals(1, disp.callCount)
    }

    @Test fun `call step is skipped if already complete`() {
        val steps = FakeAlertStepOps(initial = STEP_CALL_MADE)
        val disp = FakeAlertDispatcher()

        runAlertSteps(steps, disp)

        assertEquals(1, disp.smsSendCount)
        assertEquals("call must not be re-placed", 0, disp.callCount)
    }

    @Test fun `fully completed cycle still runs cancel, dismiss, writeLastAlertAt, stop`() {
        // START_REDELIVER_INTENT dedup is handled by AlertService.onStartCommand
        // (AlertIntentAction), not by this sequencer. But if somehow all steps
        // are marked already, we should still cleanly finish — the steps no-op,
        // writeLastAlertAt updates timestamp, stopService runs.
        val steps = FakeAlertStepOps(initial = ALL_STEPS_COMPLETE)
        val disp = FakeAlertDispatcher(isUserUnlocked = true)

        runAlertSteps(steps, disp)

        assertEquals(0, disp.smsSendCount)
        assertEquals(0, disp.callCount)
        assertEquals(0, disp.webhookSends.size)
        assertEquals(1, disp.cancelNotificationCount)
        assertEquals(1, disp.dismissOverlayCount)
        assertEquals(1, disp.stopServiceCount)
    }

    @Test fun `resume after SMS sent but before call also skips location if already complete`() {
        // Worst-case resume: SMS went through, process died before call.
        // On redelivery we send only call + location + webhook.
        val steps = FakeAlertStepOps(initial = STEP_SMS_SENT or STEP_LOCATION_DONE or STEP_WEBHOOK_DONE)
        val disp = FakeAlertDispatcher(
            isUserUnlocked = true,
            locationNeeded = true,
            webhookEnabled = true
        ).apply {
            locationResultToDeliver = sampleLocation()
        }

        val asyncPending = runAlertSteps(steps, disp)

        assertEquals("SMS skipped", 0, disp.smsSendCount)
        assertEquals("call runs", 1, disp.callCount)
        // Location and webhook already marked done — async flow should short-circuit
        // back to sync stop.
        assertFalse("no async work since location+webhook already done", asyncPending)
        assertEquals(0, disp.locationSmsSends.size)
        assertEquals(0, disp.webhookSends.size)
        assertEquals(1, disp.stopServiceCount)
    }

    // ========================================================================
    // Callback thread-of-execution
    // ========================================================================

    @Test fun `async callback fired later still completes correctly`() {
        // Simulate a slow location provider: kick off sendAlert, then fire
        // the callback after main flow has returned.
        val steps = FakeAlertStepOps()
        val disp = FakeAlertDispatcher(
            isUserUnlocked = true,
            locationNeeded = true,
            webhookEnabled = true
        ).apply {
            locationResultToDeliver = null
        }

        val asyncPending = runAlertSteps(steps, disp)
        assertTrue(asyncPending)
        // stopService NOT yet called
        assertEquals(0, disp.stopServiceCount)

        // Fire the callback that was captured
        disp.pendingLocationCallback!!(sampleLocation())

        assertTrue(steps.isComplete(STEP_LOCATION_DONE))
        assertTrue(steps.isComplete(STEP_WEBHOOK_DONE))
        assertEquals(1, disp.stopServiceCount)
    }
}
