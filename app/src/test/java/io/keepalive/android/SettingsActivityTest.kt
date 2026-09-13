package io.keepalive.android

import android.content.Context
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for the plain on/off switches on [SettingsActivity].
 * The dialog-driven settings are covered by the instrumented
 * SettingsPersistence tests.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsActivityTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        getAppSharedPreferences(appCtx).edit().clear().commit()
    }

    private fun launch(): SettingsActivity =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()

    // ---- debug logging switch (issue #166) ---------------------------------

    @Test fun `debug logging switch is on by default`() {
        val activity = launch()

        val switch: SwitchCompat = activity.findViewById(R.id.debugLoggingSwitch)
        assertTrue("logging stays on unless the user turns it off", switch.isChecked)
    }

    @Test fun `turning the debug logging switch off persists the preference`() {
        val activity = launch()

        val switch: SwitchCompat = activity.findViewById(R.id.debugLoggingSwitch)
        switch.isChecked = false

        assertFalse("switch must write the preference the logger reads",
            getAppSharedPreferences(appCtx).getBoolean("debug_logging_enabled", true))
    }
}
