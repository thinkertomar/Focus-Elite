package com.focusguardpro.app

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Porter / Shizuku Bridge for FocusGuard Pro.
 *
 * Implements Progressive Enhancement with Graceful Fallback:
 * - If Porter (or Shizuku) is active and authorized, executes privileged shell commands (UID 2000)
 *   such as force-stopping distracted apps, suspending packages, and auto-reviving Accessibility.
 * - If Porter is NOT installed, NOT running, or unprivileged, calls return false safely without
 *   crashing or altering the core application workflow.
 */
object PorterBridge {
    private const val TAG = "PorterBridge"
    const val REQUEST_CODE_PORTER_PERMISSION = 7011

    private var isBinderAlive = false

    private fun log(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {}
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        isBinderAlive = true
        log("Porter/Shizuku binder connected")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        isBinderAlive = false
        log("Porter/Shizuku binder disconnected")
    }

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (t: Throwable) {
            log("Could not register Shizuku lifecycle listeners: ${t.message}")
        }
    }

    /**
     * Checks if the Porter/Shizuku server is running and reachable.
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if this app has been granted ADB shell permission by the user in Porter/Shizuku.
     */
    fun isPermissionGranted(): Boolean {
        if (!isAvailable()) return false
        return try {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True only if Porter is alive AND granted.
     */
    fun isEnhancedModeActive(): Boolean {
        return isAvailable() && isPermissionGranted()
    }

    /**
     * Requests authorization prompt via Porter/Shizuku Manager.
     */
    fun requestPermission(activity: Activity, requestCode: Int = REQUEST_CODE_PORTER_PERMISSION) {
        try {
            if (isAvailable() && !isPermissionGranted()) {
                Shizuku.requestPermission(requestCode)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to request Porter permission", t)
        }
    }

    /**
     * Executes an ADB shell command via Porter/Shizuku.
     * Wrapped in a timeout and catches all errors.
     */
    fun executeShell(vararg command: String, timeoutSec: Long = 3): ShellResult {
        if (!isEnhancedModeActive()) {
            return ShellResult(success = false, exitCode = -1, output = "Porter not active")
        }
        return try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }

            val process = newProcessMethod.invoke(null, command, null, null) as java.lang.Process
            val completed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(timeoutSec, TimeUnit.SECONDS)
            } else {
                process.waitFor()
                true
            }

            if (!completed) {
                process.destroy()
                return ShellResult(success = false, exitCode = -1, output = "Command timed out")
            }

            val exitCode = process.exitValue()
            val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            ShellResult(success = exitCode == 0, exitCode = exitCode, output = output.trim())
        } catch (t: Throwable) {
            Log.w(TAG, "Shell execution failed: ${t.message}")
            ShellResult(success = false, exitCode = -1, output = t.message ?: "Execution error")
        }
    }

    /**
     * Force stops an app completely.
     * Equivalent to `am force-stop <package>`.
     */
    fun forceStopPackage(packageName: String): Boolean {
        if (!isEnhancedModeActive()) return false
        val result = executeShell("am", "force-stop", packageName)
        return result.success
    }

    /**
     * Hard-suspends an app package on modern Android.
     * Suspended apps are grayed out on launcher, cannot run, and cannot send notifications.
     */
    fun suspendPackage(packageName: String): Boolean {
        if (!isEnhancedModeActive()) return false
        val result = executeShell("pm", "suspend", packageName)
        return result.success
    }

    /**
     * Unsuspends a previously suspended app.
     */
    fun unsuspendPackage(packageName: String): Boolean {
        if (!isEnhancedModeActive()) return false
        val result = executeShell("pm", "unsuspend", packageName)
        return result.success
    }

    /**
     * Auto-revives BlockerAccessibilityService if the system or an aggressive OEM killed it.
     */
    fun ensureAccessibilityServiceEnabled(context: Context): Boolean {
        if (!isEnhancedModeActive()) return false
        return try {
            val component = "${context.packageName}/${BlockerAccessibilityService::class.java.canonicalName}"
            val currentResult = executeShell("settings", "get", "secure", "enabled_accessibility_services")
            val current = currentResult.output
            if (!current.contains(component)) {
                val updated = if (current.isBlank() || current == "null") component else "$current:$component"
                executeShell("settings", "put", "secure", "enabled_accessibility_services", updated).success &&
                executeShell("settings", "put", "secure", "accessibility_enabled", "1").success
            } else {
                true
            }
        } catch (t: Throwable) {
            false
        }
    }

    data class ShellResult(
        val success: Boolean,
        val exitCode: Int,
        val output: String
    )
}
