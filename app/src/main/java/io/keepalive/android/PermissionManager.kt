package io.keepalive.android

import android.Manifest
import android.app.AlarmManager
import androidx.appcompat.app.AlertDialog
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat


class PermissionManager(private val context: Context, private val activity: AppCompatActivity?) {

    private val tag = this.javaClass.name

    private var locationEnabled = false
    private var callPhoneEnabled = false

    // start with no permissions
    private val basicPermissions = mutableListOf<String>()

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
        val sharedPrefs = getEncryptedSharedPreferences(context)

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
        locationEnabled = sharedPrefs.getBoolean("location_enabled", false) || sharedPrefs.getBoolean("webhook_location_enabled",false)

        // only request location if its enabled
        if (locationEnabled) {
            basicPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        // only request phone permissions if we have a contact phone number set
        if (sharedPrefs.getString("contact_phone", "") != "") {
            callPhoneEnabled = true
            basicPermissions.add(Manifest.permission.CALL_PHONE)
        }

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
            !checkOverlayPermissions(false)
        ) {
            DebugLogger.d(tag, context.getString(R.string.debug_log_still_need_some_permissions))
            return true
        }

        DebugLogger.d(tag, context.getString(R.string.debug_log_have_all_permissions))
        // if we haven't returned true yet then we don't need any permissions
        return false
    }

    // returns true if we have all of the permissions
    fun checkHavePermissions(): Boolean {

        // check every permission and request it if needed using an explanation dialog
        for (permission in basicPermissions) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // launch a dialog to explain the permission and then request it
                explainPermission(
                    permission,
                    permissionExplanations[permission]!![0],
                    permissionExplanations[permission]!![1]
                )
            }
        }

        // this will only execute one of these at a time so that it doesn't pop up multiple dialogs
        //  at the same time
        return if (!checkUsageStatsPermissions(true) ||
            !checkScheduleExactAlarmPermissions(true) ||
            !checkBackgroundLocationPermissions(true) ||
            !checkOverlayPermissions(true)
        ) {
            DebugLogger.d(tag, context.getString(R.string.debug_log_finished_requesting_permissions))
            false
        } else {
            DebugLogger.d(tag, context.getString(R.string.debug_log_have_all_permissions))
            true
        }
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
                activity?.let {
                    ActivityCompat.requestPermissions(it, arrayOf(permission), 0)
                } ?: Log.e(tag, "Activity reference is null, cannot request $permission")
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
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
                val myIntent = Intent(permission)
                activity?.startActivity(myIntent) ?: run {
                    myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(myIntent)
                }
            }
            .setNegativeButton(context.getString(R.string.cancel), null)
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
            Log.d(tag, "Don't have fine location permissions?!")
            return false
        }
    }

    private fun checkOverlayPermissions(requestPermissions: Boolean): Boolean {

        // we only need this if the phone call is enabled
        if (!callPhoneEnabled) {
            return true

        } else {
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

        // the function used to check whether we have permissions was changed in API 29
        val opsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            opsMan.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                context.applicationInfo.uid,
                context.applicationInfo.packageName
            )
        } else {
            // suppress deprecation because we are only using this for API 28 and below
            @Suppress("DEPRECATION")
            opsMan.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                context.applicationInfo.uid,
                context.applicationInfo.packageName
            )
        }

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
