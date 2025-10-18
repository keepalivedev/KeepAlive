package io.keepalive.android

import android.app.ActivityManager
import android.content.Context
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.text.Html
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import com.google.common.util.concurrent.ListenableFuture
import io.keepalive.android.databinding.ActivityMainBinding
import java.util.Locale
import java.util.TimeZone


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPrefs: SharedPreferences

    private val tag = this.javaClass.name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // boiler plate stuff to create the view and action bar
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // load the preferences
        sharedPrefs = getEncryptedSharedPreferences(this.applicationContext)

        Log.d(tag, "KeepAlive onCreate")

        // don't need to do anything else here, onResume also gets called...
    }

    override fun onResume() {
        super.onResume()

        Log.d(tag, "onResume, updating text views and checking permissions")

        val needPermissions = PermissionManager(this, this).checkNeedAnyPermissions()
        updateMainContent(needPermissions)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {

            // launch the settings activity
            R.id.action_settings -> {
                val i = Intent(this, SettingsActivity::class.java)
                this.startActivity(i)
                return true
            }

            // launch the log display activity
            R.id.action_logdisplay -> {
                val i = Intent(this, LogDisplayActivity::class.java)
                this.startActivity(i)
                return true
            }

            // show an About dialog with information about the app
            R.id.action_about -> {

                // fill in the build version
                val messageStr = String.format(
                    getString(R.string.about_message_content),
                    BuildConfig.VERSION_NAME
                )

                // use HTML to make the formatting easier...
                val htmlMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Html.fromHtml(messageStr, Html.FROM_HTML_MODE_LEGACY)
                } else {
                    @Suppress("DEPRECATION")
                    Html.fromHtml(messageStr)
                }

                val dialog = AlertDialog.Builder(this, R.style.AlertDialogTheme)
                    .setTitle(getString(R.string.about_dialog_title))
                    .setMessage(htmlMessage)
                    .setPositiveButton(getString(R.string.close), null)
                    .show()

                val dialogMessage = dialog.findViewById<TextView>(android.R.id.message)

                // make the link clickable
                dialogMessage?.movementMethod = LinkMovementMethod.getInstance()
                dialogMessage?.textSize = 18f

                return true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        Log.d(
            tag,
            "requestCode: $requestCode, permissions: $permissions, grantResults: $grantResults"
        )

        // don't really need to check whether the permissions were granted or not?
        //  just check all of the permissions and hide the button appropriately
        adjustPermissionsComponents(PermissionManager(this, this).checkNeedAnyPermissions())
    }

    private fun updateMainContent(needPermissions: Boolean) {

        // check whether we have any permissions left to request and, if not, hide
        //  the permissions button and set a message
        adjustPermissionsComponents(needPermissions)

        // update the text views and buttons based on preferences
        updateStatusTextViews(needPermissions)
        updateTestAlertTextViews(needPermissions)
        configureButtons()

        // this should go after we update the text views because, depending on whats in the extras,
        //  we may be updating some of the text views
        Log.d(tag, "extras are ${intent.extras}")

        // check the intent extras and update the views accordingly
        checkExtras(intent.extras)

    }

    // set onClick listeners for the buttons on the main page
    private fun configureButtons() {

        // listener for the permissions button
        binding.permissionsButton.setOnClickListener { _ ->

            // attempt to request the necessary permissions and if we don't actually need any
            //  then hide the permissions button
            val haveAllPerms = PermissionManager(this, this).checkHavePermissions()

            adjustPermissionsComponents(!haveAllPerms)
        }

        // listener for the Check App Restrictions button (only visible if app restrictions are enabled)
        val checkAppRestrictionsButton: Button = findViewById(R.id.buttonCheckAppRestriction)
        checkAppRestrictionsButton.setOnClickListener { _ ->

            val future: ListenableFuture<Int> =
                PackageManagerCompat.getUnusedAppRestrictionsStatus(this)

            // check the current app restrictions status and pass the result to the callback
            future.addListener(
                { requestDisableAppRestrictions(future.get()) },
                ContextCompat.getMainExecutor(this)
            )
        }

        // listener for the Test Alert SMS button
        val testAlertSmsButton: Button = findViewById(R.id.buttonTestAlertSms)

        // make sure it clickable (in case it was previously disabled)
        testAlertSmsButton.isClickable = true
        testAlertSmsButton.setOnClickListener { _ ->

            // build a dialog so that the user can confirm the test alert before sending
            showTestAlertSMSDialog(testAlertSmsButton)
        }

        // listener for the Test Alert Call button
        val testAlertCallButton: Button = findViewById(R.id.buttonTestAlertCall)
        testAlertCallButton.isClickable = true
        testAlertCallButton.setOnClickListener { _ ->

            testAlertCallButton.isClickable = false

            makeAlertCall(this)
        }

        // listener for the restart monitoring button; this button will not always be visible
        val restartMonitoringButton: Button = findViewById(R.id.buttonRestartMonitoring)
        restartMonitoringButton.isClickable = true
        restartMonitoringButton.setOnClickListener { _ ->

            restartMonitoringButton.isClickable = false

            // set the alarm
            val checkPeriodHours = sharedPrefs.getString("time_period_hours", "12")?.toFloatOrNull() ?: 12f
            val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(sharedPrefs,"REST_PERIODS")

            // if they are hitting the restart button then use now as the last activity time
            setAlarm(this, System.currentTimeMillis(), (checkPeriodHours * 60).toInt(), "periodic", restPeriods)

            // wait a second for the alarm to be set and then update the text views
            Handler(Looper.getMainLooper()).postDelayed({

                // assume we don't need any perms here, if the button to restart monitoring is
                //  visible then that means we have all permissions?
                updateStatusTextViews(false)
            }, 1000)
        }
    }

    private fun showTestAlertSMSDialog(testAlertSmsButton: Button) {

        // build a custom dialog layout for the test alert confirmation
        val dialogView = layoutInflater.inflate(R.layout.dialog_test_alert_confirmation, null)
        val messageTextView = dialogView.findViewById<TextView>(R.id.testConfirmationDialogMessage)
        val proceedMessageTextView = dialogView.findViewById<TextView>(R.id.testProceedConfirmationDialogMessage)
        val switchSendWarning = dialogView.findViewById<SwitchCompat>(R.id.switchSendWarning)
        val editTextWarningMessage = dialogView.findViewById<EditText>(R.id.editTextWarningMessage)
        val warningMessageLayout = dialogView.findViewById<LinearLayout>(R.id.warningMessageLayout)

        // load the status of the warning message checkbox
        val warningMessageEnabled = sharedPrefs.getBoolean("test_alert_send_warning", false)
        switchSendWarning.isChecked = warningMessageEnabled

        // if they saved a custom message use that
        val testAlertSMSMessage = sharedPrefs.getString("test_alert_warning_sms_message", "")
        if (testAlertSMSMessage != "") {
            editTextWarningMessage.setText(testAlertSMSMessage)
        } else {

            // otherwise set default warning message
            editTextWarningMessage.setText(getString(R.string.test_alert_sms_default_message))
        }

        // Initially set visibility based on checkbox state
        warningMessageLayout.visibility = if (switchSendWarning.isChecked) View.VISIBLE else View.GONE

        fun buildProceedSMSCountMessage(messageCount: Int): Spanned {

            val proceedMessage = getString(R.string.test_alert_confirmation_message_with_count, messageCount)

            // deal with fromHtml deprecation
            val formattedProceedMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(proceedMessage, Html.FROM_HTML_MODE_LEGACY)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(proceedMessage)
            }
            return formattedProceedMessage
        }

        // get the SMS contact phone numbers as a csv string
        val phoneNumberStr = getSMSContactString()
        messageTextView.text = getString(R.string.test_sms_configured_contacts_message, phoneNumberStr)

        // get the message count based on the enabled contacts and whether they have location
        val messageCount = getSMSMessageCount(switchSendWarning.isChecked)

        // set the message for the initial dialog load
        proceedMessageTextView.text = buildProceedSMSCountMessage(messageCount)

        // Show/hide warningMessageLayout based on switch state
        switchSendWarning.setOnCheckedChangeListener { _, isChecked ->
            warningMessageLayout.visibility = if (isChecked) View.VISIBLE else View.GONE

            // get the message count based on the enabled contacts and whether they have location
            val msgCount = getSMSMessageCount(switchSendWarning.isChecked)

            // recalculate the message count when the warning message is enabled/disabled
            proceedMessageTextView.text = buildProceedSMSCountMessage(msgCount)
        }

        AlertDialog.Builder(this, R.style.AlertDialogTheme)
            .setView(dialogView)
            .setTitle(getString(R.string.test_alert_confirmation_title))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->

                // make it unclickable so we don't fire this more than once at a time
                testAlertSmsButton.isClickable = false

                // disable it to make it evident that the button won't work
                testAlertSmsButton.isEnabled = false

                // save the status of the warning message checkbox
                with(sharedPrefs.edit()) {
                    putBoolean("test_alert_send_warning", switchSendWarning.isChecked)
                    apply()
                }

                // if the message isn't still the default message then save it to shared prefs
                if (editTextWarningMessage.text.toString() != getString(R.string.test_alert_sms_default_message)) {
                    with(sharedPrefs.edit()) {
                        putString("test_alert_warning_sms_message", editTextWarningMessage.text.toString())
                        apply()
                    }
                }

                // if the switch is checked then include the warning message to send
                val warningMessage = if (switchSendWarning.isChecked) editTextWarningMessage.text.toString() else ""

                val alertSender = AlertMessageSender(this)

                // send the configured alert message
                alertSender.sendAlertMessage(testWarningMessage = warningMessage)

                // if location is enabled, try to get that and then send the message
                if (sharedPrefs.getBoolean("location_enabled", false)) {

                    val locationHelper = LocationHelper(this) { _, locationResult ->
                        alertSender.sendLocationAlertMessage(locationResult.formattedLocationString)

                        // re-enable the buttons after sending the location message
                        runOnUiThread {
                            testAlertSmsButton.isEnabled = true
                            testAlertSmsButton.isClickable = true
                        }
                    }
                    locationHelper.getLocationAndExecute()
                } else {

                    // re-enable the buttons after sending the alert message
                    testAlertSmsButton.isEnabled = true
                    testAlertSmsButton.isClickable = true
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // show or hide the 'Grant Permissions' button depending
    //  on whether or not there we still need permissions
    private fun adjustPermissionsComponents(needPermissions: Boolean) {

        if (needPermissions) {
            binding.permissionsButton.show()

        } else {
            binding.permissionsButton.hide()
        }
    }

    /*
    4 situations, unused components will be hidden
    monitoring active - monitoringStatusTextView and monitoringMessageTextView
    monitoring inactive - buttonRestartMonitoring and monitoringMessageTextView
    monitoring not possible - monitoringStatusTextView and monitoringMessageTextView
    monitoring impaired - monitoringStatusTextView and monitoringMessageTextView and checkAppRestrictionsButton
     */
    // based on the preferences, update the text views on the main page and which
    //  views and buttons are displayed
    private fun updateStatusTextViews(needPerms: Boolean) {

        val alarmTimestamp = sharedPrefs.getLong("NextAlarmTimestamp", -1)
        val isEnabled = sharedPrefs.getBoolean("enabled", false)

        // the message will always be visible
        val monitoringMessageTextView =
            binding.root.findViewById<TextView>(R.id.textviewMonitoringMessage)

        // either the status text or the restart button will be shown
        val monitoringStatusTextView =
            binding.root.findViewById<TextView>(R.id.textviewMonitoringStatus)
        val restartMonitoringButton =
            binding.root.findViewById<Button>(R.id.buttonRestartMonitoring)

        monitoringStatusTextView.textSize = 24F
        monitoringStatusTextView.setTypeface(null, Typeface.BOLD)

        // this should only happen the first time the app is run or if the user has disabled it
        if (alarmTimestamp == -1L || !isEnabled) {
            DebugLogger.d(tag, getString(R.string.debug_log_no_alarm_or_monitoring_disabled))

            // set a big red message indicating that monitoring is not active
            monitoringStatusTextView.text = getString(R.string.monitoring_disabled_title)
            monitoringStatusTextView.setTextColor(
                getColorCompat(this, R.color.monitoringInActive)
            )
            monitoringMessageTextView.text = getString(R.string.monitoring_disabled_message)

        } else {

            // convert to system timezone when displaying to the user
            val alarmDtStr = getDateTimeStrFromTimestamp(alarmTimestamp, TimeZone.getDefault().id)

            DebugLogger.d(tag, getString(R.string.debug_log_current_alarm_time, alarmDtStr))

            // if the time is positive then there is an active alarm so let
            //  the user know that the monitoring is enabled
            if (alarmTimestamp - System.currentTimeMillis() > 0) {

                // hide the button to restart monitoring and show the status text
                restartMonitoringButton.visibility = View.INVISIBLE
                monitoringStatusTextView.visibility = View.VISIBLE

                var checkPeriodHours = sharedPrefs.getString("time_period_hours", "12")?.toFloatOrNull() ?: 12f
                checkPeriodHours =
                    maxOf(checkPeriodHours, AppController.LAST_ACTIVITY_MAX_PERIOD_CHECK_HOURS)

                val appsToMonitor: MutableList<MonitoredAppDetails> = loadJSONSharedPreference(
                    sharedPrefs,"APPS_TO_MONITOR")

                // as a sanity check, look back either 48 hours or, if the user has set a
                //  longer time period, use that instead
                val lastInteractiveEvent = getLastDeviceActivity(
                    this,
                    (System.currentTimeMillis() - (checkPeriodHours * 1000 * 60 * 60)).toLong(),
                    appsToMonitor.map { it.packageName }
                )

                // if we haven't found any events then the user probably doesn't have a lock screen
                //  and the app isn't going to work
                if (lastInteractiveEvent == null) {
                    DebugLogger.d(tag, getString(R.string.debug_log_no_events_found_lock_screen))

                    // set a big red message indicating that we can't enable monitoring
                    monitoringStatusTextView.text =
                        getString(R.string.monitoring_no_activity_detected_title)
                    monitoringStatusTextView.setTextColor(
                        getColorCompat(
                            this,
                            R.color.monitoringInActive,
                        )
                    )

                    // and a message indicating why
                    monitoringMessageTextView.text = String.format(
                        getString(R.string.monitoring_no_activity_detected_message),
                        checkPeriodHours.toString()
                    )

                } else {

                    // if we still need permissions then show a message indicating that
                    if (needPerms) {
                        Log.d(tag, "Still need some permissions, making sure user is aware..")

                        monitoringStatusTextView.text =
                            getString(R.string.monitoring_permissions_required_title)
                        monitoringStatusTextView.setTextColor(
                            getColorCompat(this, R.color.monitoringImpaired)
                        )
                        monitoringMessageTextView.text =
                            getString(R.string.monitoring_permissions_required_message)

                    } else {
                        Log.d(tag, "Don't need any permissions, we are all set?!")

                        // get the hours and minutes so we can show the time until the next
                        //  activity check in a friendly format
                        val alarmTimestampInSec =
                            (alarmTimestamp - System.currentTimeMillis()) / 1000
                        val hours = alarmTimestampInSec / 3600
                        val minutes = (alarmTimestampInSec % 3600) / 60

                        // set a big green message indicating that monitoring is active
                        monitoringStatusTextView.text = getString(R.string.monitoring_active_title)
                        monitoringStatusTextView.setTextColor(
                            getColorCompat(this,R.color.monitoringActive)
                        )

                        // set the message to show the last detected activity and when the next check is
                        monitoringMessageTextView.text = String.format(
                            getString(R.string.monitoring_active_message),
                            getDateTimeStrFromTimestamp(
                                lastInteractiveEvent.timeStamp,
                                TimeZone.getDefault().id
                            ), hours.toString(), minutes.toString()
                        )

                        // check whether app restrictions are enabled and adjust the components based
                        //  on the result
                        val future: ListenableFuture<Int> =
                            PackageManagerCompat.getUnusedAppRestrictionsStatus(this)

                        // check the current app restrictions status and pass the result to the callback
                        future.addListener(
                            { updateTextViewsFromAppRestrictionStatus(future.get()) },
                            ContextCompat.getMainExecutor(this)
                        )
                    }
                }
            } else {
                DebugLogger.d(tag, getString(R.string.debug_log_no_active_alarm_showing_restart_button))

                monitoringMessageTextView.text = getString(R.string.monitoring_inactive_message)

                // show the button here instead of the status text view
                monitoringStatusTextView.visibility = View.INVISIBLE
                restartMonitoringButton.visibility = View.VISIBLE

                val param = monitoringMessageTextView.layoutParams as ViewGroup.MarginLayoutParams
                param.setMargins(0, 60, 0, 0)
                monitoringMessageTextView.layoutParams = param

            }
        }

    }

    // update the text views related to the Test Alert buttons
    private fun updateTestAlertTextViews(needPerms: Boolean) {

        // update the phone call phone number text view
        val callPhoneTextView = binding.root.findViewById<TextView>(R.id.textviewCallPhoneNumber)
        val testAlertCallButton = binding.root.findViewById<TextView>(R.id.buttonTestAlertCall)
        val smsPhoneTextView = binding.root.findViewById<TextView>(R.id.textviewSmsPhoneNumber)
        val testAlertSMSButton = binding.root.findViewById<TextView>(R.id.buttonTestAlertSms)

        // make everything visible by default
        callPhoneTextView.visibility = View.VISIBLE
        smsPhoneTextView.visibility = View.VISIBLE
        testAlertCallButton.visibility = View.VISIBLE
        testAlertSMSButton.visibility = View.VISIBLE

        // if we still need any permissions then disable the buttons and hide the text views
        if (needPerms) {
            testAlertCallButton.isEnabled = false
            testAlertSMSButton.isEnabled = false
            callPhoneTextView.visibility = View.INVISIBLE
            smsPhoneTextView.visibility = View.INVISIBLE
            Log.d(tag, "Still need some permissions, disabling test alert buttons")
            return
        }

        // default value is empty string in case the user later decides to remove the phone number
        var callPhoneNumber = sharedPrefs.getString("contact_phone", "")

        // if nothing is configured then disable the button and set a red message
        if (callPhoneNumber == "") {
            testAlertCallButton.isEnabled = false
            callPhoneTextView.text = getString(R.string.no_configured_contacts_message)
            callPhoneTextView.setTextColor(getColorCompat(this, R.color.red))
        } else {
            testAlertCallButton.isEnabled = true
            // format the phone number and show the text view and button
            callPhoneNumber =
                PhoneNumberUtils.formatNumber(callPhoneNumber, Locale.getDefault().country)
            callPhoneTextView.visibility = View.VISIBLE
            testAlertCallButton.visibility = View.VISIBLE
            callPhoneTextView.text =
                String.format(getString(R.string.test_phone_call_message), callPhoneNumber)
        }

        // next configure the SMS phone number text view and button

        // load SMS contacts as csv string
        var smsPhoneNumbers = getSMSContactString()

        // set the text color to the default, may change below
        smsPhoneTextView.setTextColor(getColorCompat(this, R.color.textColor))

        // if we don't have any SMS contacts then disable the button and set a red message
        if (smsPhoneNumbers == "") {
            testAlertSMSButton.isEnabled = false
            smsPhoneTextView.text = getString(R.string.no_configured_contacts_message)
            smsPhoneTextView.setTextColor(getColorCompat(this, R.color.red))
        } else {

            // first see if we can even send SMS
            val smsManager = getSMSManager(this)
            if (smsManager == null) {

                DebugLogger.d(tag, getString(R.string.debug_log_failed_getting_sms_manager_disable_button))

                // make the test alert button visible but disabled
                smsPhoneTextView.visibility = View.VISIBLE
                testAlertSMSButton.visibility = View.VISIBLE
                testAlertSMSButton.isEnabled = false

                // showing the message here so that its display is independent
                //  from the main status text view
                smsPhoneTextView.text = getString(R.string.unable_to_send_sms_message)
                smsPhoneTextView.setTextColor(getColorCompat(this, R.color.red))

            } else {
                testAlertSMSButton.isEnabled = true
                smsPhoneTextView.visibility = View.VISIBLE
                testAlertSMSButton.visibility = View.VISIBLE
                smsPhoneTextView.text =
                    String.format(getString(R.string.test_sms_message), smsPhoneNumbers)
            }
        }
    }

    private fun getSMSContactString(): String {
        val smsContacts: MutableList<SMSEmergencyContactSetting> = loadJSONSharedPreference(sharedPrefs,
            "PHONE_NUMBER_SETTINGS")

        var smsPhoneNumbers = ""

        // loop through the SMS contacts and create a csv string of the enabled contacts
        for (contact in smsContacts) {

            if (contact.isEnabled && contact.phoneNumber != "") {
                smsPhoneNumbers += PhoneNumberUtils.formatNumber(
                    contact.phoneNumber,
                    Locale.getDefault().country
                ) + ", "
            }
        }

        return smsPhoneNumbers.dropLast(2)
    }

    // get the number of SMS messages that will be sent when the test alert is triggered
    private fun getSMSMessageCount(includeWarning: Boolean): Int {
        val smsContacts: MutableList<SMSEmergencyContactSetting> = loadJSONSharedPreference(sharedPrefs,
            "PHONE_NUMBER_SETTINGS")

        var messageCount = 0

        // loop through the SMS contacts and create a csv string of the enabled contacts
        for (contact in smsContacts) {

            if (contact.isEnabled && contact.phoneNumber != "") {

                // add one for the alert message
                messageCount++

                // add one for the location message
                if (contact.includeLocation) {
                    messageCount++
                }

                // add one for the warning message
                if (includeWarning) {
                    messageCount++
                }
            }
        }
        return messageCount
    }

    // check to see if any data was passed to the activity
    private fun checkExtras(extras: Bundle?) {

        if (extras != null) {

            // this should only get set on the 'Are you there?' notification
            val alertCheck = extras.getBoolean("AlertCheck", false)

            Log.d(tag, "AlertCheck extra is $alertCheck")

            // if the AlertCheck is true, meaning the user clicked on the 'Are you there?'
            // notification. since we cancel this notification when sending an alert,
            //  this should only ever be hit BEFORE an alert is sent so we can assume that
            //  it hasn't been sent yet and just re-set it and let the user know that
            //  the Alert is going to go off
            if (alertCheck) {

                Log.d(tag, "Alert notification was clicked on!")

                val checkPeriodHours = sharedPrefs.getString("time_period_hours", "12")?.toFloatOrNull() ?: 12f
                val restPeriods: MutableList<RestPeriod> = loadJSONSharedPreference(sharedPrefs,"REST_PERIODS")

                // if the user clicked on the notification we can assume they are active so
                //  regardless of the last activity, just re-set the alarm
                setAlarm(this, System.currentTimeMillis(), (checkPeriodHours * 60).toInt(),
                    "periodic", restPeriods)

                // let the user know that the alert was cancelled
                binding.root.findViewById<TextView>(R.id.textviewMonitoringMessage).text =
                    getString(R.string.activity_notification_message)
            }
        } else {
            Log.d(tag, "extras bundle is null?!")
        }
    }

    // update the text views based on the app restriction status
    private fun updateTextViewsFromAppRestrictionStatus(appRestrictionsStatus: Int) {

        val monitoringStatusTextView =
            binding.root.findViewById<TextView>(R.id.textviewMonitoringStatus)
        val checkAppRestrictionsButton =
            binding.root.findViewById<Button>(R.id.buttonCheckAppRestriction)

        // by default hide the button
        checkAppRestrictionsButton.visibility = View.GONE

        when (appRestrictionsStatus) {
            UnusedAppRestrictionsConstants.ERROR -> {
                Log.d(
                    "updViewsAppResStatus",
                    "Error checking app restriction status"
                )
            }
            // Restrictions don't apply to your app on this device.
            UnusedAppRestrictionsConstants.FEATURE_NOT_AVAILABLE -> {
                Log.d(
                    "updViewsAppResStatus",
                    "App restriction not available on this device"
                )
            }
            // The user has disabled restrictions for your app; which is what we want
            UnusedAppRestrictionsConstants.DISABLED -> {
                Log.d("updViewsAppResStatus", "App restriction disabled")
            }
            // restrictions are enabled
            UnusedAppRestrictionsConstants.API_30_BACKPORT,
            UnusedAppRestrictionsConstants.API_30,
            UnusedAppRestrictionsConstants.API_31 -> {
                Log.d("updViewsAppResStatus", "App restrictions enabled?!")

                // show a warning to the user indicating that app restrictions are enabled
                monitoringStatusTextView.text = getString(R.string.monitoring_impaired_title)
                monitoringStatusTextView.setTextColor(
                    getColorCompat(this, R.color.monitoringImpaired)
                )
                checkAppRestrictionsButton.visibility = View.VISIBLE
            }

            else -> {
                Log.d(
                    "updViewsAppResStatus",
                    "App restrictions disabled or not applicable?"
                )
            }
        }
    }

    // check whether app restrictions/hibernation is enabled and,
    //  if so, prompt the user to disable it
    private fun requestDisableAppRestrictions(appRestrictionsStatus: Int) {
        try {
            // default to the API 32+ message
            var dialogMessage = getString(R.string.hibernation_dialog_message)


            // don't need to check the other status values as this function is only called if
            //  app restrictions are enabled
            when (appRestrictionsStatus) {

                // the settings appear in different places depending on which API version it is...

                // for API_30_BACKPORT normally they would have to go
                //  Play app > Menu > Play Protect > Permissions for Unused Apps but
                //  createManageUnusedAppRestrictionsIntent will take them there directly
                UnusedAppRestrictionsConstants.API_30_BACKPORT -> {
                    Log.d(
                        "reqDisableAppHiber",
                        "App hibernation enabled on API 30 backport"
                    )
                    dialogMessage = getString(R.string.hibernation_dialog_message_api_backport)
                }

                UnusedAppRestrictionsConstants.API_30 -> {
                    Log.d("reqDisableAppHiber", "App hibernation enabled on API 30")
                    dialogMessage = getString(R.string.hibernation_dialog_message_api30)
                }

                // ugh there is no 32+ constant and the wording used in the app Settings is different
                //  in 31 than it is in 32-34 and 35 so we need to detect which the device is using...
                UnusedAppRestrictionsConstants.API_31 -> {
                    Log.d(
                        "reqDisableAppHiber",
                        "App hibernation enabled on API 31 or higher. SDK_INT is ${Build.VERSION.SDK_INT}"
                    )

                    // API 31 uses different wording so need to set the message string accordingly
                    dialogMessage = if (Build.VERSION.SDK_INT == 31) {
                        getString(R.string.hibernation_dialog_message_api31)
                    } else if (Build.VERSION.SDK_INT == 35) {
                        // message for 35
                        getString(R.string.hibernation_dialog_message_api35)
                    } else {
                        // message for 32-34
                        getString(R.string.hibernation_dialog_message)
                    }
                }
            }

            // the hibernation dialog messages use HTML tags for formatting
            val formattedDialogMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(dialogMessage, Html.FROM_HTML_MODE_LEGACY)
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(dialogMessage)
            }

            // create a dialog to explain what we want the user to do and then, if they
            //  click ok, take them to the app settings page
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.hibernation_dialog_title))
                .setMessage(formattedDialogMessage)
                .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->

                    // regardless of the API version this will take the user to the correct place
                    val intent = IntentCompat.createManageUnusedAppRestrictionsIntent(
                        this,
                        packageName
                    )

                    // docs say to use startActivityForResult even if we aren't using the result?
                    ActivityCompat.startActivityForResult(
                        this,
                        intent,
                        AppController.APP_HIBERNATION_ACTIVITY_RESULT_CODE,
                        null
                    )
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()

        } catch (e: Exception) {
            Log.e("reqDisableAppHiber", "Failed requesting disable app hibernation?!", e)
        }
    }

    // todo implement this?
    // also requires android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    private fun checkAppBatteryRestrictions() {
        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
                if (activityManager!!.isBackgroundRestricted) {
                    Log.d(tag, "Background battery use restricted")
                } else {
                    Log.d(tag, "Background battery use not restricted")
                }
            }

            // this launches a UI prompt asking the user whether to 'Let app always run in background'
            //  if allowed, the battery optimization settings page will have NO options selected
            //  and activityManager.isBackgroundRestricted will still return true
            // the prompt WILL NOT be displayed if the app is set to Optimized or Unrestricted or
            //  if the permissions aren't declared in the manifest

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.setData(Uri.parse("package:$packageName"))
                ActivityCompat.startActivity(this, intent, null)
            }

        } catch (e: Exception) {
            Log.e("checkAppBattRestriction", "Failed checking app battery restrictions?!", e)
        }
    }
}