package com.yogeshpaliyal.wascheduler

import android.content.Context
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
