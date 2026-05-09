package io.keepalive.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import io.keepalive.android.AlertFlowTestUtil.resetToCleanEnabledState
import io.keepalive.android.AlertFlowTestUtil.targetContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests that settings written via credential-encrypted prefs
 * correctly propagate to device-protected storage, so Direct Boot sees them.
 *
 * This is pure pref-level plumbing; the machinery runs on real Android.
 */
@RunWith(AndroidJUnit4::class)
// Device-protected storage, the credential→device mirror, and the
// last_alarm_stage write are all gated on API N (24)+ in the production
// code. Pre-N these assertions read null and fail. API 22 skipped.
@SdkSuppress(minSdkVersion = android.os.Build.VERSION_CODES.N)
class SettingsPersistenceInstrumentedTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun grantAllPermissions() {
            TestSetupUtil.setupTestEnvironment()
        }
    }

    @Before fun setUp() {
        resetToCleanEnabledState()
    }

    @After fun tearDown() {
        AlertFlowTestUtil.cancelAnyPendingAlarms()
    }

    @Test fun syncCopiesCredentialEncryptedSettingsToDeviceProtected() {
        // Write a handful of values via the default prefs (the settings UI
        // would normally do this via preference-fragment).
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(targetContext)
            .edit()
            .putString("time_period_hours", "8")
            .putString("followup_time_period_minutes", "45")
            .putBoolean("enabled", true)
            .commit()

        syncPrefsToDeviceProtectedStorage(targetContext)

        val dev = getDeviceProtectedPreferences(targetContext)
        assertEquals("8", dev.getString("time_period_hours", null))
        assertEquals("45", dev.getString("followup_time_period_minutes", null))
        assertEquals(true, dev.getBoolean("enabled", false))
    }

    @Test fun settingAnAlarmStoresTimestampInBothStores() {
        // setAlarm writes NextAlarmTimestamp to credential-encrypted AND
        // mirrors to device-protected — critical for Direct Boot recovery.
        setAlarm(
            targetContext,
            lastActivityTimestamp = System.currentTimeMillis(),
            desiredAlarmInMinutes = 30,
            alarmStage = "periodic",
            restPeriods = null
        )

        val cred = getEncryptedSharedPreferences(targetContext)
            .getLong("NextAlarmTimestamp", 0L)
        val dev = getDeviceProtectedPreferences(targetContext)
            .getLong("NextAlarmTimestamp", 0L)

        assertTrue("credential-encrypted store has the timestamp", cred > 0L)
        assertEquals("device-protected store must mirror it", cred, dev)
    }

    @Test fun alarmStageIsPersistedToDeviceProtectedStorage() {
        setAlarm(
            targetContext,
            lastActivityTimestamp = System.currentTimeMillis(),
            desiredAlarmInMinutes = 10,
            alarmStage = "final",
            restPeriods = null
        )

        assertEquals("final",
            getDeviceProtectedPreferences(targetContext).getString("last_alarm_stage", null))
    }
}
