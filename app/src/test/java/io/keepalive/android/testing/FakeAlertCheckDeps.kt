package io.keepalive.android.testing

import android.app.usage.UsageEvents
import android.content.SharedPreferences
import io.keepalive.android.AlertCheckDeps
import io.keepalive.android.RestPeriod
import java.lang.reflect.Constructor

/**
 * Recording fake of [AlertCheckDeps] for pure-JVM tests of the alert decision engine.
 *
 * Default state: user unlocked, clock at T=0, empty prefs, no recent activity.
 * Individual tests override via the public mutable fields before calling
 * `doAlertCheck(deps, ...)`.
 */
class FakeAlertCheckDeps : AlertCheckDeps {

    // --- Controllable state ---
    var nowValue: Long = 0L
    var userUnlockedValue: Boolean = true
    val credPrefs: FakeSharedPreferences = FakeSharedPreferences()
    val devPrefs: FakeSharedPreferences = FakeSharedPreferences()
    var lastActivity: UsageEvents.Event? = null

    /** Canned resource strings. Tests rarely need to customize this. */
    var getStringImpl: (Int, Array<out Any>) -> String = { resId, args ->
        if (args.isEmpty()) "res$resId" else "res$resId(${args.joinToString()})"
    }

    // --- Recorded calls ---
    data class ScheduledAlarm(
        val baseTimestamp: Long,
        val periodMinutes: Int,
        val stage: String,
        val restPeriods: MutableList<RestPeriod>?
    )

    data class FinalAlertCall(
        val nowTimestamp: Long,
        val checkPeriodHours: Float,
        val restPeriods: MutableList<RestPeriod>
    )

    val scheduledAlarms = mutableListOf<ScheduledAlarm>()
    val activityQueries = mutableListOf<Pair<Long, List<String>>>()
    var notificationShowCount = 0
    var notificationLastFollowupMinutes: Int? = null
    var overlayShowCount = 0
    var overlayLastFollowupMinutes: Int? = null
    var acknowledgeCount = 0
    val finalAlertCalls = mutableListOf<FinalAlertCall>()

    // --- AlertCheckDeps impl ---

    override fun now(): Long = nowValue
    override fun isUserUnlocked(): Boolean = userUnlockedValue
    override fun getString(resId: Int, vararg args: Any): String = getStringImpl(resId, args)
    override fun credentialPrefs(): SharedPreferences = credPrefs
    override fun devicePrefs(): SharedPreferences = devPrefs

    override fun getLastDeviceActivity(startTimestamp: Long, monitoredApps: List<String>): UsageEvents.Event? {
        activityQueries.add(startTimestamp to monitoredApps)
        return lastActivity
    }

    override fun scheduleAlarm(
        baseTimestamp: Long,
        periodMinutes: Int,
        stage: String,
        restPeriods: MutableList<RestPeriod>?
    ) {
        scheduledAlarms.add(ScheduledAlarm(baseTimestamp, periodMinutes, stage, restPeriods))
    }

    override fun showAreYouThereNotification(followupPeriodMinutes: Int) {
        notificationShowCount++
        notificationLastFollowupMinutes = followupPeriodMinutes
    }

    override fun showAreYouThereOverlay(followupPeriodMinutes: Int) {
        overlayShowCount++
        overlayLastFollowupMinutes = followupPeriodMinutes
    }

    override fun acknowledgeAreYouThere() {
        acknowledgeCount++
    }

    override fun dispatchFinalAlert(
        prefs: SharedPreferences,
        nowTimestamp: Long,
        checkPeriodHours: Float,
        restPeriods: MutableList<RestPeriod>
    ) {
        finalAlertCalls.add(FinalAlertCall(nowTimestamp, checkPeriodHours, restPeriods))
    }
}

/**
 * Build a [UsageEvents.Event] for tests. The class has no public constructor so
 * we reach in via reflection (stable across API 22 → 36).
 */
fun fakeUsageEvent(
    packageName: String = "android",
    timeStamp: Long = 0L,
    eventType: Int = UsageEvents.Event.KEYGUARD_HIDDEN
): UsageEvents.Event {
    val ctor: Constructor<UsageEvents.Event> =
        UsageEvents.Event::class.java.getDeclaredConstructor()
    ctor.isAccessible = true
    val event = ctor.newInstance()
    setField(event, "mPackage", packageName)
    setField(event, "mTimeStamp", timeStamp)
    setField(event, "mEventType", eventType)
    return event
}

private fun setField(target: Any, name: String, value: Any?) {
    val field = target.javaClass.getDeclaredField(name)
    field.isAccessible = true
    field.set(target, value)
}

// --- small time helpers so tests read naturally ---

fun hours(n: Int): Long = n * 60L * 60L * 1000L
fun minutes(n: Int): Long = n * 60L * 1000L
