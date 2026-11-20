package com.yogeshpaliyal.sendahead

import android.content.Context

/** Developer switches. Only exist in debug builds; always off in release. */
object DebugSettings {
    private const val PREFS = "settings"
    private const val KEY_DRY_RUN = "debug_dry_run"

    /** Do everything up to the final Send tap, then stop and leave WhatsApp open. */
    fun dryRun(ctx: Context): Boolean =
        BuildConfig.DEBUG && ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DRY_RUN, false)

    fun setDryRun(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DRY_RUN, on).apply()
    }
}
