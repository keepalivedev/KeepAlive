package io.keepalive.android

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.Collections

// store logs in memory as well as on the device
object DebugLogger {
    private val logBuffer = Collections.synchronizedList(mutableListOf<String>())

    // 1024 should be plenty right?
    private const val MAX_BUFFER_SIZE = 1024
    private const val MAX_LINES = 1024

    private lateinit var appContext: Context

    // gets stored in /data/data/io.keepalive.android/files (credential-encrypted)
    private const val LOG_FILE_NAME = "app_debug_logs.txt"

    // gets stored in device-protected storage, available during Direct Boot
    private const val DIRECT_BOOT_LOG_FILE_NAME = "direct_boot_logs.txt"

    // track whether we've already merged Direct Boot logs this session
    private var directBootLogsMerged = false

    // count writes since last trim to avoid reading the file on every d() call
    private var writesSinceLastTrim = 0
    private const val TRIM_CHECK_INTERVAL = 100

    fun initialize(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    // check if credential-encrypted storage is available (i.e. user has unlocked the device).
    // during Direct Boot the file system under /data/user/0 is not accessible so we must
    // write to device-protected storage instead.
    private fun isUserUnlocked(): Boolean {
        if (!::appContext.isInitialized) return false
        return isUserUnlocked(appContext)
    }

    // get the device-protected storage context for writing logs during Direct Boot
    private fun getDeviceProtectedContext(): Context? {
        if (!::appContext.isInitialized) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            appContext.createDeviceProtectedStorageContext()
        } else {
            null
        }
    }

    // write a log entry to the device-protected storage log file (used during Direct Boot)
    private fun writeToDirectBootLog(logMessage: String) {
        try {
            val dpContext = getDeviceProtectedContext() ?: return
            dpContext.openFileOutput(DIRECT_BOOT_LOG_FILE_NAME, Context.MODE_APPEND).use { fos ->
                fos.write((logMessage + "\n").toByteArray())
            }
        } catch (e: Exception) {
            Log.e("DebugLogger", "Error writing to Direct Boot log", e)
        }
    }

