package io.keepalive.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


class LogDisplayActivity : AppCompatActivity() {

    // default text size
    private var textSize = 14f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_display)

        val logsRecyclerView: RecyclerView = findViewById(R.id.logsRecyclerView)
        logsRecyclerView.layoutManager = LinearLayoutManager(this)

        // add a divider between log lines to make it easier to distinguish them
        val dividerItemDecoration = DividerItemDecoration(this, DividerItemDecoration.VERTICAL).apply {
            ContextCompat.getDrawable(this@LogDisplayActivity, R.drawable.log_divider)?.let {
                setDrawable(it)
            }
        }
        logsRecyclerView.addItemDecoration(dividerItemDecoration)

        DebugLogger.d("LogDisplayActivity", getString(R.string.debug_log_log_display_activity_started))

        // load the text size from shared preferences
        val sharedPrefs = getEncryptedSharedPreferences(this.applicationContext)
        textSize = sharedPrefs.getFloat("log_display_text_size", textSize)

        // initialize with default text size
        updateLogs(logsRecyclerView, textSize)

        // update the logs on refresh
        val swipeRefreshLayout: SwipeRefreshLayout = findViewById(R.id.logDisplaySwipeRefreshLayout)
        swipeRefreshLayout.setOnRefreshListener {
            updateLogs(logsRecyclerView, textSize)
            swipeRefreshLayout.isRefreshing = false
        }

        // increase the text size
        findViewById<Button>(R.id.increaseTextSizeButton).setOnClickListener {
            textSize += 1f

            // prevent it from becoming too large
            if (textSize > 48f) textSize = 48f

            updateLogs(logsRecyclerView, textSize)

            // save the text size for better user experience
            saveTextSize(textSize)
        }

        // decrease the text size
        findViewById<Button>(R.id.decreaseTextSizeButton).setOnClickListener {
            textSize -= 1f

            // prevent it from becoming too small
            if (textSize < 2f) textSize = 2f

            updateLogs(logsRecyclerView, textSize)
            saveTextSize(textSize)
        }

        // delete logs
        findViewById<Button>(R.id.clearButton).setOnClickListener {

            // show a dialog to confirm the user wants to delete the logs
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.log_display_delete_dialog_title))
                .setMessage(getString(R.string.log_display_delete_dialog_message))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    DebugLogger.deleteLogs()
                    DebugLogger.d("LogDisplayActivity", getString(R.string.debug_log_logs_deleted))
                    updateLogs(logsRecyclerView, textSize)
                }
                .setNegativeButton(getString(R.string.no), null)
                .show()

        }

        // copy logs to the clipboard
        findViewById<Button>(R.id.copyButton).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Logs", DebugLogger.getLogs().joinToString("\n"))
            clipboard.setPrimaryClip(clip)

            DebugLogger.d("LogDisplayActivity", getString(R.string.debug_log_logs_copied_to_clipboard))

            // update the logs so our message is displayed
            updateLogs(logsRecyclerView, textSize)

            // show a toast
            Toast.makeText(this, getString(R.string.log_display_copy_button_toast), Toast.LENGTH_SHORT).show()
        }
    }

    // save the text size to shared preferences
    private fun saveTextSize(textSize: Float) {
        val sharedPrefs = getEncryptedSharedPreferences(this.applicationContext)
        with (sharedPrefs.edit()) {
            putFloat("log_display_text_size", textSize)
            apply()
        }
    }

    // reload the logs and notify the adapter
    private fun updateLogs(recyclerView: RecyclerView, textSize: Float) {
        recyclerView.adapter = LogsAdapter(DebugLogger.getLogs(), textSize)
        recyclerView.adapter?.notifyDataSetChanged()
    }

    // adapter to display the logs
    class LogsAdapter(private val logs: List<String>, private val textSize: Float) : RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

        class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val logTextView: TextView = view.findViewById(R.id.logItemTextView)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.log_item, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            val log = logs[position]

            // parse the log line and extract the timestamp and message
            val parts = log.split(": ", limit = 2)
            val timestamp = parts[0]
            val message = parts[1]

            // convert the UTC timestamp to user's locale
            val convertedTimestamp = convertUtcToUserLocale(timestamp)
            val newLog = "$convertedTimestamp: $message"

            holder.logTextView.text = newLog
            holder.logTextView.textSize = textSize
        }

        override fun getItemCount() = logs.size

        private fun convertUtcToUserLocale(utcTimestamp: String): String? {

            // input format must match what we use in the DebugLogger
            val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val outputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

            // Set the time zones
            inputFormat.timeZone = TimeZone.getTimeZone("UTC")
            outputFormat.timeZone = TimeZone.getDefault()

            return try {
                // parse the UTC timestamp
                val parsedDate: Date? = inputFormat.parse(utcTimestamp)

                // return the formatted date
                parsedDate?.let { outputFormat.format(it) }
            } catch (e: Exception) {
                Log.e("LogDisplayActivity", "Error parsing the log timestamp?! $utcTimestamp")
                null
            }
        }
    }
}
