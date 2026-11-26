package com.yogeshpaliyal.sendahead

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
        private const val TAG = "SendAhead"
    }

    private var launched = false
    private val handler = Handler(Looper.getMainLooper())

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
        SendAccessibilityService.instance?.onLaunchScreenShown(this)
        // Never linger: WhatsApp normally opens from here within a second or two.
        handler.postDelayed({ if (!isFinishing) finish() }, 20_000)

        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            onUnlocked()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = onUnlocked()
                override fun onDismissCancelled() {
                    DebugLog.log(this@UnlockActivity, "Launch screen: unlock cancelled")
                    finish()
                }
                override fun onDismissError() {
                    DebugLog.log(this@UnlockActivity, "Launch screen: unlock error")
                    finish()
                }
            })
        } else {
            // FLAG_DISMISS_KEYGUARD handles non-secure locks; give it a moment.
            handler.postDelayed({ onUnlocked() }, 600)
        }
    }

    /** The phone is unlocked. Stay visible: the service opens WhatsApp from this screen. */
    private fun onUnlocked() {
        if (launched) return
        launched = true
        val service = SendAccessibilityService.instance
        if (service != null) {
            service.onUnlocked("unlock screen callback")
        } else {
            val msg = MessageStore.get(this, intent.getLongExtra(EXTRA_ID, -1L))
            if (msg != null) {
                try {
                    startActivity(WhatsAppLauncher.buildIntent(this, msg))
                } catch (e: Exception) {
                    Log.e(TAG, "launch failed", e)
                }
            }
            finishSoon()
        }
    }

    /** Called right after WhatsApp was started from here. */
    fun finishSoon() {
        handler.postDelayed({
            if (!isFinishing) {
                finish()
                overridePendingTransition(0, 0)
            }
        }, 1_500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        SendAccessibilityService.instance?.onLaunchScreenGone(this)
        super.onDestroy()
    }
}
