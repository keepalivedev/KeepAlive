package io.keepalive.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.telephony.PhoneNumberUtils
import android.text.Editable
import android.text.Html
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
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

            // no dialog for the switch, just save the new value and process the change
            with(sharedPrefs!!.edit()) {
                putBoolean("enabled", isChecked)
                apply()
            }
            processSettingChange("enabled")
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
    }

    private fun updateTextViewsFromPreferences() {

        // update the main settings text views based on the current preference values

        val monitoringEnabledSwitch: SwitchCompat = findViewById(R.id.monitoringEnabledSwitch)
        monitoringEnabledSwitch.isChecked = sharedPrefs!!.getBoolean("enabled", false)

        val timePeriodValueTextView: TextView = findViewById(R.id.edit_time_period_hours)
        timePeriodValueTextView.text = sharedPrefs!!.getString("time_period_hours", "12")

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
            updateAlarm = true
        }

        // if we need to update the alarm
        if (updateAlarm) {
            val newValue = sharedPrefs!!.getString("time_period_hours", "12")!!.toFloat()

            Log.d(
                "processSettingChange",
                "Check period updated, need to re-set alarm to $newValue hours"
            )

            val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(sharedPrefs!!,"REST_PERIODS")

            // don't need to cancel the existing alarm, just set a new one
            setAlarm(this, (newValue * 60 * 60 * 1000).toLong(), "periodic", restPeriods)
        }
    }

    private fun showEditSettingDialog(preferenceKey: String) {
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_edit_settings, null)
        val dialogEditText: EditText = dialogView.findViewById(R.id.customDialogEditText)
        val dialogDescription: TextView = dialogView.findViewById(R.id.customDialogTextView)

        // configure the dialog based on which setting this is
        var dialogTitle = ""

        // customize the dialog based on which setting is being edited
        when (preferenceKey) {

            "time_period_hours" -> {
                dialogTitle = getString(R.string.time_period_title)
                dialogEditText.hint = getString(R.string.time_period_title)
                dialogDescription.text = getString(R.string.time_period_description)
                dialogEditText.inputType =
                    EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_FLAG_DECIMAL
                dialogEditText.setText(sharedPrefs!!.getString("time_period_hours", "12"))
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
                    apply()
                }

                // take action depending on what preference is changing
                processSettingChange(preferenceKey)

                // update the text views to reflect the new values
                updateTextViewsFromPreferences()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()

        // omg it works... focus the edit text and make the keyboard appear
        dialogEditText.requestFocus()
        if (dialogEditText.requestFocus()) {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }

        // add a text watcher to the edit text so that we can enable/disable the submit button
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        dialogEditText.addTextChangedListener(InputTextWatcher(positiveButton, this, preferenceKey))
    }

    private fun showEditRestPeriodDialog() {
        val dialogView =
            LayoutInflater.from(this).inflate(R.layout.dialog_edit_rest_period, null)
        val dialogStartTimePicker: TimePicker = dialogView.findViewById(R.id.startTimePicker)
        val dialogEndTimePicker: TimePicker = dialogView.findViewById(R.id.endTimePicker)
        val restPeriodTimeZoneMessageTextView: TextView = dialogView.findViewById(R.id.restPeriodTimeZoneMessageTextView)

        restPeriodTimeZoneMessageTextView.text = Html.fromHtml(
            String.format(
                getString(R.string.rest_period_dialog_time_zone_message),

                // this should show up like 'CST' or 'PST'
                SimpleDateFormat("z", Locale.getDefault()).format(Calendar.getInstance().time)
            ),
            Html.FROM_HTML_MODE_LEGACY
        )

        // set the time pickers to 24 hour mode
        dialogStartTimePicker.setIs24HourView(true)
        dialogEndTimePicker.setIs24HourView(true)

        val currentRestPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(sharedPrefs!!,
            "REST_PERIODS")

        // if there is a rest period then set the time pickers to the current values
        if (currentRestPeriods.isNotEmpty()) {
            Log.d("showEditRestPeriodDialog", "currentRestPeriods: $currentRestPeriods")
            dialogStartTimePicker.hour = currentRestPeriods[0].startHour
            dialogStartTimePicker.minute = currentRestPeriods[0].startMinute
            dialogEndTimePicker.hour = currentRestPeriods[0].endHour
            dialogEndTimePicker.minute = currentRestPeriods[0].endMinute
        }

        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setTitle(getString(R.string.rest_period_dialog_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.save)) { _, _ ->

                // if the start and end times are the same then this is not a valid time range so
                //  show a toast and don't save the rest period
                if (dialogStartTimePicker.hour == dialogEndTimePicker.hour &&
                    dialogStartTimePicker.minute == dialogEndTimePicker.minute) {

                    showToast(getString(R.string.rest_period_invalid_range_message))
                    return@setPositiveButton
                }

                with(sharedPrefs!!.edit()) {

                    // create a new list of rest periods with just this one
                    val restPeriods = mutableListOf<RestPeriod>()
                    restPeriods.add(
                        RestPeriod(
                            dialogStartTimePicker.hour,
                            dialogStartTimePicker.minute,
                            dialogEndTimePicker.hour,
                            dialogEndTimePicker.minute
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
            .show()
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
            Log.d(
                "showAddOrEditSMSContactDialog",
                "This is the first SMS contact, setting default alert message"
            )
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
                "alert_message"
            )
        )
        phoneNumberInput.addTextChangedListener(
            InputTextWatcher(
                positiveButton,
                this,
                "contact_sms_phone"
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
        private val preferenceKey: String
    ) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

        override fun afterTextChanged(s: Editable?) {

            if (s != null) {

                // make sure these values are valid floats
                if (preferenceKey == "time_period_hours" || preferenceKey == "followup_time_period_minutes") {

                    // try to convert the string to a float
                    try {
                        val timeValue = s.toString().toFloat()
                        submitButton.isEnabled = true

                        // if we confirm its a valid value, make sure it is not too small
                        var valueInMinutes = timeValue

                        if (preferenceKey == "time_period_hours") {
                            valueInMinutes = timeValue * 60
                        }

                        // for either of these settings, make sure the value doesn't result
                        //  in a minute value of less than 9 as we can't make alarms
                        //  that frequently
                        if (valueInMinutes < AppController.ALARM_MINIMUM_TIME_PERIOD_MINUTES) {
                            submitButton.isEnabled = false

                            showToast(context.getString(R.string.time_period_too_short_message))
                        }
                    } catch (e: Exception) {
                        submitButton.isEnabled = false
                    }
                }

                // if this is a phone number make sure it is valid before
                //  enabling the submit button
                else if (preferenceKey == "contact_phone" || preferenceKey == "contact_sms_phone") {

                    // this just checks a regex of "[\\+]?[0-9.-]+"...
                    val isPhoneNumber = PhoneNumberUtils.isGlobalPhoneNumber(s.toString())

                    // SMS contact can't be blank but phone contact can be
                    if (preferenceKey == "contact_sms_phone") {
                        val shouldEnable = isPhoneNumber && s.isNotEmpty() && s.length > 1

                        // if the phone number is valid and the alert message is valid then
                        //  enable the button
                        if (shouldEnable && alertMessageValid) {
                            submitButton.isEnabled = true
                            contactSMSPhoneValid = true

                            // if just the phone number is valid then set this to true
                        } else if (shouldEnable) {
                            contactSMSPhoneValid = true

                            // otherwise neither are valid
                        } else {
                            submitButton.isEnabled = false
                            contactSMSPhoneValid = false
                        }
                    } else {

                        // the phone contact number is simpler but make sure that it is at
                        //  least 2 characters otherwise the formatting won't work
                        // also let it be blank as a way for users to clear the setting
                        submitButton.isEnabled = (isPhoneNumber && s.length > 1) || s.isEmpty()
                    }

                    // if the submit button isn't enabled then show a toast to let the user know
                    //  that their input is invalid
                    if (!submitButton.isEnabled) {

                        Log.d("InputTextWatcher", "$s is not a valid phone number?!")

                        // notify user that the phone number is invalid
                        showToast(context.getString(R.string.phone_number_invalid_message))
                    }
                }

                // if this is the alert message then we want to make sure it
                //  not more than 160 characters
                else if (preferenceKey == "alert_message") {
                    val shouldEnable = s.length <= AppController.SMS_MESSAGE_MAX_LENGTH

                    // if the current alert message is valid and so is the SMS phone number
                    if (shouldEnable && contactSMSPhoneValid) {
                        submitButton.isEnabled = true
                        alertMessageValid = true

                        // if just the alert message is valid
                    } else if (shouldEnable) {
                        alertMessageValid = true

                        // otherwise if neither is valid
                    } else {
                        submitButton.isEnabled = false
                        alertMessageValid = false
                    }

                    // if the length of the alert message is too long then show a toast
                    if (s.length > AppController.SMS_MESSAGE_MAX_LENGTH) {
                        showToast(context.getString(R.string.alarm_message_too_long_message))
                    }
                }
            }
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

