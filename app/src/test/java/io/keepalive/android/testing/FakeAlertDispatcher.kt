package io.keepalive.android.testing

import io.keepalive.android.AlertDispatcher
import io.keepalive.android.AlertStepOps
import io.keepalive.android.LocationResult

/**
 * Recording fake of [AlertStepOps] backed by a mutable int bitmask.
 * Test-only seed helper so tests can set up "resume" scenarios directly.
 */
class FakeAlertStepOps(initial: Int = 0) : AlertStepOps {
    var bitmask: Int = initial
        private set

    override fun isComplete(step: Int): Boolean = (bitmask and step) != 0
    override fun markComplete(step: Int) { bitmask = bitmask or step }

    fun seed(mask: Int) { bitmask = mask }
}

/**
 * Recording fake of [AlertDispatcher].
 *
 * Records every method call in [callLog] so tests can assert exact ordering
 * (e.g. "SMS must be sent before call"). Individual counters are also exposed
 * for readable per-method assertions.
 *
 * Location handling has two modes:
 *   - [locationResultToDeliver] is set (default): the `requestLocationAsync`
 *     callback is invoked synchronously right away — simulates a cached fix.
 *   - `locationResultToDeliver = null`: the callback is NOT invoked; the
 *     saved callback is exposed via [pendingLocationCallback] so tests can
 *     fire it after checking intermediate state.
 */
class FakeAlertDispatcher(
    override var isUserUnlocked: Boolean = true,
    override var locationNeeded: Boolean = false,
    override var webhookEnabled: Boolean = false,
    var nowValue: Long = 1_000L
) : AlertDispatcher {

    val callLog = mutableListOf<String>()

    var cancelNotificationCount = 0
    var dismissOverlayCount = 0
    var smsSendCount = 0
    var callCount = 0
    var lastAlertAtWritten: Long? = null
    var locationSmsSends = mutableListOf<String>()
    var webhookSends = mutableListOf<LocationResult?>()
    var stopServiceCount = 0

    /** If non-null, `requestLocationAsync` invokes the callback synchronously with this. */
    var locationResultToDeliver: LocationResult? = null

    /** If [locationResultToDeliver] is null, the callback is saved here instead. */
    var pendingLocationCallback: ((LocationResult) -> Unit)? = null

    /** If set, `requestLocationAsync` throws this instead of calling the callback. */
    var locationRequestThrows: Exception? = null

    override fun cancelAreYouThereNotification() {
        callLog += "cancelNotification"
        cancelNotificationCount++
    }

    override fun dismissAreYouThereOverlay() {
        callLog += "dismissOverlay"
        dismissOverlayCount++
    }

    override fun sendSmsAlert() {
        callLog += "sendSms"
        smsSendCount++
    }

    override fun makeCall() {
        callLog += "makeCall"
        callCount++
    }

    override fun writeLastAlertAt(timestamp: Long) {
        callLog += "writeLastAlertAt"
        lastAlertAtWritten = timestamp
    }

    override fun requestLocationAsync(onResult: (LocationResult) -> Unit) {
        callLog += "requestLocationAsync"
        locationRequestThrows?.let { throw it }
        val result = locationResultToDeliver
        if (result != null) {
            onResult(result)
        } else {
            pendingLocationCallback = onResult
        }
    }

    override fun sendLocationSms(locationText: String) {
        callLog += "sendLocationSms"
        locationSmsSends += locationText
    }

    override fun sendWebhook(locationResult: LocationResult?) {
        callLog += "sendWebhook"
        webhookSends += locationResult
    }

    override fun stopService() {
        callLog += "stopService"
        stopServiceCount++
    }

    override fun now(): Long = nowValue
}
