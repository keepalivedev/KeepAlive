package io.keepalive.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.keepalive.android.testing.fakeUsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests the UsageStatsManager query that drives activity detection. Uses
 * Robolectric's shadow UsageStatsManager to seed events.
 */
@RunWith(RobolectricTestRunner::class)
// getLastDeviceActivity branches at API P (28) on the keyguard-events path
// (not available pre-P → falls back to MOVE_TO_FOREGROUND only) and at API
// Q (29) for the ACTIVITY_RESUMED event type. Matrix exercises the split.
@Config(sdk = [23, 28, 33, 34, 35])
class DeviceActivityQueryTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val usm = appCtx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private fun addEvent(pkg: String, eventType: Int, timestamp: Long) {
        // UsageEvents.Event has no public constructor, so we build it via the
        // same reflection helper used by FakeAlertCheckDeps.
        shadowOf(usm).addEvent(
            fakeUsageEvent(packageName = pkg, timeStamp = timestamp, eventType = eventType)
        )
    }

    @Test fun `returns null when no events are present`() {
        val result = getLastDeviceActivity(
            appCtx,
            startTimestamp = 0L,
            monitoredApps = null
        )
        assertNull(result)
    }

    @Test
    @Config(sdk = [28, 33, 34, 35])  // keyguard-event path gated at API P+
    fun `returns most recent matching system keyguard event when no apps specified`() {
        addEvent("android", UsageEvents.Event.KEYGUARD_HIDDEN, 1_000L)
        addEvent("android", UsageEvents.Event.KEYGUARD_SHOWN, 5_000L)
        addEvent("android", UsageEvents.Event.KEYGUARD_HIDDEN, 3_000L)

        val result = getLastDeviceActivity(
            appCtx,
            startTimestamp = 0L,
            monitoredApps = null
        )

        assertEquals(5_000L, result?.timeStamp)
        assertEquals(UsageEvents.Event.KEYGUARD_SHOWN, result?.eventType)
    }

    @Test fun `ignores events for non-monitored packages`() {
        addEvent("com.example.other", UsageEvents.Event.ACTIVITY_RESUMED, 5_000L)
        addEvent("com.example.target", UsageEvents.Event.ACTIVITY_RESUMED, 2_000L)

        val result = getLastDeviceActivity(
            appCtx,
            startTimestamp = 0L,
            monitoredApps = listOf("com.example.target")
        )

        assertEquals("should only see the target app's event", 2_000L, result?.timeStamp)
    }

    @Test fun `ignores events outside the window`() {
        addEvent("android", UsageEvents.Event.KEYGUARD_HIDDEN, 500L)

        // Query starts at 1000 — earlier event should be excluded.
        val result = getLastDeviceActivity(
            appCtx,
            startTimestamp = 1_000L,
            monitoredApps = null
        )

        assertNull(result)
    }

    @Test
    @Config(sdk = [28, 33, 34, 35])  // fallback is gated at API P+
    fun `empty monitored apps list falls back to system package and finds keyguard events`() {
        addEvent("android", UsageEvents.Event.KEYGUARD_HIDDEN, 2_000L)

        val result = getLastDeviceActivity(
            appCtx,
            startTimestamp = 0L,
            monitoredApps = emptyList()
        )

        // API 28+: empty list triggers the fallback to system keyguard monitoring.
        // Robolectric runs at SDK 35 via properties, so we exercise that path.
        assertEquals(2_000L, result?.timeStamp)
    }
}
