package com.yogeshpaliyal.sendahead

import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-device step log so failures can be diagnosed. Never leaves the phone unless the user shares it. */
object DebugLog {
    private const val MAX_BYTES = 400_000L
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun file(ctx: Context): File = File(ctx.filesDir, "logs/debug.log").apply { parentFile?.mkdirs() }

    fun log(ctx: Context, msg: String) = synchronized(lock) {
        try {
            val f = file(ctx)
            if (f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "debug.old.log")
                old.delete()
                f.renameTo(old)
            }
            file(ctx).appendText("${fmt.format(Date())} $msg\n")
        } catch (_: Exception) {
        }
    }

    private val whenFmt = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)

    /** Readable time, e.g. "24 Nov 12:00:00". */
    fun time(millis: Long): String = synchronized(lock) { whenFmt.format(Date(millis)) }

    /**
     * One line describing everything about the phone that can stop a send: screen, lock,
     * battery saver, Doze, battery optimisation, exact alarms, accessibility, auto-unlock, dry run.
     */
    fun environment(ctx: Context): String = runCatching {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0) "${l * 100 / s}%" else "?"
        } ?: "?"
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val exact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() else true
        val a11y = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(ctx.packageName) == true
        val unlock = UnlockSecret.load(ctx)?.type?.name ?: "off"
        "Env: screenOn=${pm.isInteractive} locked=${km.isKeyguardLocked} secureLock=${km.isKeyguardSecure} " +
            "battery=$level plugged=$plugged powerSave=${pm.isPowerSaveMode} doze=${pm.isDeviceIdleMode} " +
            "ignoresBatteryOpt=${pm.isIgnoringBatteryOptimizations(ctx.packageName)} exactAlarms=$exact " +
            "a11yEnabled=$a11y a11yConnected=${SendAccessibilityService.instance != null} " +
            "autoUnlock=$unlock dryRun=${DebugSettings.dryRun(ctx)}"
    }.getOrElse { "Env: unavailable (${it.message})" }

    fun clear(ctx: Context) = synchronized(lock) {
        file(ctx).delete()
        File(file(ctx).parentFile, "debug.old.log").delete()
    }

    /** Text dump of a window's view tree: class, view id, text, description, flags. */
    fun dump(node: AccessibilityNodeInfo?, maxLines: Int = 250): String {
        val sb = StringBuilder()
        var lines = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 35 || lines >= maxLines) return
            lines++
            sb.append("  ".repeat(depth))
                .append(n.className?.toString()?.substringAfterLast('.') ?: "?")
            n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
            n.text?.takeIf { it.isNotBlank() }?.let { sb.append(" t=\"").append(it.toString().take(60)).append('"') }
            n.contentDescription?.takeIf { it.isNotBlank() }?.let { sb.append(" d=\"").append(it.toString().take(60)).append('"') }
            if (n.isClickable) sb.append(" [click]")
            if (n.isEditable) sb.append(" [edit]")
            if (!n.isVisibleToUser) sb.append(" [hidden]")
            sb.append('\n')
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(node, 0)
        return sb.toString()
    }
}
