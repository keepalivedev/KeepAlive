package io.keepalive.android

import android.Manifest
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

object TestSetupUtil {

    private var permissions = listOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SYSTEM_ALERT_WINDOW
    )

    fun setupTestEnvironment() {
        println("Setting up test environment...")

        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

        cmdExec("appops set ${targetContext.packageName} android:get_usage_stats allow")
        cmdExec("appops set ${targetContext.packageName} android:schedule_exact_alarm allow")
        cmdExec("appops set ${targetContext.packageName} AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore")

        // grant usage stats permissions
        cmdExec("appops set ${targetContext.packageName} android:get_usage_stats allow")

        // grant schedule exact alarm permissions
        cmdExec("appops set ${targetContext.packageName} android:schedule_exact_alarm allow")

        // disable app hibernation restrictions
        cmdExec("appops set ${targetContext.packageName} AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.O) {
            permissions += Manifest.permission.READ_PHONE_STATE
        }

        permissions.forEach { permission ->
            println("Granting permission: $permission")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // For API 28 and above
                InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                    targetContext.packageName,
                    permission
                )
            } else {
                // For API 27 and below
                cmdExec("pm grant ${targetContext.packageName} $permission")
            }
        }

        println("Test environment setup complete.")
    }

    private fun cmdExec(cmd: String): String {
        println("Executing command: $cmd")
        val parcelFileDescriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        val inputStream = FileInputStream(parcelFileDescriptor.fileDescriptor)
        val reader = BufferedReader(InputStreamReader(inputStream))
        val stringBuilder = StringBuilder()

        var line: String? = reader.readLine()
        while (line != null) {
            stringBuilder.append(line)
            stringBuilder.append("\n")
            line = reader.readLine()
        }

        reader.close()
        inputStream.close()
        parcelFileDescriptor.close()

        return stringBuilder.toString()
    }
}