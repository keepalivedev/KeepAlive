package io.keepalive.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins [AlarmRecovery.migrateAlertSentMarker], the one-time repair for installs
 * upgrading from builds that never wrote "alert_sent" (every release up to
 * 1.5.1). Those builds wrote last_alarm_stage = "periodic" after dispatching
 * and, with Auto-Restart off, never re-armed — the same shape recovery treats
 * as a dropped periodic alarm. The signal that an alert actually went out is
 * LastAlertAt, written only by a real alert at dispatch time.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmRecoveryMigrationTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val UTILITY_FUNCTIONS_KT = "io.keepalive.android.UtilityFunctionsKt"

    private val finalTime = System.currentTimeMillis() - 3 * 60 * 60_000L

    private fun devicePrefs() = getDeviceProtectedPreferences(appCtx)

    // what 1.5.1 left behind after dispatching, with Auto-Restart off
    private fun leaveLegacyState(lastAlertAt: Long?, autoRestart: Boolean = false) {
        devicePrefs().edit()
            .putString("last_alarm_stage", "periodic")
            .putLong("NextAlarmTimestamp", finalTime)
            .commit()
        getAppSharedPreferences(appCtx).edit()
            .putBoolean("auto_restart_monitoring", autoRestart)
            .apply { if (lastAlertAt != null) putLong("LastAlertAt", lastAlertAt) }
            .commit()
    }

    @Before fun setUp() {
        mockkStatic(UTILITY_FUNCTIONS_KT)
        every { isUserUnlocked(any()) } returns true
        devicePrefs().edit().clear().commit()
        getAppSharedPreferences(appCtx).edit().clear().commit()
    }

    @After fun tearDown() {
        unmockkStatic(UTILITY_FUNCTIONS_KT)
    }

    @Test fun `an alert dispatched at the saved alarm time becomes alert_sent`() {
        leaveLegacyState(lastAlertAt = finalTime + 5_000L)

        AlarmRecovery.migrateAlertSentMarker(appCtx)

        assertEquals("alert_sent", devicePrefs().getString("last_alarm_stage", null))
    }

    @Test fun `an alert that predates the saved alarm time is not the final that fired`() {
        // Last alert was a previous cycle; this final never went out (dropped, or
        // still Doze-deferred). Recovery must be free to handle it.
        leaveLegacyState(lastAlertAt = finalTime - 24 * 60 * 60_000L)

        AlarmRecovery.migrateAlertSentMarker(appCtx)

        assertEquals("periodic", devicePrefs().getString("last_alarm_stage", null))
    }

    @Test fun `no alert ever sent leaves the state alone`() {
        leaveLegacyState(lastAlertAt = null)

        AlarmRecovery.migrateAlertSentMarker(appCtx)

        assertEquals("periodic", devicePrefs().getString("last_alarm_stage", null))
    }

    @Test fun `auto-restart on is left for recovery`() {
        // With Auto-Restart on a sent alert was followed by a periodic setAlarm(),
        // so whatever is saved is a live cycle, not a disarm.
        leaveLegacyState(lastAlertAt = finalTime + 5_000L, autoRestart = true)

        AlarmRecovery.migrateAlertSentMarker(appCtx)

        assertEquals("periodic", devicePrefs().getString("last_alarm_stage", null))
    }

    @Test fun `LastAlertAt in the device-protected store is honored too`() {
        // The alert may have run during Direct Boot, in which case
        // getAppSharedPreferences resolved to the device store at dispatch.
        leaveLegacyState(lastAlertAt = null)
        devicePrefs().edit().putLong("LastAlertAt", finalTime + 5_000L).commit()

        AlarmRecovery.migrateAlertSentMarker(appCtx)

        assertEquals("alert_sent", devicePrefs().getString("last_alarm_stage", null))
    }

    @Test fun `waits for an unlocked start instead of burning the flag while locked`() {
        // While locked the credential copy of LastAlertAt is unreadable; deciding
        // then would mark the repair done on incomplete information.
        every { isUserUnlocked(any()) } returns false
        leaveLegacyState(lastAlertAt = finalTime + 5_000L)

        AlarmRecovery.migrateAlertSentMarker(appCtx)

        assertEquals("periodic", devicePrefs().getString("last_alarm_stage", null))
        assertFalse(devicePrefs().getBoolean("alert_sent_marker_migrated", false))
    }

    @Test fun `runs only once`() {
        // A later genuinely-dropped alarm must stay recoverable, so the repair
        // has to stop applying after the first run.
        leaveLegacyState(lastAlertAt = null)
        AlarmRecovery.migrateAlertSentMarker(appCtx)
        assertTrue(devicePrefs().getBoolean("alert_sent_marker_migrated", false))

        getAppSharedPreferences(appCtx).edit().putLong("LastAlertAt", finalTime + 5_000L).commit()

        AlarmRecovery.migrateAlertSentMarker(appCtx)

        assertEquals("periodic", devicePrefs().getString("last_alarm_stage", null))
    }
}
