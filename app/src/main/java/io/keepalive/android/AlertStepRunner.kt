package io.keepalive.android

/**
 * Bit flags for the idempotent step tracker used during alert dispatch.
 *
 * Must stay in sync with [AlertService.ALL_STEPS_COMPLETE] (the service holds
 * a private copy for the dedup guard; the two values must agree).
 */
internal const val STEP_SMS_SENT = 1       // SMS messages dispatched to all contacts
internal const val STEP_CALL_MADE = 2      // Phone call placed
internal const val STEP_LOCATION_DONE = 4  // Location SMS sent (or not needed / failed)
internal const val STEP_WEBHOOK_DONE = 8   // Webhook request sent (or not needed / failed)
internal const val ALL_STEPS_COMPLETE =
    STEP_SMS_SENT or STEP_CALL_MADE or STEP_LOCATION_DONE or STEP_WEBHOOK_DONE


/** Reads / writes the persistent step-completion bitmask for the current alert cycle. */
interface AlertStepOps {
    fun isComplete(step: Int): Boolean
    fun markComplete(step: Int)
}

/**
 * All side-effecting operations [runAlertSteps] needs. A production impl lives
 * inside [AlertService]; tests pass a fake that records calls.
 *
 * `locationNeeded` and `webhookEnabled` are read up-front so the sequencing
 * logic isn't coupled to preference storage. `now()` is injectable for
 * deterministic `LastAlertAt` assertions.
 */
interface AlertDispatcher {
    /** Whether the device has been unlocked since boot. */
    val isUserUnlocked: Boolean

    /**
     * True if either the location-SMS or the webhook-location-attachment
     * feature is enabled. Controls whether the async LocationHelper path runs.
     */
    val locationNeeded: Boolean

    /** True if the webhook delivery step is configured. */
    val webhookEnabled: Boolean

    fun cancelAreYouThereNotification()

    /** Called when the user is unlocked; overlays can't display during Direct Boot. */
    fun dismissAreYouThereOverlay()

    fun sendSmsAlert()
    fun makeCall()
    fun writeLastAlertAt(timestamp: Long)

    /**
     * Kicks off an async location request. The callback may run on any thread;
     * it must mark the location/webhook steps complete and call [stopService].
     * Throwing from this method is treated as a location-provider failure —
     * both location and webhook steps get marked done and the service stops.
     */
    fun requestLocationAsync(onResult: (LocationResult) -> Unit)

    fun sendLocationSms(locationText: String)
    fun sendWebhook(locationResult: LocationResult?)

    fun stopService()
    fun now(): Long
}


/**
 * Pure sequencing logic for a single alert cycle.
 *
 * Order is deliberately:
 *  1. Cancel the "Are you there?" prompt + overlay (idempotent)
 *  2. Send SMS
 *  3. Place phone call
 *  4. Write `LastAlertAt` (done before the async step so it's guaranteed)
 *  5. Location / webhook — async if location needed, sync otherwise
 *  6. `stopService` once everything is done (or, in the async path, from the
 *     location callback)
 *
 * Each step checks [AlertStepOps.isComplete] first and returns early if done.
 * This is how `START_REDELIVER_INTENT` resume works: completed steps are
 * skipped, incomplete steps are retried.
 *
 * Returns **true** if async work is pending (the caller should leave the
 * service running; the location callback will stop it). Returns **false**
 * if the method already called [AlertDispatcher.stopService].
 */
internal fun runAlertSteps(steps: AlertStepOps, disp: AlertDispatcher): Boolean {
    // Cancel prompt first — these are idempotent and the user has effectively
    // "been responded for".
    disp.cancelAreYouThereNotification()
    if (disp.isUserUnlocked) {
        disp.dismissAreYouThereOverlay()
    }

    // ---- Step 1: SMS ----
    if (!steps.isComplete(STEP_SMS_SENT)) {
        disp.sendSmsAlert()
        steps.markComplete(STEP_SMS_SENT)
    }

    // ---- Step 2: Phone call ----
    // Done synchronously BEFORE the async location step so a fast location
    // callback can't stop the service before the call is placed.
    if (!steps.isComplete(STEP_CALL_MADE)) {
        disp.makeCall()
        steps.markComplete(STEP_CALL_MADE)
    }

    // Persisted before any async work so we don't lose it on process death.
    disp.writeLastAlertAt(disp.now())

    val locationNeeded = disp.locationNeeded
    val webhookEnabled = disp.webhookEnabled

    // ---- Steps 3 & 4: Location SMS + Webhook ----
    if (locationNeeded) {
        val needLocationSms = !steps.isComplete(STEP_LOCATION_DONE)
        val needWebhook = webhookEnabled && !steps.isComplete(STEP_WEBHOOK_DONE)

        if (needLocationSms || needWebhook) {
            try {
                disp.requestLocationAsync { locationResult ->
                    if (!steps.isComplete(STEP_LOCATION_DONE)) {
                        disp.sendLocationSms(locationResult.formattedLocationString)
                        steps.markComplete(STEP_LOCATION_DONE)
                    }
                    if (webhookEnabled && !steps.isComplete(STEP_WEBHOOK_DONE)) {
                        disp.sendWebhook(locationResult)
                        steps.markComplete(STEP_WEBHOOK_DONE)
                    } else if (!webhookEnabled) {
                        steps.markComplete(STEP_WEBHOOK_DONE)
                    }
                    disp.stopService()
                }
                return true  // async work pending — caller MUST NOT stop service
            } catch (e: Exception) {
                // Mark both done on failure so we don't endlessly retry a broken
                // location provider; the core SMS + call already went through.
                steps.markComplete(STEP_LOCATION_DONE)
                if (!steps.isComplete(STEP_WEBHOOK_DONE)) {
                    steps.markComplete(STEP_WEBHOOK_DONE)
                }
                // fall through to synchronous stopService below
            }
        }
        // either steps already done or we just marked-on-failure — fall through
    } else {
        // Location not enabled — mark as not needed and maybe send webhook with no location.
        steps.markComplete(STEP_LOCATION_DONE)
        if (webhookEnabled && !steps.isComplete(STEP_WEBHOOK_DONE)) {
            disp.sendWebhook(null)
            steps.markComplete(STEP_WEBHOOK_DONE)
        } else {
            steps.markComplete(STEP_WEBHOOK_DONE)
        }
    }

    disp.stopService()
    return false
}
