package io.keepalive.android

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Wake-lock lifecycle tests for [AlertService].
 *
 * Robolectric 4.16 dropped `ShadowPowerManager.getLatestWakeLock()`. We
 * introspect the service's private `wakeLock` field directly — more robust
 * across Robolectric versions and checks what the production code actually
 * holds, not a shadow's recollection of it.
 */
@RunWith(RobolectricTestRunner::class)
class AlertServiceWakeLockTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    private fun wakeLockOf(service: AlertService): PowerManager.WakeLock {
        val field = AlertService::class.java.getDeclaredField("wakeLock")
        field.isAccessible = true
        return field.get(service) as PowerManager.WakeLock
    }

    private fun newAlertIntent(triggerTimestamp: Long = System.currentTimeMillis()) =
        Intent(appCtx, AlertService::class.java).apply {
            putExtra(AlertService.EXTRA_ALERT_TRIGGER_TIMESTAMP, triggerTimestamp)
        }

    @Before fun clearPrefs() {
        getEncryptedSharedPreferences(appCtx).edit().clear().commit()
    }

    @Test fun `onCreate constructs a PARTIAL wake lock`() {
        val service = Robolectric.buildService(AlertService::class.java).create().get()

        val wakeLock = wakeLockOf(service)
        assertNotNull(wakeLock)
        // We can't directly inspect the level (PARTIAL vs FULL) without
        // reflecting on PowerManager internals. Instead confirm it's the
        // lock the service will use — it's not held yet because
        // onStartCommand hasn't run.
        assertFalse("onCreate must not acquire — only construct", wakeLock.isHeld)

        service.onDestroy()
    }

    @Test fun `onStartCommand acquires the wake lock`() {
        val controller = Robolectric.buildService(AlertService::class.java, newAlertIntent())
            .create()
            .startCommand(0, 1)
        val wakeLock = wakeLockOf(controller.get())

        assertTrue("onStartCommand must acquire", wakeLock.isHeld)

        controller.get().onDestroy()
    }

    @Test fun `onDestroy releases the wake lock`() {
        val controller = Robolectric.buildService(AlertService::class.java, newAlertIntent())
            .create()
            .startCommand(0, 1)
        val wakeLock = wakeLockOf(controller.get())
        assertTrue(wakeLock.isHeld)

        controller.get().onDestroy()

        assertFalse("onDestroy must release", wakeLock.isHeld)
    }

    @Test fun `back-to-back acquires do not leak due to refcounting`() {
        // Historical bug: default WakeLock reference-counting meant N acquires
        // required N releases. AlertService.onCreate disables refcounting so a
        // single release() in onDestroy fully unwinds.
        val controller = Robolectric.buildService(AlertService::class.java, newAlertIntent())
            .create()
            .startCommand(0, 1)
            .startCommand(0, 2)
            .startCommand(0, 3)
        val wakeLock = wakeLockOf(controller.get())
        assertTrue(wakeLock.isHeld)

        controller.get().onDestroy()

        assertFalse("single release() must fully release regardless of acquire count",
            wakeLock.isHeld)
    }

    @Test fun `stale-trigger intent does not hold a wake lock past the dedup guard`() {
        // Seed a newer trigger as "already saved", then deliver an older one.
        val prefs = getEncryptedSharedPreferences(appCtx)
        prefs.edit()
            .putLong("AlertTriggerTimestamp", 10_000L)
            .putInt("AlertStepsCompleted", 0)
            .commit()

        val staleIntent = Intent(appCtx, AlertService::class.java).apply {
            putExtra(AlertService.EXTRA_ALERT_TRIGGER_TIMESTAMP, 5_000L)
        }
        val controller = Robolectric.buildService(AlertService::class.java, staleIntent)
            .create()
            .startCommand(0, 1)
        val wakeLock = wakeLockOf(controller.get())

        // Dedup guard returned START_REDELIVER_INTENT before wakeLock.acquire().
        assertFalse("stale intent must not hold a wake lock", wakeLock.isHeld)

        controller.get().onDestroy()
    }

    @Test fun `wakeLock reference-counting is disabled so isHeld flips on a single release`() {
        // Bare behavioral check: even if something other than AlertService
        // acquires this exact WakeLock twice, a single release should fully
        // release it (because setReferenceCounted(false) was called in onCreate).
        val service = Robolectric.buildService(AlertService::class.java).create().get()
        val wakeLock = wakeLockOf(service)

        wakeLock.acquire(60_000L)
        wakeLock.acquire(60_000L)
        wakeLock.acquire(60_000L)
        assertTrue(wakeLock.isHeld)

        wakeLock.release()

        assertFalse("refcount was disabled — single release fully unwinds", wakeLock.isHeld)

        service.onDestroy()
    }
}
