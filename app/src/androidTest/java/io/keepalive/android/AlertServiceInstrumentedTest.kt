package io.keepalive.android

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.keepalive.android.AlertFlowTestUtil.hasNotification
import io.keepalive.android.AlertFlowTestUtil.resetToCleanEnabledState
import io.keepalive.android.AlertFlowTestUtil.savedAlertTriggerTimestamp
import io.keepalive.android.AlertFlowTestUtil.targetContext
import io.keepalive.android.AlertFlowTestUtil.waitUntil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage of [AlertService] — dedup guard, foreground lifecycle,
 * step tracking, and teardown on real Android rather than Robolectric.
 *
 * Important: these tests don't assert SMS delivery. They assert that the
 * service progressed through the step tracker (SMS bit set, CALL bit set)
 * and eventually stops. On a device with a SIM the SMS attempt to the fake
 * 555-01xx contact fails at the network layer, which is fine — the step bit
 * still flips because we mark-complete after the dispatch call, not after
 * delivery confirmation.
 */
@RunWith(AndroidJUnit4::class)
class AlertServiceInstrumentedTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun grantAllPermissions() {
            TestSetupUtil.setupTestEnvironment()
        }
    }

    @Before fun setUp() {
        // Ensure any AlertService from a prior test has fully stopped before
        // we reset state — otherwise its tail-end writes can race with ours.
        targetContext.stopService(Intent(targetContext, AlertService::class.java))
        Thread.sleep(500)

        resetToCleanEnabledState()
        // Also clear step tracker from any prior run
        getEncryptedSharedPreferences(targetContext).edit()
            .putLong("AlertTriggerTimestamp", 0L)
            .putInt("AlertStepsCompleted", 0)
            .putLong("LastAlertAt", 0L)
            .commit()
    }

    @After fun tearDown() {
        AlertFlowTestUtil.cancelAllNotifications()
        // Stop the service if still running
        targetContext.stopService(Intent(targetContext, AlertService::class.java))
        Thread.sleep(200)
    }

    private fun startAlertService(triggerTimestamp: Long = System.currentTimeMillis()) {
        val intent = Intent(targetContext, AlertService::class.java).apply {
            putExtra(AlertService.EXTRA_ALERT_TRIGGER_TIMESTAMP, triggerTimestamp)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            targetContext.startForegroundService(intent)
        } else {
            targetContext.startService(intent)
        }
    }

    @Test fun serviceRunsAllStepsOnNewTrigger() {
        val trigger = System.currentTimeMillis()
        startAlertService(trigger)

        // Service registers its trigger in prefs before any step runs
        assertTrue("trigger persisted",
            waitUntil { savedAlertTriggerTimestamp() == trigger })

        // All four steps should complete (even if SMS delivery fails at the
        // network layer — we mark after dispatch, not after delivery).
        assertTrue("SMS step", waitUntil { AlertFlowTestUtil.isAlertStepComplete(STEP_SMS_SENT) })
        assertTrue("CALL step", waitUntil { AlertFlowTestUtil.isAlertStepComplete(STEP_CALL_MADE) })
        assertTrue("LOCATION step (skipped since location_enabled=false)",
            waitUntil { AlertFlowTestUtil.isAlertStepComplete(STEP_LOCATION_DONE) })
        assertTrue("WEBHOOK step (skipped since webhook_enabled=false)",
            waitUntil { AlertFlowTestUtil.isAlertStepComplete(STEP_WEBHOOK_DONE) })

        // LastAlertAt should be recent
        val lastAt = getEncryptedSharedPreferences(targetContext).getLong("LastAlertAt", 0)
        assertTrue("LastAlertAt should be recent", lastAt >= trigger)
    }

    @Test fun duplicateTriggerDoesNotRerunSteps() {
        val trigger = System.currentTimeMillis()
        startAlertService(trigger)
        assertTrue("first run completes",
            waitUntil { AlertFlowTestUtil.isAlertStepComplete(STEP_CALL_MADE) })
        // LastAlertAt is written after the CALL step; wait for it to land.
        assertTrue("first run should write LastAlertAt",
            waitUntil { getEncryptedSharedPreferences(targetContext).getLong("LastAlertAt", 0) > 0 })

        val firstLastAt = getEncryptedSharedPreferences(targetContext).getLong("LastAlertAt", 0)
        Thread.sleep(50)

        // Send the same trigger again — dedup guard should bail.
        startAlertService(trigger)
        Thread.sleep(1500) // let service boot and bail

        val secondLastAt = getEncryptedSharedPreferences(targetContext).getLong("LastAlertAt", 0)
        assertEquals("LastAlertAt should NOT be updated by a duplicate trigger",
            firstLastAt, secondLastAt)
    }

    @Test fun newerTriggerOverwritesTheSavedTrigger() {
        val firstTrigger = System.currentTimeMillis()
        startAlertService(firstTrigger)
        assertTrue(waitUntil { AlertFlowTestUtil.isAlertStepComplete(STEP_SMS_SENT) })

        val newerTrigger = firstTrigger + 1000
        startAlertService(newerTrigger)
        assertTrue("newer trigger must replace the saved one",
            waitUntil { savedAlertTriggerTimestamp() == newerTrigger })
    }

    @Test fun staleTriggerIsIgnored() {
        val newer = System.currentTimeMillis()
        startAlertService(newer)
        assertTrue(waitUntil { savedAlertTriggerTimestamp() == newer })

        val older = newer - 5000
        val savedBefore = savedAlertTriggerTimestamp()
        startAlertService(older)
        Thread.sleep(1000)

        assertEquals("stale trigger must not overwrite a newer saved trigger",
            savedBefore, savedAlertTriggerTimestamp())
    }

    @Test fun serviceEventuallyStopsItself() {
        startAlertService()
        assertTrue(waitUntil { AlertFlowTestUtil.isAlertStepComplete(STEP_CALL_MADE) })

        // AlertService stops itself after the sync steps complete (no async
        // location in this config). Notification should be cleared within a
        // few seconds.
        assertTrue("AlertService foreground notification should be cleared after completion",
            waitUntil(timeoutMs = 10_000L) {
                !hasNotification(AppController.ALERT_SERVICE_NOTIFICATION_ID)
            })
    }

    @Test fun serviceRunsWithoutCrashingWhenContactsAreBlank() {
        // Edge case: the user enables the app but hasn't configured a contact.
        getEncryptedSharedPreferences(targetContext).edit()
            .putString("PHONE_NUMBER_SETTINGS", "[]")
            .putString("contact_phone", "")
            .commit()

        startAlertService()
        Thread.sleep(2000)

        // Steps still mark complete — we don't hang when there's nothing to send.
        assertTrue(AlertFlowTestUtil.isAlertStepComplete(STEP_SMS_SENT))
        assertTrue(AlertFlowTestUtil.isAlertStepComplete(STEP_CALL_MADE))
    }
}
