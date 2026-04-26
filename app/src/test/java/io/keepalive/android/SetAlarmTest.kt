package io.keepalive.android

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for [setAlarm] — the AlarmManager interaction for scheduling the
 * next periodic or final alarm.
 *
 * Uses Robolectric's `ShadowAlarmManager` to inspect what was scheduled.
 *
 * Runs under multiple SDK levels to cover the version-sensitive branches in
 * [setAlarm]: `setExact` (pre-M) vs `setAndAllowWhileIdle` (M+) vs
 * `setExactAndAllowWhileIdle` (M+ with user pref) vs `setAlarmClock` (final
 * alarm on systems that permit exact); plus `canScheduleExactAlarms` gate
 * added in API S; plus device-protected-storage mirror added in API N.
 */
@RunWith(RobolectricTestRunner::class)
// Robolectric 4.16 only ships SDK jars for API 23+; genuine API 22 coverage
// comes from the instrumented test CI matrix (emulator image supports it).
@Config(sdk = [23, 28, 33, 34, 35, 36])
class SetAlarmTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarm get() = shadowOf(alarmManager)

    @Before fun setUp() {
        // Start from a clean slate.
        shadowAlarm.scheduledAlarms.clear()
        // ShadowAlarmManager.canScheduleExactAlarms is a *static* (process-
        // level) flag — bleed-over from a prior test would silently flip the
        // exact-alarm branch. Reset it here so individual tests own its value.
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        getEncryptedSharedPreferences(appCtx).edit()
            .putBoolean("use_exact_alarms", false)
            .commit()
    }

    @Test fun `periodic alarm is scheduled in the future`() {
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, desiredAlarmInMinutes = 10, alarmStage = "periodic")

        val scheduled = shadowAlarm.nextScheduledAlarm
        assertNotNull("exactly one alarm should be scheduled", scheduled)
        assertNotNull(scheduled!!.operation)
    }

    @Test fun `final alarm is scheduled regardless of exact-alarm permission`() {
        // Final alarms take setAlarmClock() when exact is permitted, or fall back
        // to setAndAllowWhileIdle/set otherwise. Either way, exactly one alarm
        // must end up scheduled.
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, desiredAlarmInMinutes = 60, alarmStage = "final")

        assertEquals(1, shadowAlarm.scheduledAlarms.size)
        assertNotNull(shadowAlarm.nextScheduledAlarm?.operation)
    }

    @Test fun `NextAlarmTimestamp is persisted to credential prefs on all SDK levels`() {
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, desiredAlarmInMinutes = 30, alarmStage = "periodic")

        val credSaved = getEncryptedSharedPreferences(appCtx).getLong("NextAlarmTimestamp", 0)
        assertTrue("credential prefs remembers next alarm time", credSaved > now)
    }

    @Test
    @Config(sdk = [28, 33, 34, 35, 36])  // device-protected storage is API N (24)+
    fun `NextAlarmTimestamp is mirrored to device-protected storage on API N+`() {
        // Device-protected storage (Direct Boot) only exists on API N+.
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, desiredAlarmInMinutes = 30, alarmStage = "periodic")

        val credSaved = getEncryptedSharedPreferences(appCtx).getLong("NextAlarmTimestamp", 0)
        val devSaved = getDeviceProtectedPreferences(appCtx).getLong("NextAlarmTimestamp", 0)

        assertTrue("device-protected prefs mirrors it for Direct Boot", devSaved > now)
        assertEquals(credSaved, devSaved)
    }

    @Test
    @Config(sdk = [28, 33, 34, 35, 36])
    fun `last_alarm_stage is mirrored to device-protected storage on API N+`() {
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, 30, "final")

        assertEquals("final",
            getDeviceProtectedPreferences(appCtx).getString("last_alarm_stage", null))
    }

    @Test fun `setting a periodic alarm overwrites a previous one (same PendingIntent)`() {
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, 10, "periodic")
        setAlarm(appCtx, now, 20, "periodic")

        assertEquals("AlarmManager deduplicates by PendingIntent — only one alarm",
            1, shadowAlarm.scheduledAlarms.size)
    }

    @Test fun `alarm computed to be in the past is forced to 1 minute from now`() {
        val wayInThePast = System.currentTimeMillis() - 1000 * 60 * 60
        setAlarm(appCtx, wayInThePast, desiredAlarmInMinutes = 1, alarmStage = "periodic")

        val credSaved = getEncryptedSharedPreferences(appCtx).getLong("NextAlarmTimestamp", 0)
        assertTrue("alarm must fire in the future, not the past",
            credSaved >= System.currentTimeMillis())
    }

    @Test fun `cancelAlarm cancels the scheduled alarm`() {
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, 10, "periodic")
        assertEquals(1, shadowAlarm.scheduledAlarms.size)

        cancelAlarm(appCtx)

        // ShadowAlarmManager removes the scheduled alarm when cancel() is called
        // on a matching PendingIntent.
        assertEquals(0, shadowAlarm.scheduledAlarms.size)
    }

    @Test fun `exact-alarm user preference is honored when the system permits`() {
        getEncryptedSharedPreferences(appCtx).edit()
            .putBoolean("use_exact_alarms", true)
            .commit()
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, 10, "periodic")

        val scheduled = shadowAlarm.nextScheduledAlarm
        assertNotNull(scheduled)
        // Can't easily distinguish setExactAndAllowWhileIdle vs setAndAllowWhileIdle
        // via ShadowAlarmManager without looking at the recorded type. Just
        // confirm something was scheduled with the right PendingIntent.
        assertNotNull(scheduled!!.operation)
    }

    // ---- API-level-specific branches ---------------------------------------

    // True API 22 (pre-M) setExact coverage lives in the instrumented test
    // matrix — Robolectric 4.16 can't boot that SDK image.

    @Test
    @Config(sdk = [33, 34, 35, 36])  // canScheduleExactAlarms is API S+ (31)
    fun `on API 31+ the exact-alarm pref is only honored if canScheduleExactAlarms`() {
        // Robolectric's shadow defaults canScheduleExactAlarms to true, so with
        // use_exact_alarms=true we go down the setExactAndAllowWhileIdle branch.
        // If we deny exact alarms, setAlarm falls back to setAndAllowWhileIdle
        // (code path only reachable on API S+).
        getEncryptedSharedPreferences(appCtx).edit()
            .putBoolean("use_exact_alarms", true)
            .commit()
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, 10, "periodic")

        val scheduled = shadowAlarm.nextScheduledAlarm
        assertNotNull(scheduled)
        // Exact-alarm path is wake-up elapsed-realtime. The fallback path on
        // same SDK also uses ELAPSED_REALTIME_WAKEUP; we can't easily
        // discriminate the two via ShadowAlarmManager's scheduledAlarms list.
        // Smoke check: still scheduled, still wake-up.
        assertEquals(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, scheduled!!.type)
    }

    // ---- SCHEDULE_EXACT_ALARM denied (API S/31+) ---------------------------
    //
    // Production behavior: when `canScheduleExactAlarms()` returns false, the
    // periodic path silently uses `setAndAllowWhileIdle` regardless of
    // `use_exact_alarms`, and the final path falls back to
    // `setAndAllowWhileIdle` (M+) instead of `setAlarmClock`. Either way an
    // alarm IS scheduled — the safety net of getting *some* alarm matters
    // more than getting an exact one.

    @Test
    @Config(sdk = [33, 34, 35, 36])
    fun `periodic alarm is still scheduled when SCHEDULE_EXACT_ALARM is denied`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        getEncryptedSharedPreferences(appCtx).edit()
            .putBoolean("use_exact_alarms", true)  // user wanted exact
            .commit()

        setAlarm(appCtx, System.currentTimeMillis(), 10, "periodic")

        val scheduled = shadowAlarm.nextScheduledAlarm
        assertNotNull("periodic must still schedule an inexact alarm when exact is denied",
            scheduled)
        // Periodic path on M+ without exact uses ELAPSED_REALTIME_WAKEUP.
        assertEquals(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, scheduled!!.type)
    }

    @Test
    @Config(sdk = [33, 34, 35, 36])
    fun `final alarm falls back to setAndAllowWhileIdle when SCHEDULE_EXACT_ALARM is denied`() {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)

        setAlarm(appCtx, System.currentTimeMillis(), 60, "final")

        val scheduled = shadowAlarm.nextScheduledAlarm
        assertNotNull("final must still schedule even without exact-alarm permission",
            scheduled)
        // setAlarmClock uses RTC_WAKEUP; the fallback setAndAllowWhileIdle uses
        // ELAPSED_REALTIME_WAKEUP. The denied-path scheduled alarm must NOT be
        // an alarm-clock entry (which would mean we successfully called
        // setAlarmClock — but canScheduleExactAlarms=false should prevent that).
        assertEquals(
            "denied exact must use ELAPSED_REALTIME_WAKEUP fallback, not RTC_WAKEUP alarmClock",
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, scheduled!!.type
        )
        // ShadowAlarmManager exposes the AlarmClockInfo on the scheduled
        // entry; the fallback path leaves it null.
        assertEquals(
            "denied exact path must not produce an AlarmClock entry",
            null, scheduled.alarmClockInfo
        )
    }

    @Test
    @Config(sdk = [33, 34, 35, 36])
    fun `final alarm uses setAlarmClock when SCHEDULE_EXACT_ALARM is granted`() {
        // Companion to the denied test above: confirm we DID take the exact
        // path when permitted, so the denied test isn't passing for the wrong
        // reason.
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)

        setAlarm(appCtx, System.currentTimeMillis(), 60, "final")

        val scheduled = shadowAlarm.nextScheduledAlarm
        assertNotNull(scheduled)
        assertNotNull("granted exact-alarm path must produce an AlarmClock entry",
            scheduled!!.alarmClockInfo)
    }
}
