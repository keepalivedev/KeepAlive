package io.keepalive.android

import android.Manifest
import android.app.AlarmManager
import androidx.appcompat.app.AlertDialog
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class PermissionManager(
    private val context: Context,
    private val activity: AppCompatActivity?,

    // for a pass over the missing permissions (checkHavePermissions): called when the user
    //  accepts an explanation and is sent off to the system dialog or a settings page, so
    //  the caller can carry on with the next permission when they return
    private val onRequestLaunched: (() -> Unit)? = null,

    // and called when the user cancels an explanation, so the caller can carry on at once
    private val onRequestCancelled: (() -> Unit)? = null,

    // what the pass has asked for so far: basic permissions and the names of special
    //  checks. the caller can save it and hand it to a new instance, so that the pass
    //  survives the activity being recreated
    val asked: MutableSet<String> = mutableSetOf()
) {

    private val tag = this.javaClass.name

    private var locationEnabled = false
    private var callPhoneEnabled = false
    private var overlayPromptEnabled = false

    // start with no permissions
    private val basicPermissions = mutableListOf<String>()

    // the special permissions, in the order they are asked for
    private val specialChecks: List<Pair<String, (Boolean) -> Boolean>> = listOf(
        "usage_access" to ::checkUsageStatsPermissions,
        "exact_alarms" to ::checkScheduleExactAlarmPermissions,
        "background_location" to ::checkBackgroundLocationPermissions,
        "overlay" to ::checkOverlayPermissions,
        "full_screen_intent" to ::checkFullScreenIntentPermissions
    )

    // map of a permission to the title and description to use in the explanation dialog
    private val permissionExplanations = mutableMapOf(
        Manifest.permission.SEND_SMS to arrayOf(
            context.getString(R.string.permission_send_sms_title),
            context.getString(R.string.permission_send_sms_description)
        ),
        Manifest.permission.CALL_PHONE to arrayOf(
            context.getString(R.string.permission_call_phone_title),
            context.getString(R.string.permission_call_phone_description)
        ),
        Manifest.permission.READ_PHONE_STATE to arrayOf(
            context.getString(R.string.permission_read_phone_state_title),
            context.getString(R.string.permission_read_phone_state_description)
        ),
        Settings.ACTION_USAGE_ACCESS_SETTINGS to arrayOf(
            context.getString(R.string.permission_usage_access_title),
            context.getString(R.string.permission_usage_access_description)
        ),
        Manifest.permission.ACCESS_FINE_LOCATION to arrayOf(
            context.getString(R.string.permission_access_fine_location_title),
            context.getString(R.string.permission_access_fine_location_description)
        )
    )

    init {

        // get the preferences and check for SMS contacts, whether location is enabled and
        //  if there is a call phone number
        val sharedPrefs = getAppSharedPreferences(context)

        val smsContacts: MutableList<SMSEmergencyContactSetting> = loadJSONSharedPreference(sharedPrefs,
            "PHONE_NUMBER_SETTINGS")

        // only request SMS permissions if there is at least one SMS contact
        if (smsContacts.isNotEmpty()) {
            basicPermissions.add(Manifest.permission.SEND_SMS)

            // only request read phone state permissions if we are on android O as there is a bug
            //  that requires it in order to send SMS
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
                basicPermissions.add(Manifest.permission.READ_PHONE_STATE)
            }
        }

        // if location is enabled for an SMS contact or for the webhook
        locationEnabled = sharedPrefs.getBoolean(PrefKeys.LOCATION_ENABLED, false) || sharedPrefs.getBoolean(PrefKeys.WEBHOOK_LOCATION_ENABLED,false)

        // only request location if its enabled
        if (locationEnabled) {
            basicPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // only request phone permissions if we have a contact phone number set
        if (sharedPrefs.getString(PrefKeys.CONTACT_PHONE, "") != "") {
            callPhoneEnabled = true
            basicPermissions.add(Manifest.permission.CALL_PHONE)
        }

        // also need overlay permission if the user has opted in to the full-screen
        //  are-you-there dialog (independent of the call-phone path).
        // default false here is deliberate: we only want to require the permission
        //  when we know the user wants the feature on. AppController.onCreate writes
        //  the explicit true value on every app start, so by the time PermissionManager
        //  is constructed in any realistic flow the value will be present.
        overlayPromptEnabled = sharedPrefs.getBoolean(PrefKeys.ARE_YOU_THERE_OVERLAY_ENABLED, false)

        // overlay permissions added in API 23
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissionExplanations[Settings.ACTION_MANAGE_OVERLAY_PERMISSION] = arrayOf(
                context.getString(R.string.permission_manage_overlay_title),
                context.getString(R.string.permission_manage_overlay_description)
            )
        }

        // need to request notification permissions starting in API 33
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionExplanations[Manifest.permission.POST_NOTIFICATIONS] = arrayOf(
                context.getString(R.string.permission_post_notifications_title),
                context.getString(R.string.permission_post_notifications_description)
            )
            basicPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // need to request background location permissions starting in API 29
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionExplanations[Manifest.permission.ACCESS_BACKGROUND_LOCATION] = arrayOf(
                context.getString(R.string.permission_access_background_location_title),
                context.getString(R.string.permission_access_background_location_description)
            )
        }

        // need to request schedule exact alarm permissions starting in API 31
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionExplanations[Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM] = arrayOf(
                context.getString(R.string.permission_schedule_alarms_title),
                context.getString(R.string.permission_schedule_alarms_description)
            )
        }

        // full-screen intent special access added in API 34. the Play Store
        //  revokes the default grant at install for apps without an approved
        //  calling/alarm core function, so the user may need to enable it
        //  manually; sideloaded/F-Droid installs keep the default grant
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissionExplanations[Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT] = arrayOf(
                context.getString(R.string.permission_full_screen_intent_title),
                context.getString(R.string.permission_full_screen_intent_description)
            )
        }
    }

    // check whether we need any permissions without actually requesting them
    // will check permissions one by one and return true as soon as it finds one that is needed
    fun checkNeedAnyPermissions(): Boolean {

        // for the basic permissions
        for (permission in basicPermissions) {

            // if we don't have the permission then return true
            if (ContextCompat.checkSelfPermission(
                    context,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }
        }

        // check the special permissions, each returns false if permissions are needed
        //  so invert that so as soon as we find one that needs permissions we return true
        if (!checkUsageStatsPermissions(false) ||
            !checkScheduleExactAlarmPermissions(false) ||
            !checkBackgroundLocationPermissions(false) ||
            !checkOverlayPermissions(false) ||
            !checkFullScreenIntentPermissions(false)
        ) {
            DebugLogger.d(tag, context.getString(R.string.debug_log_still_need_some_permissions))
            return true
        }

        DebugLogger.d(tag, context.getString(R.string.debug_log_have_all_permissions))
        // if we haven't returned true yet then we don't need any permissions
        return false
    }

    // returns true if we have all of the permissions. otherwise asks for the next missing
    //  one that this instance hasn't asked for yet. only one per call: dialogs on top of
    //  each other launch their requests together and Android drops all but one. call it
    //  again on the same instance when the user comes back, and one pass asks for each
    //  missing permission once, so a permission the user won't grant doesn't hold up the rest
    fun checkHavePermissions(): Boolean {

        for (permission in basicPermissions) {
            if (permission !in asked && ContextCompat.checkSelfPermission(
                    context,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                asked.add(permission)

                // launch a dialog to explain the permission and then request it
                explainPermission(
                    permission,
                    permissionExplanations[permission]!![0],
                    permissionExplanations[permission]!![1]
                )
                return false
            }
        }

        for ((name, check) in specialChecks) {
            if (name !in asked && !check(true)) {
                asked.add(name)
                DebugLogger.d(tag, context.getString(R.string.debug_log_finished_requesting_permissions))
                return false
            }
        }

        return !checkNeedAnyPermissions()
    }

    // check and request a single permission
    fun checkRequestSinglePermission(permission: String): Boolean {
        Log.d(tag, "Checking permissions for $permission")

        // if we don't have location permissions then request them
        if (ContextCompat.checkSelfPermission(
                context,
                permission
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.d(tag, "Requesting permission: $permission")

            explainPermission(
                permission,
                permissionExplanations[permission]!![0],
                permissionExplanations[permission]!![1]
            )

            // return false because we haven't actually granted the permission at this point
            return false

        } else {
            return true
        }
    }

    private fun explainPermission(permission: String, title: String, explanation: String) {
        Log.d(tag, "Creating dialog for permission $permission")

        // create a dialog to explain the permission we are going to request
        AlertDialog.Builder(context, R.style.AlertDialogTheme)
            .setTitle(title)
            .setMessage(explanation)
            .setPositiveButton(context.getString(R.string.ok)) { _, _ ->

                // request the permission
                Log.d(tag, "Requesting permission: $permission")
                onRequestLaunched?.invoke()
                activity?.let {
                    ActivityCompat.requestPermissions(it, arrayOf(permission), 0)
                } ?: Log.e(tag, "Activity reference is null, cannot request $permission")
            }
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->
                onRequestCancelled?.invoke()
            }
            .show()
    }

    private fun explainSettingsPermission(permission: String, title: String, explanation: String) {
        Log.d(tag, "Creating dialog for setting permission $permission")

        // create a dialog to explain the permission we are going to request
        AlertDialog.Builder(context, R.style.AlertDialogTheme)
            .setTitle(title)
            .setMessage(explanation)
            .setPositiveButton(context.getString(R.string.go_to_settings)) { _, _ ->

                // request the permission by taking the user to the settings page
                Log.d(tag, "Requesting setting permission: $permission")
                onRequestLaunched?.invoke()
                val myIntent = Intent(permission)

                // the full-screen intent settings action expects a package URI
                //  so it opens directly on this app's toggle
                if (permission == Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) {
                    myIntent.data = Uri.fromParts("package", context.packageName, null)
                }

                activity?.startActivity(myIntent) ?: run {
                    myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(myIntent)
                }
            }
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->
                onRequestCancelled?.invoke()
            }
            .show()
    }

    // Handle the result of a runtime permission request (call from the hosting
    //  Activity's onRequestPermissionsResult). Once a permission is permanently
    //  denied - "Don't allow" twice, or set to "Not allowed" in system settings -
    //  requestPermissions() no longer shows the system dialog and silently
    //  returns denied (the framework logs "No requestable permission in the
    //  request."), so the explain-then-request path dead-ends. In that case send
    //  the user to the app's settings page where they can re-enable it. Returns
    //  true if it showed that dialog.
    fun handlePermissionResult(permissions: Array<out String>, grantResults: IntArray): Boolean {
        val act = activity ?: return false
        val permanentlyDenied = firstPermanentlyDeniedPermission(
            permissions,
            grantResults,
            shouldShowRationale = { ActivityCompat.shouldShowRequestPermissionRationale(act, it) },
            isKnown = { permissionExplanations.containsKey(it) }
        ) ?: return false

        explainPermanentlyDeniedPermission(permanentlyDenied)
        return true
    }

    private fun explainPermanentlyDeniedPermission(permission: String) {
        val title = permissionExplanations[permission]?.get(0) ?: return
        Log.d(tag, "Permission $permission is permanently denied, routing to app settings")

        AlertDialog.Builder(context, R.style.AlertDialogTheme)
            .setTitle(title)
            .setMessage(context.getString(R.string.permission_denied_go_to_settings_message))
            .setPositiveButton(context.getString(R.string.go_to_settings)) { _, _ ->

                // the system request dialog won't appear anymore, so open the
                //  app's details page where the permission can be re-enabled
                onRequestLaunched?.invoke()
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null)
                )
                activity?.startActivity(intent) ?: run {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
            .setNegativeButton(context.getString(R.string.cancel)) { _, _ ->
                onRequestCancelled?.invoke()
            }
            .show()
    }

    private fun checkBackgroundLocationPermissions(requestPermissions: Boolean): Boolean {

        Log.d(tag, "Checking background location permissions")

        // ACCESS_BACKGROUND_LOCATION permissions added in API v29
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(
                tag,
                "Current build is ${Build.VERSION.SDK_INT}, don't need background location permissions"
            )
            return true
        }

        // if location is not enabled then don't check location permissions
        if (!locationEnabled) {
            Log.d(tag, "Location is disabled.")
            return true
        }

        // background location permissions are slightly different in that we first need
        //  ACCESS_FINE_LOCATION permissions before we can request background location permissions

        // if we have fine location
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {

            // but don't yet have background access, request it
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                if (!requestPermissions) {
                    return false
                }

                Log.d(tag, "Requesting background location permission")

                explainPermission(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    permissionExplanations[Manifest.permission.ACCESS_BACKGROUND_LOCATION]!![0],
                    permissionExplanations[Manifest.permission.ACCESS_BACKGROUND_LOCATION]!![1]
                )

                // return false because we haven't actually granted the permission at this point
                return false

            } else {
                return true
            }
        } else {
            // fine location comes first, so there is nothing to ask for here. when
            //  requesting, say so by returning true, which lets a pass over the missing
            //  permissions move on to the next one instead of stopping without a dialog
            Log.d(tag, "Don't have fine location permissions?!")
            return requestPermissions
        }
    }

    private fun checkFullScreenIntentPermissions(requestPermissions: Boolean): Boolean {

        // only needed when the user has the full-screen prompt enabled; the
        //  special access exists from API 34 and is granted at install below that
        if (!overlayPromptEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }

        Log.d(tag, "Checking full-screen intent permissions")

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

        if (notificationManager != null && !notificationManager.canUseFullScreenIntent()) {

            if (!requestPermissions) {
                return false
            }

            explainSettingsPermission(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                permissionExplanations[Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT]!![0],
                permissionExplanations[Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT]!![1]
            )

            // return false because we haven't actually granted the permission at this point
            return false
        } else {
            Log.d(tag, "Full-screen intent permission already granted!")
            return true
        }
    }

    private fun checkOverlayPermissions(requestPermissions: Boolean): Boolean {

        // we need overlay perm if EITHER the call-phone path or the
        //  user-opted-in are-you-there overlay path requires it
        if (!callPhoneEnabled && !overlayPromptEnabled) {
            return true
        }

        Log.d(tag, "Checking overlay permissions")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {

            if (!requestPermissions) {
                return false
            }

            explainSettingsPermission(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                permissionExplanations[Settings.ACTION_MANAGE_OVERLAY_PERMISSION]!![0],
                permissionExplanations[Settings.ACTION_MANAGE_OVERLAY_PERMISSION]!![1]
            )

            // return false because we haven't actually granted the permission at this point
            return false

        } else {
            Log.d(tag, "Overlay permissions already granted!")
            return true
        }
    }

    private fun checkScheduleExactAlarmPermissions(requestPermissions: Boolean): Boolean {

        // request SCHEDULE_EXACT_ALARM if this is API 31 or greater
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(tag, "Checking schedule exact alarm permissions")

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

            Log.d(tag, "Alarm manager: $alarmManager")
            Log.d(tag, "Can schedule exact alarms: ${alarmManager?.canScheduleExactAlarms()}")

            // canScheduleExactAlarms only available on API 31+ and if targeting below that then we
            //  dont need to request permissions
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {

                if (!requestPermissions) {
                    return false
                }
                //explainPermission()
                explainSettingsPermission(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    permissionExplanations[Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM]!![0],
                    permissionExplanations[Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM]!![1]
                )
                // return false because we haven't actually granted the permission at this point
                return false
            } else {
                return true
            }
        } else {
            return true
        }
    }

    fun checkUsageStatsPermissions(requestPermissions: Boolean): Boolean {
        Log.d(tag, "checking usage stats permissions")
        val opsMan = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        // checkOpNoThrow works across all supported API levels; unsafeCheckOpNoThrow was
        // deprecated in API 36 in favor of checkOpNoThrow (which is no longer deprecated)
        val opsPermission = opsMan.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            context.applicationInfo.uid,
            context.applicationInfo.packageName
        )

        if (opsPermission != AppOpsManager.MODE_ALLOWED) {
            Log.d(tag, "Do not have Usage stats permissions!")

            if (!requestPermissions) {
                return false
            }

            explainSettingsPermission(
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
                permissionExplanations[Settings.ACTION_USAGE_ACCESS_SETTINGS]!![0],
                permissionExplanations[Settings.ACTION_USAGE_ACCESS_SETTINGS]!![1]
            )

            // return false because we haven't actually granted the permission at this point
            return false

        } else {
            Log.d(tag, "Usage stats permissions already granted!")
            return true
        }
    }
}

/**
 * The first requested permission that came back denied and can no longer be
 * requested through the system dialog — i.e. permanently denied. In the result
 * callback, "denied AND no rationale" is the permanent-denial signature: a
 * first-time or soft-denied permission would have shown the system dialog
 * (granting, or making [shouldShowRationale] true), so reaching the callback
 * denied without rationale means requestPermissions() no-op'd and the user must
 * go to settings. Returns null when nothing needs that handling (e.g. all
 * granted, or an empty/cancelled result). [isKnown] limits handling to
 * permissions we actually explain.
 */
internal fun firstPermanentlyDeniedPermission(
    permissions: Array<out String>,
    grantResults: IntArray,
    shouldShowRationale: (String) -> Boolean,
    isKnown: (String) -> Boolean
): String? {
    for (i in permissions.indices) {
        if (i < grantResults.size &&
            grantResults[i] == PackageManager.PERMISSION_DENIED &&
            !shouldShowRationale(permissions[i]) &&
            isKnown(permissions[i])
        ) {
            return permissions[i]
        }
    }
    return null
}
