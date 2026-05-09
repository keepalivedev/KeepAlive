package io.keepalive.android

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the contract of [ProductionAlertCheckDeps.dispatchFinalAlert] —
 * specifically the try/catch added around `startForegroundService`. The
 * concern: a `BackgroundServiceStartNotAllowedException` (API 31+) or
 * `ForegroundServiceStartNotAllowedException` from a non-exempt context
 * MUST NOT prevent the rest of dispatchFinalAlert from running. The
 * post-startForegroundService work writes `last_alarm_stage = "periodic"`
 * (so a Direct-Boot reboot after the alert doesn't replay) and optionally
 * re-schedules the periodic alarm — both of which the AlarmReceiver's
 * outer catch-all would silently swallow if the service-start exception
 * propagated.
 *
 * Setup: wrap the Robolectric Application with a Context that throws on
 * any startForegroundService / startService call. Then drive
 * dispatchFinalAlert and assert on the side effects that come AFTER it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34, 35, 36])  // device-protected prefs gate is N+; matrix matches other tests
class DispatchFinalAlertTest {

    private val realCtx: Context = ApplicationProvider.getApplicationContext()

    /**
     * Wrapping context whose start{Foreground,}Service throws like the OS
     * does on API 31+ for a non-exempt background start. Production code's
     * try/catch around the call must catch this.
     */
    private class ServiceStartFailing(base: Context) : ContextWrapper(base) {
        var startForegroundServiceCalls = 0
            private set
        var startServiceCalls = 0
            private set

        override fun startForegroundService(service: Intent): android.content.ComponentName? {
            startForegroundServiceCalls++
            // Mimics ForegroundServiceStartNotAllowedException without
            // depending on the @hide class directly.
            throw IllegalStateException(
                "Test injection: ForegroundServiceStartNotAllowedException"
            )
        }

        override fun startService(service: Intent): android.content.ComponentName? {
            startServiceCalls++
            throw IllegalStateException("Test injection: BackgroundServiceStartNotAllowedException")
        }
    }

    @Before fun setUp() {
        // Clean slate for both stores.
        getEncryptedSharedPreferences(realCtx).edit().clear().commit()
        try { getDeviceProtectedPreferences(realCtx).edit().clear().commit() } catch (_: Exception) {}
    }

    @After fun tearDown() {
        getEncryptedSharedPreferences(realCtx).edit().clear().commit()
        try { getDeviceProtectedPreferences(realCtx).edit().clear().commit() } catch (_: Exception) {}
    }

    private fun newDeps(throwingCtx: ServiceStartFailing) = ProductionAlertCheckDeps(throwingCtx)

    @Test fun `service-start exception does not propagate`() {
        // The outer AlarmReceiver wraps everything in try/catch and would
        // swallow an exception silently — but that means it would also
        // skip the post-dispatch bookkeeping. Asserting the dispatch returns
        // normally proves the catch is engaged at the right level.
        val ctx = ServiceStartFailing(realCtx as Application)
        val deps = newDeps(ctx)
        val prefs = getEncryptedSharedPreferences(realCtx)

        deps.dispatchFinalAlert(
            prefs = prefs,
            nowTimestamp = System.currentTimeMillis(),
            checkPeriodHours = 12f,
            restPeriods = mutableListOf()
        )

        assertEquals("startForegroundService must have been attempted exactly once",
            1, ctx.startForegroundServiceCalls)
        // No assertion failure thrown to here = exception was caught.
    }

    @Test fun `last_alarm_stage is reset to periodic even when service-start throws`() {
        // This is the assertion the instrumented test (AlarmFlowInstrumentedTest
        // .finalAlarmWithNoActivityStartsAlertService) relies on. If a future
        // refactor moves the device-protected write INSIDE the try block, the
        // instrumented test would still pass on machines where the FG service
        // start happens to succeed, but silently regress on machines (or APIs)
        // where it throws. This unit test pins the order so the regression
        // would surface here regardless of environment.
        val devPrefs = getDeviceProtectedPreferences(realCtx)
        devPrefs.edit().putString("last_alarm_stage", "final").commit()

        val ctx = ServiceStartFailing(realCtx as Application)
        newDeps(ctx).dispatchFinalAlert(
            prefs = getEncryptedSharedPreferences(realCtx),
            nowTimestamp = System.currentTimeMillis(),
            checkPeriodHours = 12f,
            restPeriods = mutableListOf()
        )

        assertEquals("last_alarm_stage must be reset to 'periodic' even when " +
                "startForegroundService throws",
            "periodic", devPrefs.getString("last_alarm_stage", null))
    }

    @Test fun `auto_restart_monitoring still re-schedules when service-start throws`() {
        // Same concern as the alarm-stage test: the post-catch reschedule
        // must run regardless of service-start outcome. We can't easily
        // observe AlarmManager.set from this layer (it's mocked by
        // Robolectric), but we CAN observe that the credential-store
        // `NextAlarmTimestamp` gets a value populated by setAlarm.
        val prefs = getEncryptedSharedPreferences(realCtx)
        prefs.edit().putBoolean("auto_restart_monitoring", true).commit()

        val ctx = ServiceStartFailing(realCtx as Application)
        // ProductionAlertCheckDeps.scheduleAlarm calls setAlarm(context, ...)
        // which writes NextAlarmTimestamp via getEncryptedSharedPreferences.
        // Because our wrapper context only throws for startService, the
        // scheduleAlarm path is unaffected.
        val before = System.currentTimeMillis()
        newDeps(ctx).dispatchFinalAlert(
            prefs = prefs,
            nowTimestamp = before,
            checkPeriodHours = 12f,
            restPeriods = mutableListOf()
        )

        val saved = prefs.getLong("NextAlarmTimestamp", 0)
        assertTrue(
            "NextAlarmTimestamp must be populated by the auto-restart " +
                    "scheduleAlarm call after dispatchFinalAlert; got $saved",
            saved >= before
        )
    }

    @Test fun `auto_restart_monitoring disabled means no reschedule after dispatch`() {
        // Companion to the test above — confirms the auto-restart branch is
        // gated by the pref, and isn't accidentally always-on.
        val prefs = getEncryptedSharedPreferences(realCtx)
        // auto_restart_monitoring defaults to false; do not set it.

        val ctx = ServiceStartFailing(realCtx as Application)
        newDeps(ctx).dispatchFinalAlert(
            prefs = prefs,
            nowTimestamp = System.currentTimeMillis(),
            checkPeriodHours = 12f,
            restPeriods = mutableListOf()
        )

        assertEquals("with auto_restart_monitoring=false, NextAlarmTimestamp " +
                "should NOT be written by dispatchFinalAlert",
            0L, prefs.getLong("NextAlarmTimestamp", 0L))
    }
}
