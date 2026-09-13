package io.keepalive.android

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Safety net for the periodic alarm.
 *
 * A single self-rearming alarm is the only thing keeping monitoring alive, and
 * Android drops it in situations the app cannot see: the package being replaced
 * by an update, a force stop, or Doze/App Standby deferring it indefinitely.
 * Nothing recovered from that except a reboot or the user tapping Restart
 * Monitoring, so monitoring could stay silently dead for days while the main
 * screen still displayed a countdown.
 *
 * WorkManager keeps its own persisted schedule and re-registers itself across
 * reboots and app updates, giving the alarm an independent second leg. If the
 * saved alarm time is well past and nothing re-armed, [AlarmRecovery] puts the
 * alarm back so [io.keepalive.android.receivers.AlarmReceiver] runs the check
 * (see the WATCHDOG branch there for why the worker never runs it itself).
 *
 * [setAlarm] enqueues this and [cancelAlarm] cancels it, so its lifetime
 * follows the alarm it watches.
 */
class MonitoringWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        try {
            AlarmRecovery.restore(applicationContext, AlarmRecovery.Source.WATCHDOG)
        } catch (e: Exception) {
            // never let the watchdog crash the process it is meant to protect
            Log.e(TAG, "Error running monitoring watchdog", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "MonitoringWatchdog"
        private const val WORK_NAME = "monitoring_watchdog"

        // bounds how long monitoring can stay silently dead. a check that finds
        //  nothing wrong is a few preference reads, so running it often costs
        //  little; App Standby will throttle it further on idle devices anyway
        private const val INTERVAL_HOURS = 2L

        // confine each run to the end of its window: without a flex WorkManager may
        //  run consecutive periods almost two intervals apart
        private const val FLEX_MINUTES = 15L

        fun enqueue(context: Context) {

            // WorkManager initializes through a ContentProvider that isn't available
            //  during Direct Boot, and there is nothing to re-arm until the user has
            //  unlocked anyway
            if (!isUserUnlocked(context)) {
                Log.d(TAG, "User is locked, skipping watchdog scheduling")
                return
            }

            try {
                val request = PeriodicWorkRequestBuilder<MonitoringWatchdogWorker>(
                    INTERVAL_HOURS, TimeUnit.HOURS, FLEX_MINUTES, TimeUnit.MINUTES
                ).build()

                // KEEP so an already scheduled watchdog keeps its place in the queue
                //  instead of having its period restarted on every process launch
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
                )
            } catch (e: Exception) {
                Log.e(TAG, "Unable to schedule the monitoring watchdog", e)
            }
        }

        fun cancel(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to cancel the monitoring watchdog", e)
            }
        }
    }
}
