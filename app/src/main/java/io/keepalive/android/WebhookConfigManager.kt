package io.keepalive.android

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.switchmaterial.SwitchMaterial
import org.json.JSONObject
import org.json.JSONException
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull


class WebhookConfigManager(private val context: Context, private val activity: AppCompatActivity?) {

    private val sharedPref = getEncryptedSharedPreferences(context)
    private val maxHeaders = 10
    private val maxRetries = 10
    private val maxTimeout = 300
    private val maxHeaderKeyLength = 256
    private val maxHeaderValueLength = 8192

    // we can assume these start true
    private var isTimeoutValid = true
    private var isRetriesValid = true
    private var isWebhookUrlValid = true
    private var areHeadersValid = true

    private lateinit var dialogPositiveButton: Button
    private lateinit var buttonTestWebhook: Button
    private lateinit var buttonAddHeader: Button

    fun showWebhookConfigDialog(updateViewsCallback: () -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_webhook_config, null)
        val editTextWebhookUrl = dialogView.findViewById<TextInputEditText>(R.id.editTextWebhookUrl)
        val spinnerHttpMethod = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerHttpMethod)
        val spinnerHttpMethodLayout = dialogView.findViewById<TextInputLayout>(R.id.spinnerHttpMethodLayout)
        val spinnerIncludeLocation = dialogView.findViewById<AutoCompleteTextView>(R.id.spinnerIncludeLocation)
        val editTextTimeout = dialogView.findViewById<TextInputEditText>(R.id.editTextTimeout)
        val editTextRetries = dialogView.findViewById<TextInputEditText>(R.id.editTextRetries)
        val switchVerifyCertificate = dialogView.findViewById<SwitchMaterial>(R.id.switchVerifyCertificate)
        val headerRecyclerView = dialogView.findViewById<RecyclerView>(R.id.headerRecyclerView)

        buttonAddHeader = dialogView.findViewById(R.id.buttonAddHeader)
        buttonTestWebhook = dialogView.findViewById(R.id.buttonTestWebhook)

        // options for the HTTP method spinner
        val methods = arrayOf(
            context.getString(R.string.webhook_get),
            context.getString(R.string.webhook_post),
        )

        // use a custom list item layout to make it look better
        val methodAdapter = ArrayAdapter(context, R.layout.spinner_list_item, methods)
        spinnerHttpMethod.setAdapter(methodAdapter)

        // options for the Include Location dropdown
        val includeLocationOptions = arrayOf(
            context.getString(R.string.webhook_location_do_not_include),
            context.getString(R.string.webhook_location_body_json),
            context.getString(R.string.webhook_location_body_form),
            context.getString(R.string.webhook_location_query_parameters)
        )

        // Setup Include Location spinner
        val locationAdapter = ArrayAdapter(context, R.layout.spinner_list_item, includeLocationOptions)
        spinnerIncludeLocation.setAdapter(locationAdapter)

        // load the saved preferences into a WebhookConfig object
        val webhookConfig = getWebhookConfig()

        // load saved settings into the dialog
        editTextWebhookUrl.setText(webhookConfig.url)
        editTextTimeout.setText(webhookConfig.timeout.toString())
        editTextRetries.setText(webhookConfig.retries.toString())
        switchVerifyCertificate.isChecked = webhookConfig.verifyCertificate
        spinnerHttpMethod.setText(webhookConfig.method, false)
        spinnerIncludeLocation.setText(webhookConfig.includeLocation, false)

        // Disable HTTP method selection if saved location is Body - JSON or Body - Form
        if (webhookConfig.includeLocation == context.getString(R.string.webhook_location_body_json) || webhookConfig.includeLocation == context.getString(R.string.webhook_location_body_form)) {
            spinnerHttpMethod.setText(context.getString(R.string.webhook_post), false)

            // disabling just the spinner still leaves the carrot at the edge working so
            //  we need to disable the layout as well...
            spinnerHttpMethod.isEnabled = false
            spinnerHttpMethodLayout.isEnabled = false
        }

        // Setup RecyclerView for headers
        val headerAdapter = HeaderAdapter()
        headerRecyclerView.layoutManager = LinearLayoutManager(context)
        headerRecyclerView.adapter = headerAdapter
        Log.d("WebhookConfigManager", "loading headers: ${webhookConfig.headers}")

        // Load saved headers
        webhookConfig.headers.forEach { (key, value) ->
            headerAdapter.addHeader(key, value)
        }

        // Add listener to Include Location spinner
        spinnerIncludeLocation.setOnItemClickListener { _, _, position, _ ->
            when (includeLocationOptions[position]) {
                context.getString(R.string.webhook_location_body_json), context.getString(R.string.webhook_location_body_form) -> {
                    spinnerHttpMethod.setText(context.getString(R.string.webhook_post), false)
                    spinnerHttpMethod.isEnabled = false
                    spinnerHttpMethodLayout.isEnabled = false
                }
                else -> {
                    spinnerHttpMethod.isEnabled = true
                    spinnerHttpMethodLayout.isEnabled = true
                }
            }
        }

        // Add Header button listener
        buttonAddHeader.setOnClickListener {
            if (headerAdapter.itemCount < maxHeaders) {
                headerAdapter.addHeader("", "")
            } else {
                Toast.makeText(context, context.getString(R.string.webhook_toast_max_headers, maxHeaders.toString()), Toast.LENGTH_SHORT).show()
            }
        }

        // Test Webhook button listener with the current settings
        buttonTestWebhook.setOnClickListener {

            val shouldIncludeLocation = spinnerIncludeLocation.text.toString() != context.getString(R.string.webhook_location_do_not_include)

            // in order to fully test the webhook we need to be able to pull the location so
            //  check those here and request if needed
            val permissionManager = PermissionManager(context, activity)

            // this will require the user to click the test button again after granting the permission
            // only check for location permissions if we are including the location
            if (!shouldIncludeLocation || permissionManager.checkRequestSinglePermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) {

                // this would only be to ensure that permissions are granted in case they save the
                //  settings and then just leave the app instead of going back to the home page?
               // if (permissionManager.checkBackgroundLocationPermissions(true)) {

                buttonTestWebhook.isEnabled = false

                testWebhook(
                    editTextWebhookUrl.text.toString(),
                    spinnerHttpMethod.text.toString(),
                    spinnerIncludeLocation.text.toString(),
                    editTextTimeout.text.toString().toIntOrNull() ?: 10,
                    switchVerifyCertificate.isChecked,
                    headerAdapter.getHeaders()
                )
              // }
            }
        }

        // webhook settings dialog
        val alertDialog =  AlertDialog.Builder(context, R.style.AlertDialogTheme)
            .setTitle(context.getString(R.string.webhook_dialog_title))
            .setView(dialogView)
            .setPositiveButton(context.getString(R.string.save)) { _, _ ->

                // note that this doesn't account for duplicates and if there are multiple headers
                //  with the same key then it will just use the latest one in the list
                val headers = headerAdapter.getHeaders()


                Log.d("WebhookConfigManager", "converted url: ${editTextWebhookUrl.text.toString().toHttpUrlOrNull()}")

                // save the settings
                with (sharedPref.edit()) {
                    putBoolean("webhook_enabled", true)

                    // location is enabled if it is anything but the 'do not include' option
                    putBoolean("webhook_location_enabled", spinnerIncludeLocation.text.toString() != context.getString(R.string.webhook_location_do_not_include))

                    // save the raw url instead of from toHttpUrlOrNull so that it will show up
                    //  in a more user friendly way
                    putString("webhook_url", editTextWebhookUrl.text.toString())
                    putString("webhook_method", spinnerHttpMethod.text.toString())
                    putString("webhook_include_location", spinnerIncludeLocation.text.toString())
                    putInt("webhook_timeout", editTextTimeout.text.toString().toIntOrNull() ?: 10)
                    putInt("webhook_retries", editTextRetries.text.toString().toIntOrNull() ?: 0)
                    putBoolean("webhook_verify_certificate", switchVerifyCertificate.isChecked)
                    putString("webhook_headers", JSONObject(headers).toString())
                    apply()
                }
                DebugLogger.d("WebhookConfigManager", context.getString(R.string.debug_log_webhook_config_saved))
                updateViewsCallback()

            }
            .setNegativeButton(context.getString(R.string.cancel), null)
            .setNeutralButton(context.getString(R.string.delete)) { _, _ ->

                // remove all webhook settings
                with (sharedPref.edit()) {
                    remove("webhook_enabled")
                    remove("webhook_url")
                    remove("webhook_method")
                    remove("webhook_include_location")
                    remove("webhook_timeout")
                    remove("webhook_retries")
                    remove("webhook_verify_certificate")
                    remove("webhook_headers")
                    apply()
                }
                DebugLogger.d("WebhookConfigManager", context.getString(R.string.debug_log_webhook_config_deleted))
                updateViewsCallback()
            }
            .create()

        alertDialog.show()

        // get the Save button from the dialog
        dialogPositiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE)

        // false if the webhook is empty or not a valid URL
        isWebhookUrlValid = if (editTextWebhookUrl.text.toString().isEmpty()) false else editTextWebhookUrl.text.toString().toHttpUrlOrNull() != null

        // set the initial state of the buttons
        updateButtonState()

        // the layout for the webhook url so we can set an error message on it
        val layoutWebhookUrl = dialogView.findViewById<TextInputLayout>(R.id.layoutWebhookUrl)
        editTextWebhookUrl.addTextChangedListener(object : TextWatcher {

            override fun afterTextChanged(s: Editable?) {

                val url = s.toString()

                if (url.isEmpty()) {
                    layoutWebhookUrl.error = context.getString(R.string.webhook_url_empty_error)
                    isWebhookUrlValid = false
                } else if (url.toHttpUrlOrNull() == null) {
                    layoutWebhookUrl.error = context.getString(R.string.webhook_url_invalid_error)
                    isWebhookUrlValid = false
                } else {
                    layoutWebhookUrl.error = null
                    isWebhookUrlValid = true
                }
                updateButtonState()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // timeout text changed listener
        val layoutTimeout = dialogView.findViewById<TextInputLayout>(R.id.editTextTimeoutLayout)
        editTextTimeout.addTextChangedListener(object : TextWatcher {

            override fun afterTextChanged(s: Editable?) {

                val timeout = s.toString().toIntOrNull()

                if (timeout == null || timeout < 1) {
                    layoutTimeout.error = context.getString(R.string.webhook_timeout_invalid_error)
                    isTimeoutValid = false
                } else if (timeout > maxTimeout) {
                    layoutTimeout.error = context.getString(R.string.webhook_timeout_max_error, maxTimeout.toString())
                    isTimeoutValid = false
                } else {
                    layoutTimeout.error = null
                    isTimeoutValid = true
                }
                updateButtonState()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // retries text changed listener
        val layoutRetries = dialogView.findViewById<TextInputLayout>(R.id.editTextRetriesLayout)
        editTextRetries.addTextChangedListener(object : TextWatcher {

            override fun afterTextChanged(s: Editable?) {

                val retries = s.toString().toIntOrNull()

                if (retries == null || retries < 0) {
                    layoutRetries.error = context.getString(R.string.webhook_retries_invalid_error)
                    isRetriesValid = false
                } else if (retries > maxRetries) {
                    layoutRetries.error = context.getString(R.string.webhook_retries_max_error, maxRetries.toString())
                    isRetriesValid = false
                } else {
                    layoutRetries.error = null
                    isRetriesValid = true
                }
                updateButtonState()
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun testWebhook(url: String, method: String, includeLocation: String, timeout: Int, verifyCertificate: Boolean, headers: Map<String, String>) {
        CoroutineScope(Dispatchers.IO).launch{
            try {
                // force retries to 0 when testing
                val config = WebhookConfig(url, method, includeLocation, timeout, 0, verifyCertificate, headers)
                val sender = WebhookSender(context, config)

                // check whether the user wants to include the location in the request
                if (includeLocation != context.getString(R.string.webhook_location_do_not_include)) {

                    Log.d("WebhookConfigManager", "Testing webhook with location")

                    // get the location and then send the request
                    val locationHelper = LocationHelper(context) { _, locationResult ->
                        sender.sendRequest(locationResult, callback = webhookTestCallback())
                    }
                    locationHelper.getLocationAndExecute(ignoreBackgroundPerms = true)

                } else {
                    Log.d("WebhookConfigManager", "Testing webhook without location")
                    sender.sendRequest(null, callback = webhookTestCallback())
                }

            } catch (e: Exception) {
                Log.e("WebhookConfigManager", "Error testing webhook", e)
                DebugLogger.d("WebhookConfigManager", context.getString(R.string.debug_log_webhook_test_error, e.localizedMessage))
            }
        }
   }

    // callback used when testing the webhook to show a toast message with the result
    private fun webhookTestCallback() : WebhookCallback {
        return object : WebhookCallback {
            override fun onSuccess(responseCode: Int) {
                Handler(Looper.getMainLooper()).post {

                    Toast.makeText(context, context.getString(R.string.webhook_request_success_toast_text, responseCode), Toast.LENGTH_SHORT).show()

                    if (::buttonTestWebhook.isInitialized) {
                        buttonTestWebhook.isEnabled = true
                    }
                }
            }

            override fun onFailure(responseCode: Int) {
                Handler(Looper.getMainLooper()).post {

                    Toast.makeText(context, context.getString(R.string.webhook_request_error_code_toast_text, responseCode), Toast.LENGTH_SHORT).show()

                    if (::buttonTestWebhook.isInitialized) {
                        buttonTestWebhook.isEnabled = true
                    }
                }
            }

            override fun onError(errorMessage: String) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.webhook_request_failure_toast_text, errorMessage), Toast.LENGTH_SHORT).show()

                    if (::buttonTestWebhook.isInitialized) {
                        buttonTestWebhook.isEnabled = true
                    }
                }
            }
        }
    }

    // pull the webhook settings from shared preferences and load them into a WebhookConfig object
    fun getWebhookConfig(): WebhookConfig {
        val webhookUrl = sharedPref.getString("webhook_url", "") ?: ""
        val webhookMethod = sharedPref.getString("webhook_method", context.getString(R.string.webhook_get)) ?: context.getString(R.string.webhook_get)
        val includeLocation = sharedPref.getString("webhook_include_location", context.getString(R.string.webhook_location_do_not_include)) ?: context.getString(R.string.webhook_location_do_not_include)
        val timeout = sharedPref.getInt("webhook_timeout", 10)
        val retries = sharedPref.getInt("webhook_retries", 0)
        val verifyCertificate = sharedPref.getBoolean("webhook_verify_certificate", true)

        val headers = mutableMapOf<String, String>()
        val headersStr = sharedPref.getString("webhook_headers", "{}")
        try {
            val headersJson = JSONObject(headersStr ?: "{}")
            headersJson.keys().forEach { key ->
                headers[key] = headersJson.getString(key)
            }
        } catch (e: JSONException) {
            DebugLogger.d("WebhookConfigManager", "Invalid header JSON", e)
        }

        return WebhookConfig(webhookUrl, webhookMethod, includeLocation, timeout, retries, verifyCertificate, headers)
    }


    fun updateButtonState() {

        // make sure the buttons are initialized an then update the state
        val shouldEnable = isWebhookUrlValid && isTimeoutValid && isRetriesValid && areHeadersValid

        if (::dialogPositiveButton.isInitialized) {
            dialogPositiveButton.isEnabled = shouldEnable
        }

        if (::buttonTestWebhook.isInitialized) {
            buttonTestWebhook.isEnabled = shouldEnable
        }

    }

    // adapter to manage the headers in a RecyclerView
    inner class HeaderAdapter : RecyclerView.Adapter<HeaderAdapter.HeaderViewHolder>() {
        private val headers = mutableListOf<Pair<String, String>>()

        inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameInput: TextInputEditText = view.findViewById(R.id.editTextHeaderName)
            val valueInput: TextInputEditText = view.findViewById(R.id.editTextHeaderValue)
            val removeButton: ImageButton = view.findViewById(R.id.buttonRemoveHeader)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.webhook_header_list_item, parent, false)
            return HeaderViewHolder(view)
        }

        override fun onBindViewHolder(holder: HeaderViewHolder, position: Int) {
            val (key, value) = headers[position]

            holder.nameInput.setText(key)
            holder.valueInput.setText(value)

            holder.removeButton.setOnClickListener {

                // need to clear the focus otherwise it might throw the below exception when
                //  loading the view if the focus is on an item that gets moved by the RecyclerView
                // java.lang.IllegalArgumentException: parameter must be a descendant of this view
                holder.nameInput.clearFocus()
                holder.valueInput.clearFocus()

                removeHeader(position)
            }

            holder.nameInput.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {

                    // need to make sure this position is still valid?
                    val adapterPosition = holder.adapterPosition

                    if (adapterPosition != RecyclerView.NO_POSITION) {

                        // if the user changes the key then we need to update the list on our end
                        headers[adapterPosition] = Pair(s.toString(), headers[adapterPosition].second)

                        if (s.toString().isEmpty()) {
                            holder.nameInput.error = context.getString(R.string.webhook_header_name_empty_error)
                        } else if (s.toString().length > maxHeaderKeyLength) {
                            holder.nameInput.error = context.getString(
                                R.string.webhook_header_name_length_error,
                                maxHeaderKeyLength.toString()
                            )
                        } else if (!isValidHeaderName(s.toString())) {
                            holder.nameInput.error = context.getString(R.string.webhook_header_name_invalid_error)
                        } else {
                            holder.nameInput.error = null
                        }

                        updateHeadersAreValid()
                        updateButtonState()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })

            holder.valueInput.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {

                    val adapterPosition = holder.adapterPosition

                    if (adapterPosition != RecyclerView.NO_POSITION) {

                        // if the user changes the value then we need to update the list on our end
                        headers[adapterPosition] = Pair(headers[adapterPosition].first, s.toString())

                        if (s.toString().length > maxHeaderValueLength) {
                            holder.valueInput.error = context.getString(
                                R.string.webhook_header_value_length_error,
                                maxHeaderValueLength.toString()
                            )
                        } else if (!isValidHeaderValue(s.toString())) {
                            holder.valueInput.error = context.getString(R.string.webhook_header_value_invalid_error)
                        } else {
                            holder.valueInput.error = null
                        }

                        updateHeadersAreValid()
                        updateButtonState()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }

        override fun getItemCount() = headers.size

        fun addHeader(key: String, value: String) {
            headers.add(Pair(key, value))
            notifyItemInserted(headers.size - 1)

            // check this when adding a header so that it is immediately detected as invalid
            //  (because it will have a blank name)
            updateHeadersAreValid()

            updateHeaderButtonState()
            updateButtonState()
        }

        private fun removeHeader(position: Int) {
            try {

                // remove from our list of headers and then update the recycler view
                if (position in headers.indices) {
                    headers.removeAt(position)
                    notifyItemRemoved(position)
                    notifyItemRangeChanged(position, headers.size)
                }

                // it is possible that removing a header will make the dialog valid so
                //  update the status here
                updateHeadersAreValid()
                updateHeaderButtonState()
                updateButtonState()

            } catch (e: Exception) {
                Log.e("HeaderAdapter", "Error removing header at position $position.  Headers are: $headers", e)
            }
        }

        fun getHeaders(): Map<String, String> = headers.toMap()

        // update the state of the add header button based on the number of headers
        private fun updateHeaderButtonState() {
            if (::buttonAddHeader.isInitialized) {
                buttonAddHeader.isEnabled = headers.size < maxHeaders
            }
        }

        // check to see if all headers are valid
        private fun updateHeadersAreValid() {
            areHeadersValid = headers.all { isValidHeader(it.first, it.second) }
        }

        // header key must be non empty and have a valid value
        private fun isValidHeader(name: String, value: String): Boolean {
            Log.d("HeaderAdapter", "isValidHeader: $name ${isValidHeaderName(name)} ; $value ${isValidHeaderValue(value)}")
            return isValidHeaderName(name) && isValidHeaderValue(value)
        }

        // RFC 7230 compliant header name validation
        private fun isValidHeaderName(name: String): Boolean {
            return name.isNotEmpty() && name.matches(Regex("^[!#$%&'*+-.^_`|~0-9a-zA-Z]+$")) && name.length <= maxHeaderKeyLength
        }

        // RFC 7230 compliant header value validation
        private fun isValidHeaderValue(value: String): Boolean {
            return value.matches(Regex("^[\\t\\x20-\\x7E\\x80-\\xFF]*$")) && value.length <= maxHeaderValueLength
        }
    }
}

data class WebhookConfig(
    val url: String,
    val method: String,
    val includeLocation: String,
    val timeout: Int,
    val retries: Int,
    val verifyCertificate: Boolean,
    val headers: Map<String, String>
)

interface WebhookCallback {
    fun onSuccess(responseCode: Int)
    fun onFailure(responseCode: Int)
    fun onError(errorMessage: String)
}