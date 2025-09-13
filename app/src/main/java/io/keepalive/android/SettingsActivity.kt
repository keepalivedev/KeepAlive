package io.keepalive.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale


class SettingsActivity : AppCompatActivity() {

    private lateinit var phoneNumberAdapter: PhoneNumberAdapter
    private lateinit var recyclerView: RecyclerView
    private val phoneNumberList = mutableListOf<SMSEmergencyContactSetting>()
    private var sharedPrefs: SharedPreferences? = null
    private val gson = Gson()
    private var mToast: Toast? = null

    private var alertMessageValid = true
    private var contactSMSPhoneValid = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // load settings and initialize the adapter that will be used to store and interact with
        //  the SMS contact number settings
        sharedPrefs = getEncryptedSharedPreferences(this)
        phoneNumberList.addAll(loadJSONSharedPreference(sharedPrefs!!,"PHONE_NUMBER_SETTINGS"))
        phoneNumberAdapter = PhoneNumberAdapter(phoneNumberList, sharedPrefs!!, ::editPhoneNumber)

        // initialize the recycler view and link it to the adapter
        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = phoneNumberAdapter

        // set the text views based on the current preference values
        updateTextViewsFromPreferences()

        // add on click listeners to the setting rows and buttons
        addOnClickListeners()
    }

    override fun onDestroy() {
        Log.d("SettingsActivity", "onDestroy")
        super.onDestroy()
    }

    private fun addOnClickListeners() {

        // listener for the Add SMS Emergency Contact button
        val addButton: Button = findViewById(R.id.addButton)
        addButton.setOnClickListener { showAddOrEditSMSContactDialog() }

        // listener for the enabled switch
        val monitoringEnabledSwitch: SwitchCompat = findViewById(R.id.monitoringEnabledSwitch)
        monitoringEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->

            // if the device doesn't support device lock/unlock monitoring and hasn't configured
            //  any apps to monitor and the user is trying to enable monitoring then show a dialog
            //  to let them know that they need to configure the apps first
            if (Build.VERSION.SDK_INT < AppController.MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK &&
                sharedPrefs!!.getString("APPS_TO_MONITOR", "[]") == "[]" && isChecked) {

                // force it back to false
                monitoringEnabledSwitch.isChecked = false

                Log.d(
                    "processSettingChange", "API level is too low, monitored apps" +
                            " must be configured before monitoring can be enabled"
                )

                // show a dialog to explain
                AlertDialog.Builder(this, R.style.AlertDialogTheme)
                    .setTitle(getString(R.string.monitored_apps_not_configured_title))
                    .setMessage(getString(R.string.monitored_apps_not_configured_message))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()

            } else {

                // no dialog for the switch, just save the new value and process the change
                with(sharedPrefs!!.edit()) {
                    putBoolean("enabled", isChecked)
                    apply()
                }
                processSettingChange("enabled")
            }
        }

        // listener for the auto restart monitoring switch
        val restartMonitoringSwitch: SwitchCompat = findViewById(R.id.restartMonitoringSwitch)
        restartMonitoringSwitch.setOnCheckedChangeListener { _, isChecked ->

            // no dialog for the switch, just save the new value
            with(sharedPrefs!!.edit()) {
                putBoolean("auto_restart_monitoring", isChecked)
                apply()
            }
        }

        // set up listeners for each setting row so that the user can click
        //  anywhere on the row itself to bring up the edit dialog

        val timePeriodRowLayout: LinearLayout = findViewById(R.id.timePeriodRow)
        timePeriodRowLayout.setOnClickListener {
            showEditSettingDialog("time_period_hours")
        }

        val followupPeriodRowLayout: LinearLayout = findViewById(R.id.followupPeriodRow)
        followupPeriodRowLayout.setOnClickListener {
            showEditSettingDialog("followup_time_period_minutes")
        }

        val callPhoneRowLayout: LinearLayout = findViewById(R.id.callPhoneRow)
        callPhoneRowLayout.setOnClickListener {
            showEditSettingDialog("contact_phone")
        }

        val restPeriodRowLayout: LinearLayout = findViewById(R.id.restPeriodRow)
        restPeriodRowLayout.setOnClickListener {
            showEditRestPeriodDialog()
        }

        val alertWebhookRowLayout: LinearLayout = findViewById(R.id.alertWebhookRow)

        // only show the webhook row if this build includes webhook support
        if (BuildConfig.INCLUDE_WEBHOOK) {
            alertWebhookRowLayout.setOnClickListener {
                val webhookConfigManager = WebhookConfigManager(this, this)

                // show the dialog and then update the text views after it is closed
                webhookConfigManager.showWebhookConfigDialog(::updateTextViewsFromPreferences)
            }
        } else {
            alertWebhookRowLayout.visibility = View.GONE
        }

        val monitoredAppsRowLayout: LinearLayout = findViewById(R.id.monitoredAppsRow)
        monitoredAppsRowLayout.setOnClickListener {

            // we can't check the monitored apps if we don't have usage stats permissions yet
            //  so make sure we have those and, if not, show a dialog to let the user know
            val haveUsageStatsPerms = PermissionManager(this, this).checkUsageStatsPermissions(false)

            if(!haveUsageStatsPerms) {
                Log.d("monitoredAppsRowLayout", "No usage stats permissions, letting user know")

                AlertDialog.Builder(this, R.style.AlertDialogTheme)
                    .setTitle(getString(R.string.monitored_apps_no_usage_stats_permissions_dialog_title))
                    .setMessage(getString(R.string.monitored_apps_no_usage_stats_permissions_dialog_message))
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()

                // stop processing
                return@setOnClickListener
            }

            // if no apps are configured yet then show a warning dialog to explain that
            //  this feature is still in beta testing
            if (sharedPrefs!!.getString("APPS_TO_MONITOR", "[]") == "[]") {

                var dialogMsg = getString(R.string.monitored_apps_warning_dialog_message)

                val dialog = AlertDialog.Builder(this, R.style.AlertDialogTheme)
                    .setTitle(getString(R.string.monitored_apps_warning_dialog_title))
                    .setPositiveButton(getString(R.string.ok)) { _, _ ->

                        // pass in the updateTextViews functions so we can call it after the dialog window is closed
                        AppsSelectionDialogFragment(::updateTextViewsFromPreferences).show(
                            supportFragmentManager,
                            "appsSelectionDialog"
                        )
                    }

                // if we are able to use device lock/unlock events then explain to the user that that
                //  is the preferred method of monitoring and add a button to go back instead
                //  of proceeding to the app selection dialog
                if (Build.VERSION.SDK_INT >= AppController.MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK) {
                    dialogMsg += getString(R.string.monitored_apps_warning_dialog_message_alt)
                    dialog.setNeutralButton(getString(R.string.back), null)
                }

                dialog.setMessage(dialogMsg)
                dialog.show()

            } else {
                // if the user has already configured apps then just show the app selection dialog
                AppsSelectionDialogFragment(::updateTextViewsFromPreferences).show(
                    supportFragmentManager,
                    "appsSelectionDialog"
                )
            }
        }
    }

    private fun updateTextViewsFromPreferences() {

        // update the main settings text views based on the current preference values

        val monitoringEnabledSwitch: SwitchCompat = findViewById(R.id.monitoringEnabledSwitch)
        monitoringEnabledSwitch.isChecked = sharedPrefs!!.getBoolean("enabled", false)

        val restartMonitoringSwitch: SwitchCompat = findViewById(R.id.restartMonitoringSwitch)
        restartMonitoringSwitch.isChecked = sharedPrefs!!.getBoolean("auto_restart_monitoring", false)

        val timePeriodValueTextView: TextView = findViewById(R.id.edit_time_period_hours)
        timePeriodValueTextView.text = sharedPrefs!!.getString("time_period_hours", "12")

        val monitoredAppsValueTextView: TextView = findViewById(R.id.edit_monitored_apps)
        val appsToMonitor: MutableList<MonitoredAppDetails> = loadJSONSharedPreference(
            sharedPrefs!!,"APPS_TO_MONITOR")

        var monitoredAppsValueText: String

        // if there are apps to monitor configured then display them, otherwise display the default message
        if (appsToMonitor.isNotEmpty()) {

            // only show the first 3 as there may not be a lot of space
            monitoredAppsValueText = appsToMonitor.take(3).joinToString(", ") { it.appName }

            // add an ellipse to indicate there is more that isn't displayed
            if (appsToMonitor.size > 3) {
                monitoredAppsValueText += "..."
            }
        } else {
            // if this is API 29 or higher then this is the default behavior
            monitoredAppsValueText = if (Build.VERSION.SDK_INT >= AppController.MIN_API_LEVEL_FOR_DEVICE_LOCK_UNLOCK) {
                "Device Lock/Unlock"
            } else {
                // if nothing is configured and this is < API 29 then the user has to configure
                //  them before the app will work
                "Not Configured"
            }
        }
        monitoredAppsValueTextView.text = monitoredAppsValueText

        val followupPeriodValueTextView: TextView =
            findViewById(R.id.edit_followup_time_period_minutes)
        followupPeriodValueTextView.text =
            sharedPrefs!!.getString("followup_time_period_minutes", "60")

        // format the phone number for display
        val callPhoneValueTextView: TextView = findViewById(R.id.edit_contact_phone)
        callPhoneValueTextView.text = PhoneNumberUtils.formatNumber(
            sharedPrefs!!.getString("contact_phone", ""),
            Locale.getDefault().country
        )

        // format the rest period for display
        val restPeriodValueTextView: TextView = findViewById(R.id.edit_rest_period)

        val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(sharedPrefs!!,
            "REST_PERIODS")

        if (restPeriods.isNotEmpty()) {
            restPeriodValueTextView.text = String.format(
                Locale.getDefault(),
                "%02d:%02d - %02d:%02d %s",
                restPeriods[0].startHour,
                restPeriods[0].startMinute,
                restPeriods[0].endHour,
                restPeriods[0].endMinute,
                SimpleDateFormat("z", Locale.getDefault()).format(Calendar.getInstance().time)
            )

        } else {
            restPeriodValueTextView.text = getString(R.string.rest_period_not_set_message)
        }

        // update the alert webhook settings if enabled
        if (BuildConfig.INCLUDE_WEBHOOK) {
            val alertWebhookValueTextView: TextView = findViewById(R.id.edit_webhook)

            // make sure the webhook url isn't blank and limit the # of characters
            val webhookUrl = sharedPrefs!!.getString("webhook_url", "")!!

            // if configured, limit the displayed webhook to 150 characters (arbitrary...)
            // otherwise show a 'Not Configured' message
            val webhookUrlDisplay = if (webhookUrl.length > 150) {
                webhookUrl.substring(0, 150) + "..."
            } else if (webhookUrl.isEmpty()) {
                this.getString(R.string.webhook_not_configured)
            } else {
                webhookUrl
            }
            alertWebhookValueTextView.text = webhookUrlDisplay
        }
    }

    private fun processSettingChange(preferenceKey: String) {

        var updateAlarm = false

        // if the app has been enabled/disabled then we need to update the alarm
        if (preferenceKey == "enabled") {

            val newValue = sharedPrefs!!.getBoolean(preferenceKey, false)
            if (newValue) {
                Log.d("processSettingChange", "App has been enabled, need to set alarm")
                updateAlarm = true

            } else {

                // if the app has been disabled then cancel the alarm and work manager job
                Log.d("processSettingChange", "App has been disabled, canceling alarm")

                cancelAlarm(this)
            }
        }
        // if we change the time period hours then we need to update the alarm
        else if (preferenceKey == "time_period_hours" || preferenceKey == "REST_PERIODS") {

            // make sure the app is actually enabled
            if (sharedPrefs!!.getBoolean("enabled", false)) {
                updateAlarm = true
            }
        }

        // if we need to update the alarm
        if (updateAlarm) {
            val newValue = sharedPrefs!!.getString("time_period_hours", "12")?.toFloatOrNull() ?: 12f

            Log.d(
                "processSettingChange",
                "Check period updated, need to re-set alarm to $newValue hours"
            )

            val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(sharedPrefs!!,"REST_PERIODS")

            // don't need to cancel the existing alarm, just set a new one
            setAlarm(this, System.currentTimeMillis(), (newValue * 60).toInt(), "periodic", restPeriods)
        }
    }

    private fun showEditSettingDialog(preferenceKey: String) {
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_edit_settings, null)
        val dialogEditText: EditText = dialogView.findViewById(R.id.customDialogEditText)
        val dialogDescription: TextView = dialogView.findViewById(R.id.customDialogTextView)
        val exactAlarmSwitch: SwitchCompat = dialogView.findViewById(R.id.exactAlarmSwitch)
        val exactAlarmNoteTextView: TextView = dialogView.findViewById(R.id.exactAlarmNoteTextView)
        exactAlarmSwitch.visibility = View.GONE
        exactAlarmNoteTextView.visibility = View.GONE

        // configure the dialog based on which setting this is
        var dialogTitle = ""

        // for the call phone number, show a delete button to make it easier to clear the setting
        var showDeleteButton = false

        // customize the dialog based on which setting is being edited
        when (preferenceKey) {

            "time_period_hours" -> {
                dialogTitle = getString(R.string.time_period_title)
                dialogEditText.hint = getString(R.string.time_period_title)
                dialogDescription.text = getString(R.string.time_period_description)
                dialogEditText.inputType =
                    EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_DECIMAL
                dialogEditText.setText(sharedPrefs!!.getString("time_period_hours", "12"))

                // show the exact alarm switch and explanatory note for this setting
                exactAlarmSwitch.visibility = View.VISIBLE
                exactAlarmSwitch.isChecked = sharedPrefs!!.getBoolean("use_exact_alarms", false)
                exactAlarmNoteTextView.visibility = View.VISIBLE
            }

            "followup_time_period_minutes" -> {
                dialogTitle = getString(R.string.followup_time_period_title)
                dialogEditText.hint = getString(R.string.followup_time_period_title)
                dialogDescription.text = getString(R.string.followup_time_period_description)
                dialogEditText.inputType =
                    EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_DECIMAL
                dialogEditText.setText(
                    sharedPrefs!!.getString(
                        "followup_time_period_minutes",
                        "60"
                    )
                )
            }

            "contact_phone" -> {
                dialogTitle = getString(R.string.contact_phone_title)
                dialogEditText.hint = getString(R.string.contact_phone_title)
                dialogDescription.text = getString(R.string.contact_phone_description)
                dialogEditText.inputType = EditorInfo.TYPE_CLASS_PHONE
                dialogEditText.setText(sharedPrefs!!.getString("contact_phone", ""))
                showDeleteButton = true
            }
        }

        // build the dialog that will be used to edit the setting
        //val dialog = AlertDialog.Builder(this)
        val dialog = AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(dialogTitle)
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->

                // all of the settings we are editing in a dialog are strings
                with(sharedPrefs!!.edit()) {
                    putString(preferenceKey, dialogEditText.text.toString())
                    if (preferenceKey == "time_period_hours") {
                        putBoolean("use_exact_alarms", exactAlarmSwitch.isChecked)
                    }
                    apply()
                }

                // take action depending on what preference is changing
                processSettingChange(preferenceKey)

                // update the text views to reflect the new values
                updateTextViewsFromPreferences()
            }
            .setNegativeButton(getString(R.string.cancel), null)

        if (showDeleteButton) {
            dialog.setNeutralButton(getString(R.string.delete)) { _, _ ->

                // if the user deletes the phone number then remove it from shared prefs
                with(sharedPrefs!!.edit()) {
                    remove(preferenceKey)
                    apply()
                }

                // processSettingChange(preferenceKey)
                updateTextViewsFromPreferences()
            }
        }

        // show the dialog
        val shownDialog = dialog.show()

        // omg it works... focus the edit text and make the keyboard appear
        dialogEditText.requestFocus()
        if (dialogEditText.requestFocus()) {
            shownDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }

        // add a text watcher to the edit text so that we can enable/disable the submit button
        val positiveButton = shownDialog.getButton(AlertDialog.BUTTON_POSITIVE)
        dialogEditText.addTextChangedListener(InputTextWatcher(positiveButton, this, preferenceKey, dialogEditText))
    }

    private fun showEditRestPeriodDialog() {
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_edit_rest_period, null)
        val dialogStartTimePicker: TimePicker = dialogView.findViewById(R.id.startTimePicker)
        val dialogEndTimePicker: TimePicker = dialogView.findViewById(R.id.endTimePicker)
        val restPeriodTimeZoneMessageTextView: TextView = dialogView.findViewById(R.id.restPeriodTimeZoneMessageTextView)

        val restPeriodTextStr = String.format(
            getString(R.string.rest_period_dialog_time_zone_message),

            // this should show up like 'CST' or 'PST'
            SimpleDateFormat("z", Locale.getDefault()).format(Calendar.getInstance().time)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            restPeriodTimeZoneMessageTextView.text = Html.fromHtml(
                restPeriodTextStr,
                Html.FROM_HTML_MODE_LEGACY
            )
        } else {
            @Suppress("DEPRECATION")
            restPeriodTimeZoneMessageTextView.text = Html.fromHtml(restPeriodTextStr)
        }

        // set the time pickers to 24 hour mode
        dialogStartTimePicker.setIs24HourView(true)
        dialogEndTimePicker.setIs24HourView(true)

        val currentRestPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(sharedPrefs!!,
            "REST_PERIODS")

        // if there is a rest period then set the time pickers to the current values
        if (currentRestPeriods.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Log.d("showEditRestPeriodDlg", "currentRestPeriods: $currentRestPeriods")
                dialogStartTimePicker.hour = currentRestPeriods[0].startHour
                dialogStartTimePicker.minute = currentRestPeriods[0].startMinute
                dialogEndTimePicker.hour = currentRestPeriods[0].endHour
                dialogEndTimePicker.minute = currentRestPeriods[0].endMinute
            } else {
                @Suppress("DEPRECATION")
                dialogStartTimePicker.currentHour = currentRestPeriods[0].startHour
                @Suppress("DEPRECATION")
                dialogStartTimePicker.currentMinute = currentRestPeriods[0].startMinute
                @Suppress("DEPRECATION")
                dialogEndTimePicker.currentHour = currentRestPeriods[0].endHour
                @Suppress("DEPRECATION")
                dialogEndTimePicker.currentMinute = currentRestPeriods[0].endMinute
            }
        }

        val dialog = AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(getString(R.string.rest_period_dialog_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->

                // Declare variables to hold the start and end times
                val startHour: Int
                val startMinute: Int
                val endHour: Int
                val endMinute: Int

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startHour = dialogStartTimePicker.hour
                    startMinute = dialogStartTimePicker.minute
                    endHour = dialogEndTimePicker.hour
                    endMinute = dialogEndTimePicker.minute
                } else {
                    @Suppress("DEPRECATION")
                    startHour = dialogStartTimePicker.currentHour
                    @Suppress("DEPRECATION")
                    startMinute = dialogStartTimePicker.currentMinute
                    @Suppress("DEPRECATION")
                    endHour = dialogEndTimePicker.currentHour
                    @Suppress("DEPRECATION")
                    endMinute = dialogEndTimePicker.currentMinute
                }

                // if the start and end times are the same then this is not a valid time range so
                //  show a toast and don't save the rest period
                if (startHour == endHour && startMinute == endMinute) {
                    showToast(getString(R.string.rest_period_invalid_range_message))
                    return@setPositiveButton
                }

                with(sharedPrefs!!.edit()) {

                    // create a new list of rest periods with just this one
                    val restPeriods = mutableListOf<RestPeriod>()
                    restPeriods.add(
                        RestPeriod(
                            startHour,
                            startMinute,
                            endHour,
                            endMinute
                        )
                    )

                    // convert the list to json and save it to shared prefs
                    val jsonString = gson.toJson(restPeriods)
                    putString("REST_PERIODS", jsonString)
                    apply()
                }

                // take action depending on what preference is changing
                processSettingChange("REST_PERIODS")

                // update the text views to reflect the new values
                updateTextViewsFromPreferences()
            }
            .setNeutralButton(getString(R.string.delete)) { _, _ ->

                // if the user deletes the rest period then remove it from shared prefs
                with(sharedPrefs!!.edit()) {
                    remove("REST_PERIODS")
                    apply()
                }

                processSettingChange("REST_PERIODS")
                updateTextViewsFromPreferences()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            fun updateSaveButtonState() {
                val sHour: Int
                val sMinute: Int
                val eHour: Int
                val eMinute: Int

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    sHour = dialogStartTimePicker.hour
                    sMinute = dialogStartTimePicker.minute
                    eHour = dialogEndTimePicker.hour
                    eMinute = dialogEndTimePicker.minute
                } else {
                    @Suppress("DEPRECATION")
                    sHour = dialogStartTimePicker.currentHour
                    @Suppress("DEPRECATION")
                    sMinute = dialogStartTimePicker.currentMinute
                    @Suppress("DEPRECATION")
                    eHour = dialogEndTimePicker.currentHour
                    @Suppress("DEPRECATION")
                    eMinute = dialogEndTimePicker.currentMinute
                }

                val enabled = !(sHour == eHour && sMinute == eMinute)
                saveButton.isEnabled = enabled
                val colorRes = if (enabled) R.color.primary else android.R.color.darker_gray
                saveButton.setTextColor(ContextCompat.getColor(this, colorRes))
            }

            dialogStartTimePicker.setOnTimeChangedListener { _, _, _ -> updateSaveButtonState() }
            dialogEndTimePicker.setOnTimeChangedListener { _, _, _ -> updateSaveButtonState() }

            updateSaveButtonState()
        }

        dialog.show()
    }

    private fun showAddOrEditSMSContactDialog(
        setting: SMSEmergencyContactSetting? = null,
        position: Int? = null
    ) {
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_add_edit_phone_number, null)
        val phoneNumberInput: EditText = dialogView.findViewById(R.id.phoneNumberInput)
        val alertMessageInput: EditText = dialogView.findViewById(R.id.alertMessageInput)
        val enabledSwitch: SwitchCompat = dialogView.findViewById(R.id.dialogEnabledSwitch)
        val locationSwitch: SwitchCompat = dialogView.findViewById(R.id.dialogLocationSwitch)

        // update the dialog based on whether this is a new setting or an existing one
        setting?.let {
            phoneNumberInput.setText(it.phoneNumber)
            alertMessageInput.setText(it.alertMessage)
            enabledSwitch.isChecked = it.isEnabled
            locationSwitch.isChecked = it.includeLocation
        }

        // if there aren't any contacts yet, give the user a default alert message
        if (phoneNumberAdapter.itemCount == 0) {
            alertMessageInput.setText(getString(R.string.default_alert_message))
        }

        // set the title and positive button text based on whether this is a new or existing setting
        var dialogTitle = getString(R.string.edit_emergency_contact_title)
        var positiveButtonText = getString(R.string.save)

        // if this is a new setting then default to enabled. this button is hidden on the dialog
        //  but will be saved when a new setting is added
        if (setting == null) {
            enabledSwitch.isChecked = true
            dialogTitle = getString(R.string.add_emergency_contact_title)
            positiveButtonText = getString(R.string.add)
        }

        // build the dialog
        val dialog = AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(dialogTitle)
            .setView(dialogView)
            .setPositiveButton(positiveButtonText) { _, _ ->

                // build a new SMSEmergencyContactSetting based on the dialog views
                val newSetting = SMSEmergencyContactSetting(
                    phoneNumber = phoneNumberInput.text.toString(),
                    alertMessage = alertMessageInput.text.toString(),
                    isEnabled = enabledSwitch.isChecked,
                    includeLocation = locationSwitch.isChecked
                )

                // if this is a new setting then add it to the list, otherwise edit the existing one
                if (position == null) {
                    phoneNumberAdapter.addPhoneNumber(newSetting)
                } else {
                    phoneNumberAdapter.editPhoneNumber(position, newSetting)
                }

                // save the new phone number list to shared prefs
                saveSMSEmergencyContactSettings(sharedPrefs, phoneNumberList, gson)
            }
            .setNegativeButton(getString(R.string.cancel), null)

        // if this is an existing setting then add a delete button
        if (setting != null) {
            dialog.setNeutralButton(getString(R.string.delete)) { _, _ ->
                position?.let { deletePhoneNumber(it) }
            }
        }

        // show the dialog
        val shownDialog = dialog.show()

        val positiveButton = shownDialog.getButton(AlertDialog.BUTTON_POSITIVE)

        // if this is a new contact or the existing phone number is blank (which shouldn't be able to happen?)
        //   then disable the submit button initially
        if (setting == null || setting.phoneNumber == "") {
            positiveButton.isEnabled = false
        }

        // add a text watcher for the alert message and phone number so we can disable the
        //  submit button if the input is invalid
        alertMessageInput.addTextChangedListener(
            InputTextWatcher(
                positiveButton,
                this,
                "alert_message",
                alertMessageInput
            )
        )
        phoneNumberInput.addTextChangedListener(
            InputTextWatcher(
                positiveButton,
                this,
                "contact_sms_phone",
                phoneNumberInput
            )
        )
    }

    fun showToast(toastText: String) {

        // if we already have a Toast up then cancel it
        mToast?.cancel()

        mToast = Toast.makeText(this, toastText, Toast.LENGTH_SHORT)
        mToast?.show()
    }

    // text watcher so we can validate the input and enable/disable the submit button
    inner class InputTextWatcher(
        private val submitButton: Button, private val context: Context,
        private val preferenceKey: String, private val editText: EditText
    ) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
        }

        override fun afterTextChanged(s: Editable?) {
            s ?: return

            when (preferenceKey) {
                "time_period_hours", "followup_time_period_minutes" -> handleTimeInput(s.toString())
                "contact_phone", "contact_sms_phone" -> handlePhoneInput(s.toString())
                "alert_message" -> handleAlertMessage(s)
            }

            updateSubmitButtonColor()
        }

        private fun handleTimeInput(value: String) {
            try {
                // try to convert the string to a float
                val timeValue = value.toFloat()

                val minutes = if (preferenceKey == "time_period_hours") {
                    timeValue * 60
                } else {
                    timeValue
                }

                // for either of these settings, make sure the value doesn't result
                //  in a minute value of less than 9 as we can't make alarms
                //  that frequently
                val valid = minutes >= AppController.ALARM_MINIMUM_TIME_PERIOD_MINUTES
                submitButton.isEnabled = valid

                if (!valid) {
                    showToast(context.getString(R.string.time_period_too_short_message))
                }
            } catch (_: Exception) {
                submitButton.isEnabled = false
            }
        }

        private fun handlePhoneInput(raw: String) {
            // remove everything except numbers and the plus sign
            //  no reason to allow #, *, comma, or anything else right?
            val sanitized = raw.filter { it.isDigit() || it == '+' }

            // update EditText only if necessary to avoid infinite loop
            if (sanitized != raw) {
                // update the text and set cursor to the end
                editText.setText(sanitized)
                editText.setSelection(sanitized.length)
            }

            // this just checks a regex of "[\\+]?[0-9.-]+"...
            val isNumber = PhoneNumberUtils.isGlobalPhoneNumber(sanitized)

            // SMS contact can't be blank but phone contact can be
            if (preferenceKey == "contact_sms_phone") {
                contactSMSPhoneValid = isNumber && sanitized.length > 1
                // if the phone number is valid and the alert message is valid then
                //  enable the button
                submitButton.isEnabled = contactSMSPhoneValid && alertMessageValid
                if (!contactSMSPhoneValid) {
                    showToast(context.getString(R.string.phone_number_invalid_message))
                }
            } else {

                // the phone contact number is simpler but make sure that it is at
                //  least 2 characters otherwise the formatting won't work
                // also let it be blank as a way for users to clear the setting
                val phoneValid = sanitized.isEmpty() || (isNumber && sanitized.length > 1)
                submitButton.isEnabled = phoneValid

                // if the phone number isn't valid then show a toast to let the user know
                //  that their input is invalid
                if (!phoneValid && sanitized.isNotEmpty()) {
                    showToast(context.getString(R.string.phone_number_invalid_message))
                }
            }
        }

        private fun handleAlertMessage(s: Editable) {

            // if this is the alert message then we want to make sure it
            //  not more than 160 characters or empty
            val validMessage = s.length <= AppController.SMS_MESSAGE_MAX_LENGTH && s.isNotEmpty()

            // if the current alert message is valid and so is the SMS phone number
            if (validMessage && contactSMSPhoneValid) {
                submitButton.isEnabled = true
            } else if (!validMessage) {
                submitButton.isEnabled = false
            }

            alertMessageValid = validMessage

            // if the length of the alert message is too long then show a toast
            if (s.length > AppController.SMS_MESSAGE_MAX_LENGTH) {
                showToast(context.getString(R.string.alarm_message_too_long_message))
            }
        }

        private fun updateSubmitButtonColor() {

            // regardless of the setting, if the submit button isn't enabled then
            //  change the text color to gray to indicate that
            val colorRes = if (submitButton.isEnabled) {
                R.color.primary
            } else {
                android.R.color.darker_gray
            }
            submitButton.setTextColor(ContextCompat.getColor(context, colorRes))
        }
    }

    private fun editPhoneNumber(position: Int) {
        showAddOrEditSMSContactDialog(phoneNumberList[position], position)
    }

    private fun deletePhoneNumber(position: Int) {

        // build a dialog to confirm that the user wants to delete the phone number
        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(getString(R.string.delete_phone_number_dialog_title))
            .setMessage(getString(R.string.delete_phone_number_dialog_description))
            .setPositiveButton(getString(R.string.yes)) { _, _ ->

                // delete the phone number from the list and save the new list to shared prefs
                phoneNumberAdapter.deletePhoneNumber(position)
                saveSMSEmergencyContactSettings(sharedPrefs, phoneNumberList, gson)
            }
            .setNegativeButton(getString(R.string.no), null)
            .show()
    }
}

