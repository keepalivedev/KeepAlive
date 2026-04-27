package io.keepalive.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies [SMSSentReceiver] only unregisters itself after receiving the
 * number of broadcasts it was constructed for. Guards against the pre-fix
 * behavior where the receiver unregistered on the first broadcast and
 * subsequent SMS results went unreported.
 */
@RunWith(RobolectricTestRunner::class)
class SMSSentReceiverTest {

    /**
     * Real Robolectric context but counts `unregisterReceiver` calls and can
     * be configured to throw IAE (the pre-unregistered case). Using a real
     * Context means AlertNotificationHelper construction works.
     */
    private class CountingContext(
        base: Context,
        private val throwOnUnregister: Boolean = false
    ) : ContextWrapper(base) {
        var unregisterCount = 0
            private set

        override fun unregisterReceiver(receiver: BroadcastReceiver?) {
            unregisterCount++
            if (throwOnUnregister) throw IllegalArgumentException("already unregistered")
        }
    }

    private val appCtx: Context = ApplicationProvider.getApplicationContext()

    @Test fun `does not unregister until expected broadcasts arrive`() {
        val ctx = CountingContext(appCtx)
        val receiver = SMSSentReceiver(expectedBroadcasts = 3)
        val intent = Intent("SMS_SENT")

        receiver.onReceive(ctx, intent)
        receiver.onReceive(ctx, intent)
        assertEquals("should not unregister before all broadcasts arrived",
            0, ctx.unregisterCount)

        receiver.onReceive(ctx, intent)
        assertEquals("should unregister on final expected broadcast",
            1, ctx.unregisterCount)
    }

    @Test fun `single-broadcast receiver unregisters on first onReceive`() {
        val ctx = CountingContext(appCtx)
        val receiver = SMSSentReceiver(expectedBroadcasts = 1)

        receiver.onReceive(ctx, Intent("SMS_SENT"))

        assertEquals(1, ctx.unregisterCount)
    }

    @Test fun `default constructor acts as single-broadcast`() {
        val ctx = CountingContext(appCtx)
        val receiver = SMSSentReceiver()

        receiver.onReceive(ctx, Intent("SMS_SENT"))

        assertEquals(1, ctx.unregisterCount)
    }

    @Test fun `stray extra broadcast past zero still attempts unregister and catches IAE`() {
        // If the safety-net unregister already ran and the receiver still
        // gets an intent, onReceive should not crash.
        val ctx = CountingContext(appCtx, throwOnUnregister = true)
        val receiver = SMSSentReceiver(expectedBroadcasts = 1)

        receiver.onReceive(ctx, Intent("SMS_SENT"))
        receiver.onReceive(ctx, Intent("SMS_SENT"))  // counter is now negative

        // Both calls attempted unregister; the IAE was swallowed both times.
        assertEquals(2, ctx.unregisterCount)
    }

}
