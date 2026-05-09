package io.keepalive.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Smoke tests for [AppController]. The class is a thin Application subclass
 * but it has two failure modes worth pinning:
 *
 *  1. **Constants drift.** Notification channel IDs, notification IDs, and
 *     request codes are all referenced across the codebase by name. A
 *     refactor that renumbers a notification ID would silently merge two
 *     notifications (e.g. SMS-success + SMS-failure both posting under id=2)
 *     because the system de-duplicates by id. Pinning the values here makes
 *     such a renumber a deliberate, visible change.
 *
 *  2. **onCreate startup.** `AppController.onCreate` runs at every app
 *     start, including Direct Boot. It calls `DebugLogger.initialize(this)`
 *     and reads `applicationInfo.flags` — if either of those starts
 *     throwing under a future SDK or a refactored DebugLogger, every
 *     instrumented + every Robolectric test silently breaks. This smoke
 *     test exercises the path with a real Application context.
 */
@RunWith(RobolectricTestRunner::class)
class AppControllerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun `AppController is the Application instance under Robolectric`() {
        // Robolectric reads the `android:name` from the test manifest. If a
        // future build accidentally drops the AppController declaration,
        // the type-check below fails. Without AppController.onCreate
        // running, DebugLogger never initializes — every test that depends
        // on file-backed debug logs would hit lazy init unpredictably.
        assertTrue("Application must be the AppController subclass — " +
                "manifest android:name must point at io.keepalive.android.AppController",
            app is AppController)
    }

    @Test fun `notification channel and ID constants are stable`() {
        // These values are the identity by which Android dedupes/groups
        // notifications and channels. Pin them so a future refactor that
        // accidentally collapses or renumbers them surfaces here.
        assertEquals("AlertNotificationChannel",
            AppController.ARE_YOU_THERE_NOTIFICATION_CHANNEL_ID)
        assertEquals("CallSentNotificationChannel",
            AppController.CALL_SENT_NOTIFICATION_CHANNEL_ID)
        assertEquals("SMSSentNotificationChannel",
            AppController.SMS_SENT_NOTIFICATION_CHANNEL_ID)
        assertEquals("AlertServiceNotificationChannel",
            AppController.ALERT_SERVICE_NOTIFICATION_CHANNEL_ID)
        assertEquals("WebhookSentNotificationChannel",
            AppController.WEBHOOK_SENT_NOTIFICATION_CHANNEL_ID)

        // Numeric IDs — pinned because the SMSSentReceiver uses two
        // DIFFERENT IDs (success vs failure) so a partial multipart send
        // shows both notifications. Collapsing them would lose that signal.
        assertEquals(1, AppController.ARE_YOU_THERE_NOTIFICATION_ID)
        assertEquals(2, AppController.SMS_ALERT_SENT_NOTIFICATION_ID)
        assertEquals(3, AppController.CALL_ALERT_SENT_NOTIFICATION_ID)
        assertEquals(4, AppController.SMS_ALERT_FAILURE_NOTIFICATION_ID)
        assertEquals(5, AppController.ALERT_SERVICE_NOTIFICATION_ID)
        assertEquals(6, AppController.WEBHOOK_ALERT_SENT_NOTIFICATION_ID)
    }

    @Test fun `notification IDs are unique`() {
        // The bug we're guarding against: someone accidentally setting two
        // constants to the same value. Notifications collapsed under one
        // id silently overwrite each other.
        val ids = listOf(
            AppController.ARE_YOU_THERE_NOTIFICATION_ID,
            AppController.SMS_ALERT_SENT_NOTIFICATION_ID,
            AppController.CALL_ALERT_SENT_NOTIFICATION_ID,
            AppController.SMS_ALERT_FAILURE_NOTIFICATION_ID,
            AppController.ALERT_SERVICE_NOTIFICATION_ID,
            AppController.WEBHOOK_ALERT_SENT_NOTIFICATION_ID
        )
        assertEquals("notification IDs must be unique; collisions silently " +
                "deduplicate notifications. ids=$ids",
            ids.size, ids.toSet().size)
    }

    @Test fun `request and result codes do not collide`() {
        // Same hazard, different failure mode: AlarmReceiver pending intents
        // share the request code namespace with hibernation activity
        // results. A collision would route alarm intents into the
        // hibernation handler.
        org.junit.Assert.assertNotEquals(
            AppController.ACTIVITY_ALARM_REQUEST_CODE,
            AppController.APP_HIBERNATION_ACTIVITY_RESULT_CODE
        )
    }

    @Test fun `MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK is API P`() {
        // The keyguard-event activity-detection path in DeviceActivityQuery
        // is gated on this constant. Bumping it without updating the
        // gate logic would silently change which APIs use the fallback
        // MOVE_TO_FOREGROUND path — that's the kind of change the
        // commit author should explicitly rationalize.
        assertEquals(android.os.Build.VERSION_CODES.P,
            AppController.MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK)
    }

    @Test fun `onCreate completed without throwing during Robolectric setup`() {
        // Robolectric calls Application.onCreate() during test setup. If
        // DebugLogger.initialize() were to start throwing (file-system
        // restriction, missing dir, etc.), the test process would have
        // crashed before this method runs. Reaching this assertion proves
        // onCreate completed at least once cleanly.
        assertNotNull(app)
    }
}
