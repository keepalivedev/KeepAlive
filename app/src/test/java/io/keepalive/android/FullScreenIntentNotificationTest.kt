package io.keepalive.android

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the full-screen intent attachment on the "Are you there?" notification
 * (issue #182): app overlay windows draw below the keyguard, so the
 * notification's full-screen intent launching [AreYouThereActivity] is the
 * only path that makes the prompt visible while the device is locked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33, 34, 36])
class FullScreenIntentNotificationTest {

    private val appCtx: Context = ApplicationProvider.getApplicationContext()
    private val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before fun setUp() {
        // USE_FULL_SCREEN_INTENT models the install-time grant (the state on
        //  sideloaded/F-Droid installs, and on Play installs with the core
        //  function approved) — canUseFullScreenIntent() consults it on API 34+
        shadowOf(appCtx as Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.USE_FULL_SCREEN_INTENT
        )
        nm.cancelAll()
        // explicit default: full-screen prompt enabled (AppController's
        //  first-run migration writes this in production)
        getAppSharedPreferences(appCtx).edit()
            .putBoolean(PrefKeys.ARE_YOU_THERE_OVERLAY_ENABLED, true)
            .commit()
    }

    private fun postAreYouThere() {
        AlertNotificationHelper(appCtx).sendNotification(
            "Are you still there?",
            "Tap here to confirm you're OK.",
            AppController.ARE_YOU_THERE_NOTIFICATION_ID
        )
    }

    @Test
    // pre-34 legs only: this Robolectric version hard-codes
    //  canUseFullScreenIntent() to false on API 34+ and offers no shadow
    //  setter, so the granted path can't be simulated there. on 34+ the only
    //  difference is that two-line gate; the real grant flow is covered by
    //  on-device testing
    @Config(sdk = [28, 33])
    fun `are-you-there notification carries a full-screen intent when enabled`() {
        postAreYouThere()

        val notif = shadowOf(nm).getNotification(null, AppController.ARE_YOU_THERE_NOTIFICATION_ID)
        assertNotNull("notification should be posted", notif)
        assertNotNull("full-screen intent must be attached so the prompt can " +
                "show over the lock screen", notif.fullScreenIntent)
    }

    @Test
    @Config(sdk = [34, 36])
    fun `notification still posts normally when the special access is not granted on API 34+`() {
        // Robolectric reports canUseFullScreenIntent() = false here — the same
        //  state as a fresh Play install without the core-function approval.
        //  The prompt gracefully degrades: notification posted, no FSI attached.
        postAreYouThere()

        val notif = shadowOf(nm).getNotification(null, AppController.ARE_YOU_THERE_NOTIFICATION_ID)
        assertNotNull("notification itself must still be posted", notif)
        assertNull("no full-screen intent without the special access", notif.fullScreenIntent)
    }

    @Test fun `no full-screen intent when the full-screen prompt setting is off`() {
        getAppSharedPreferences(appCtx).edit()
            .putBoolean(PrefKeys.ARE_YOU_THERE_OVERLAY_ENABLED, false)
            .commit()

        postAreYouThere()

        val notif = shadowOf(nm).getNotification(null, AppController.ARE_YOU_THERE_NOTIFICATION_ID)
        assertNotNull("notification itself must still be posted", notif)
        assertNull("setting off must not attach a full-screen intent", notif.fullScreenIntent)
    }

    @Test fun `other notifications never carry a full-screen intent`() {
        AlertNotificationHelper(appCtx).sendNotification(
            "Keep Alive Alert triggered!",
            "An SMS has been sent",
            AppController.SMS_ALERT_SENT_NOTIFICATION_ID
        )

        val notif = shadowOf(nm).getNotification(null, AppController.SMS_ALERT_SENT_NOTIFICATION_ID)
        assertNotNull(notif)
        assertNull(notif.fullScreenIntent)
    }

}
