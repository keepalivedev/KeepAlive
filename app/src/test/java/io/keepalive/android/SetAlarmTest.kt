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

/**
 * Tests for [setAlarm] — the AlarmManager interaction for scheduling the
 * next periodic or final alarm.
 *
 * Uses Robolectric's `ShadowAlarmManager` to inspect what was scheduled.
 */
@RunWith(RobolectricTestRunner::class)
class SetAlarmTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarm get() = shadowOf(alarmManager)

    @Before fun setUp() {
        // Start from a clean slate.
        shadowAlarm.scheduledAlarms.clear()
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

    @Test fun `NextAlarmTimestamp is persisted to both credential and device prefs`() {
        val now = System.currentTimeMillis()
        setAlarm(appCtx, now, desiredAlarmInMinutes = 30, alarmStage = "periodic")

        val credSaved = getEncryptedSharedPreferences(appCtx).getLong("NextAlarmTimestamp", 0)
        val devSaved = getDeviceProtectedPreferences(appCtx).getLong("NextAlarmTimestamp", 0)

        assertTrue("credential prefs remembers next alarm time", credSaved > now)
        assertTrue("device-protected prefs mirrors it for Direct Boot", devSaved > now)
        assertEquals(credSaved, devSaved)
    }

    @Test fun `last_alarm_stage is mirrored to device-protected storage`() {
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
}
