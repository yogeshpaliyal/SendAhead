package com.yogeshpaliyal.wascheduler

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager

/**
 * Invisible activity shown over the lock screen. It asks Android to dismiss the keyguard
 * (instant for "Swipe"/"None"; shows the PIN/pattern prompt for secure locks) and then
 * opens WhatsApp for the scheduled message. The accessibility service does the rest.
 */
class UnlockActivity : Activity() {

    companion object {
        const val EXTRA_ID = "message_id"
        private const val TAG = "WaScheduler"
    }

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            launchWhatsApp()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = launchWhatsApp()
                override fun onDismissCancelled() = finish()
                override fun onDismissError() = finish()
            })
        } else {
            // FLAG_DISMISS_KEYGUARD handles non-secure locks; give it a moment.
            Handler(Looper.getMainLooper()).postDelayed({ launchWhatsApp() }, 600)
        }
    }

    private fun launchWhatsApp() {
        if (launched) return
        launched = true
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        val msg = MessageStore.get(this, id)
        if (msg != null) {
            try {
                startActivity(WhatsAppLauncher.buildIntent(this, msg))
            } catch (e: Exception) {
                // The service's timeout will mark the message as failed.
                Log.e(TAG, "launch failed", e)
            }
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
