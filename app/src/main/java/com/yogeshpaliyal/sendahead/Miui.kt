package com.yogeshpaliyal.sendahead

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings

/**
 * Xiaomi/Redmi/POCO (MIUI, HyperOS) have their own permissions on top of Android's.
 * "Open new windows while running in the background" must be on, or MIUI silently drops
 * WhatsApp launches that SendAhead makes when it isn't on screen.
 */
object Miui {
    private const val OP_BACKGROUND_START_ACTIVITY = 10021
    private const val OP_SHOW_WHEN_LOCKED = 10020

    val isMiui: Boolean by lazy {
        val maker = Build.MANUFACTURER.lowercase()
        listOf("xiaomi", "redmi", "poco").any { maker.contains(it) }
    }

    /** true/false when it can be read, null when unknown (not MIUI, or the check isn't available). */
    fun backgroundStartAllowed(ctx: Context): Boolean? = checkOp(ctx, OP_BACKGROUND_START_ACTIVITY)

    fun showWhenLockedAllowed(ctx: Context): Boolean? = checkOp(ctx, OP_SHOW_WHEN_LOCKED)

    private fun checkOp(ctx: Context, op: Int): Boolean? {
        if (!isMiui) return null
        return runCatching {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            (m.invoke(ops, op, Process.myUid(), ctx.packageName) as Int) == AppOpsManager.MODE_ALLOWED
        }.getOrNull()
    }

    /** MIUI's "Other permissions" page for this app, or the normal app settings page. */
    fun openPermissions(ctx: Context) {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            .putExtra("extra_pkgname", ctx.packageName)
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
        try {
            ctx.startActivity(miui)
        } catch (_: Exception) {
            ctx.startActivity(fallback)
        }
    }
}