    // read all entries from the device-protected storage log file
    private fun readDirectBootLogs(): List<String> {
        val logs = mutableListOf<String>()
        try {
            val dpContext = getDeviceProtectedContext() ?: return logs
            val file = dpContext.getFileStreamPath(DIRECT_BOOT_LOG_FILE_NAME)
            if (file == null || !file.exists()) return logs

            dpContext.openFileInput(DIRECT_BOOT_LOG_FILE_NAME).use { fis ->
                BufferedReader(InputStreamReader(fis)).use { br ->
                    var line = br.readLine()
                    while (line != null) {
                        if (line.isNotBlank()) {
                            logs.add(line)
                        }
                        line = br.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("DebugLogger", "Error reading Direct Boot logs", e)
        }
        return logs
    }

    // delete the device-protected storage log file
    private fun deleteDirectBootLogs() {
        try {
            val dpContext = getDeviceProtectedContext() ?: return
            val file = dpContext.getFileStreamPath(DIRECT_BOOT_LOG_FILE_NAME)
            if (file != null && file.exists()) {
                dpContext.deleteFile(DIRECT_BOOT_LOG_FILE_NAME)
                Log.d("DebugLogger", "Deleted Direct Boot log file")
            }
        } catch (e: Exception) {
            Log.e("DebugLogger", "Error deleting Direct Boot log file", e)
        }
    }

    // merge any Direct Boot logs into the main credential-encrypted log file.
    // called once after the user unlocks the device.
    // @Synchronized because both d() and getLogs() can call this, potentially from
    // different threads. the lock is reentrant so d() (already synchronized) calling
    // this just increments the hold count — no deadlock.
    @Synchronized
    private fun mergeDirectBootLogs() {
        if (directBootLogsMerged) return

        val directBootLogs = readDirectBootLogs()
        if (directBootLogs.isEmpty()) {
            // no Direct Boot logs to merge — set the flag so we don't keep checking
            directBootLogsMerged = true
            Log.d("DebugLogger", "No Direct Boot logs to merge")
            return
        }

        Log.d("DebugLogger", "Merging ${directBootLogs.size} Direct Boot log entries")

        try {
            // read existing logs from the main file
            val existingLogs = mutableListOf<String>()
            val mainFile = appContext.getFileStreamPath(LOG_FILE_NAME)
            if (mainFile != null && mainFile.exists()) {
                appContext.openFileInput(LOG_FILE_NAME).use { fis ->
                    BufferedReader(InputStreamReader(fis)).use { br ->
                        var line = br.readLine()
                        while (line != null) {
                            existingLogs.add(line)
                            line = br.readLine()
                        }
                    }
                }
            }

            Log.d("DebugLogger", "Read ${existingLogs.size} existing log entries from main file")

            // combine both sets of logs and sort by timestamp to maintain chronological order.
            // Direct Boot logs were written between the reboot and unlock, while the main log
            // may already contain post-unlock entries by the time merge runs. the log format
            // is "yyyy-MM-dd HH:mm:ss: message" which sorts correctly as a string.
            val combined = (existingLogs + directBootLogs).sortedBy {
                // extract the timestamp prefix (first 19 chars: "yyyy-MM-dd HH:mm:ss")
                // malformed/short lines sort to the beginning and get trimmed first by takeLast
                it.take(19)
            }

            // trim to MAX_LINES (keep the most recent, which are at the end)
            val trimmed = if (combined.size > MAX_LINES) combined.takeLast(MAX_LINES) else combined

            // rewrite the main log file with the merged+sorted entries
            appContext.openFileOutput(LOG_FILE_NAME, Context.MODE_PRIVATE).use { fos ->
                trimmed.forEach { line ->
                    fos.write((line + "\n").toByteArray())
                }
            }

            Log.d("DebugLogger", "Successfully merged and sorted ${trimmed.size} total log entries")

            // only set the flag and clean up after a successful merge
            directBootLogsMerged = true
            writesSinceLastTrim = 0

            // clean up the Direct Boot log file
            deleteDirectBootLogs()

        } catch (e: Exception) {
            Log.e("DebugLogger", "Error merging Direct Boot logs", e)
            // don't set directBootLogsMerged so we can retry on the next call
        }
    }

    @Synchronized
    fun d(tag: String, message: String, ex: Exception? = null) {

        Log.d(tag, message, ex)

        // build the log message; the timestamp will be stored as UTC
        val dtStr = getDateTimeStrFromTimestamp(System.currentTimeMillis())
        val logMessage = "$dtStr: $message" + (ex?.let { ". Exception: ${it.localizedMessage}" } ?: "")

        // keep tracking logs in memory in case there is some issue writing logs to file?
        addLogToMemory(logMessage)

        if (!::appContext.isInitialized) {

            // don't throw an exception so that the logger doesn't crash the app
            // throw IllegalStateException("DebugLogger is not initialized. Call initialize(context) before logging.")
            return
        }

        // if the user hasn't unlocked the device yet (Direct Boot), credential-encrypted
        // storage is not available so write to device-protected storage instead
        if (!isUserUnlocked()) {
            writeToDirectBootLog(logMessage)
            return
        }

        // user is unlocked - merge any Direct Boot logs that were written before unlock
        mergeDirectBootLogs()

        // only check whether the log file needs trimming periodically to avoid
        // reading the entire file on every d() call
        writesSinceLastTrim++
        if (writesSinceLastTrim >= TRIM_CHECK_INTERVAL) {
            trimLog()
            writesSinceLastTrim = 0
        }

        try {
            // this will create the file if it doesn't exist
            appContext.openFileOutput(LOG_FILE_NAME, Context.MODE_APPEND).use { fos ->
                fos.write((logMessage + "\n").toByteArray())
            }
        } catch (e: IOException) {
            Log.e("DebugLogger", "Error writing log entry to file", e)
        }
    }

    private fun trimLog() {
        if (!isUserUnlocked()) return
        try {
            val file = appContext.getFileStreamPath(LOG_FILE_NAME)
            if (!file.exists()) return

            // get the line count
            val lines = file.readLines()
            if (lines.size > MAX_LINES) {

                // lines are appended so the most recent are at the bottom, so take
                //  the last MAX_LINES lines
                val trimmedLines = lines.takeLast(MAX_LINES)

                // rewrite the log file...
                appContext.openFileOutput(LOG_FILE_NAME, Context.MODE_PRIVATE).use { fos ->
                    trimmedLines.forEach { line ->
                        fos.write((line + "\n").toByteArray())
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("DebugLogger", "Error trimming log file", e)
        }
    }

    private fun addLogToMemory(log: String) {
        synchronized(logBuffer) {
            logBuffer.add(0, log) // Add new log at the beginning
            if (logBuffer.size > MAX_BUFFER_SIZE) {
                logBuffer.removeAt(logBuffer.size - 1) // Remove oldest log
            }
        }
    }

    fun getLogs(): List<String> {

        // if there is an error return the logs in memory instead
        if (!::appContext.isInitialized) {
            return logBuffer.toList()
        }

        // if user is locked, return Direct Boot logs + memory logs
        if (!isUserUnlocked()) {
            val directBootLogs = readDirectBootLogs()
            return if (directBootLogs.isNotEmpty()) {
                // return reversed so newest is first, same as the normal behavior
                directBootLogs.reversed()
            } else {
                logBuffer.toList()
            }
        }

        // user is unlocked - merge any Direct Boot logs first
        mergeDirectBootLogs()

        val logs = mutableListOf<String>()
        try {

            // check if the file exists, if not then return the logs in memory
            if (!appContext.getFileStreamPath(LOG_FILE_NAME).exists()) {
                return logBuffer.toList()
            }

            appContext.openFileInput(LOG_FILE_NAME).use { fis ->
                BufferedReader(InputStreamReader(fis)).use { br ->
                    var line = br.readLine()
                    while (line != null) {
                        logs.add(line)
                        line = br.readLine()
                    }
                }
            }
        } catch (e: IOException) {
            Log.e("DebugLogger", "Error reading log file", e)
        }

        Log.d("DebugLogger", "Returning ${logs.size} logs")

        // return the logs in reverse order so that the newest logs are at the top
        // if there are issues with the logs saved to file then return the memory logs instead
        return if (logs.size > 0) logs.toList().reversed() else logBuffer.toList()
    }

    fun deleteLogs() {
        if (!::appContext.isInitialized) {
            return
        }
        try {
            // clear the logs in memory
            logBuffer.clear()

            // also delete the Direct Boot log file if it exists
            deleteDirectBootLogs()

            // only delete the main log file if credential-encrypted storage is available
            if (!isUserUnlocked()) return

            // delete the logfile
            val fileDeleted = appContext.deleteFile(LOG_FILE_NAME)
            if (!fileDeleted) {
                Log.e("DebugLogger", "Log file could not be deleted.")
            }
        } catch (e: Exception) {
            Log.e("DebugLogger", "Error deleting log file", e)
        }
    }
}

