package io.keepalive.android.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ---- Result-code → notification mapping --------------------------------
    //
    // SMSSentReceiver.onReceive reads `resultCode` (the broadcast's ordered-
    // result code) and posts a status notification: success on RESULT_OK,
    // failure with an error string for any other code.
    //
    // Driving `resultCode` from a unit test requires giving the receiver a
    // PendingResult — that's normally framework-provided when an ordered
    // broadcast dispatches. Here we construct one via reflection on its
    // public constructor and set it on the receiver before calling onReceive.

    private fun pendingResultWithCode(code: Int): Any {
        // Constructor signature (stable across recent SDKs):
        //   PendingResult(int resultCode, String resultData, Bundle resultExtras,
        //                 int type, boolean ordered, boolean sticky,
        //                 IBinder token, int userId, int flags)
        val cls = BroadcastReceiver.PendingResult::class.java
        val ctor = cls.declaredConstructors.maxByOrNull { it.parameterCount }!!
        ctor.isAccessible = true
        val args: Array<Any?> = ctor.parameterTypes.mapIndexed { i, type ->
            when {
                i == 0 -> code  // resultCode
                type == Int::class.javaPrimitiveType -> 0
                type == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return ctor.newInstance(*args)
    }

    /** `setPendingResult` is `@SystemApi` (not in the SDK stub) — reflect. */
    private fun BroadcastReceiver.installPendingResult(pr: Any) {
        val m = BroadcastReceiver::class.java.getMethod(
            "setPendingResult",
            BroadcastReceiver.PendingResult::class.java
        )
        m.invoke(this, pr)
    }

    private fun fireWithCode(code: Int) {
        // Grant POST_NOTIFICATIONS so AlertNotificationHelper actually posts.
        org.robolectric.Shadows.shadowOf(appCtx as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        nm.cancelAll()

        val receiver = SMSSentReceiver(expectedBroadcasts = 1)
        receiver.installPendingResult(pendingResultWithCode(code))
        receiver.onReceive(CountingContext(appCtx), Intent("SMS_SENT"))
    }

    @Test fun `RESULT_OK posts the SMS_ALERT_SENT success notification`() {
        fireWithCode(android.app.Activity.RESULT_OK)

        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        val active = nm.activeNotifications
        assertEquals("RESULT_OK should post exactly the success notification",
            1, active.size)
        assertEquals(io.keepalive.android.AppController.SMS_ALERT_SENT_NOTIFICATION_ID,
            active[0].id)
    }

    @Test fun `error result codes post the SMS_ALERT_FAILURE notification with a different ID`() {
        // Spec-locking: success and failure notifications use DIFFERENT IDs
        // so a partially-failed multipart batch surfaces both. If a future
        // refactor accidentally collapses the IDs the user would lose this
        // disambiguation.
        fireWithCode(android.telephony.SmsManager.RESULT_ERROR_NO_SERVICE)

        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        val active = nm.activeNotifications
        assertEquals(1, active.size)
        assertEquals(io.keepalive.android.AppController.SMS_ALERT_FAILURE_NOTIFICATION_ID,
            active[0].id)
    }

    @Test fun `success and failure within the same batch produce both notifications`() {
        // The real motivating case: a 3-part SMS where part 2 fails. The
        // receiver must post BOTH the success notification (one of the
        // successful parts) and the failure notification (the failed part)
        // so the user can tell something went wrong even though most parts
        // got through. A future refactor that uses a single notification ID
        // for both would break this and the user could mistake a partial
        // failure for a full success.
        org.robolectric.Shadows.shadowOf(appCtx as android.app.Application)
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        nm.cancelAll()

        val receiver = SMSSentReceiver(expectedBroadcasts = 3)
        val ctx = CountingContext(appCtx)
        receiver.installPendingResult(pendingResultWithCode(android.app.Activity.RESULT_OK))
        receiver.onReceive(ctx, Intent("SMS_SENT"))
        receiver.installPendingResult(
            pendingResultWithCode(android.telephony.SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        )
        receiver.onReceive(ctx, Intent("SMS_SENT"))
        receiver.installPendingResult(pendingResultWithCode(android.app.Activity.RESULT_OK))
        receiver.onReceive(ctx, Intent("SMS_SENT"))

        val ids = nm.activeNotifications.map { it.id }.toSet()
        assertTrue("success notification must be posted",
            io.keepalive.android.AppController.SMS_ALERT_SENT_NOTIFICATION_ID in ids)
        assertTrue("failure notification must be posted alongside success",
            io.keepalive.android.AppController.SMS_ALERT_FAILURE_NOTIFICATION_ID in ids)
    }

}
