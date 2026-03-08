package io.keepalive.android

import android.content.Context

/**
 * Thin wrapper around [AreYouThereOverlayService].
 *
 * NOTE: This intentionally does NOT hold onto any View/WindowManager references.
 */
object AreYouThereOverlay {

    fun canDrawOverlays(context: Context): Boolean = AreYouThereOverlayService.canDrawOverlays(context)

    fun show(context: Context, message: String?) {
        AreYouThereOverlayService.show(context.applicationContext, message)
    }

    /**
     * Dismiss the overlay (if it's showing).
     *
     * Requires a Context so we can message the service without keeping static references.
     */
    fun dismiss(context: Context) {
        AreYouThereOverlayService.dismiss(context.applicationContext)
    }
}
