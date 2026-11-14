package com.yogeshpaliyal.wascheduler

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Only job: when a scheduled message is due, open WhatsApp with the chat/media prefilled
 * and tap its Send button. It ignores everything while no message is being sent.
 */
class SendAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WaScheduler"
        private const val TIMEOUT_MS = 45_000L
        private const val POLL_MS = 1_000L

        @Volatile
        var instance: SendAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Long>()
    private var current: ScheduledMessage? = null
    private var clicked = false

    private val poll = object : Runnable {
        override fun run() {
            if (current == null || clicked) return
            tryClickSend()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val timeout = Runnable {
        current?.let { finish(it, success = false, error = "Send button not found in time") }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    fun enqueue(id: Long) {
        handler.post {
            if (current?.id != id && id !in queue) queue.addLast(id)
            if (current == null) startNext()
        }
    }

    private fun startNext() {
        val id = queue.removeFirstOrNull() ?: return
        val msg = MessageStore.get(this, id)
        if (msg == null || msg.status != Status.PENDING) {
            startNext()
            return
        }
        if (!WhatsAppLauncher.isInstalled(this, msg.packageName)) {
            current = msg
            finish(msg, success = false, error = "${msg.packageName} is not installed")
            return
        }
        current = msg
        clicked = false
        MessageStore.updateStatus(this, id, Status.SENDING)
        try {
            startActivity(WhatsAppLauncher.buildIntent(this, msg))
        } catch (e: ActivityNotFoundException) {
            finish(msg, success = false, error = "Could not open WhatsApp")
            return
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
            finish(msg, success = false, error = e.message ?: "Launch failed")
            return
        }
        handler.postDelayed(poll, 1_500)
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val msg = current ?: return
        if (clicked) return
        if (event?.packageName?.toString() != msg.packageName) return
        tryClickSend()
    }

    private fun tryClickSend() {
        val msg = current ?: return
        if (clicked) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != msg.packageName) return

        val target = findSendButton(root, msg.packageName) ?: return
        val clickable = clickableAncestor(target) ?: return
        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            clicked = true
            Log.i(TAG, "Clicked send for ${msg.id}")
            // Give WhatsApp a moment to actually send before leaving the chat.
            handler.postDelayed({
                finish(msg, success = true, error = null)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 2_000)
        }
    }

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        // 1. Known view ids (chat screen + media preview screen).
        for (id in listOf("$pkg:id/send", "$pkg:id/send_btn", "$pkg:id/send_button")) {
            root.findAccessibilityNodeInfosByViewId(id)
                ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
                ?.let { return it }
        }
        // 2. Fallback: anything labelled "Send" (content description or text), e.g. confirm dialogs.
        return findByLabel(root)
    }

    private fun findByLabel(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        val desc = node.contentDescription?.toString()?.trim()
        val text = node.text?.toString()?.trim()
        if (node.isVisibleToUser && node.isEnabled &&
            (desc.equals("Send", ignoreCase = true) || text.equals("Send", ignoreCase = true))
        ) return node
        for (i in 0 until node.childCount) {
            findByLabel(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 5) {
            if (n.isClickable) return n
            n = n.parent
            hops++
        }
        return null
    }

    private fun finish(msg: ScheduledMessage, success: Boolean, error: String?) {
        handler.removeCallbacks(poll)
        handler.removeCallbacks(timeout)
        if (success) {
            MessageStore.updateStatus(this, msg.id, Status.SENT)
            Notifier.notify(this, msg.id, "Sent to +${msg.phone}", msg.text.ifBlank { "${msg.attachments.size} attachment(s)" })
        } else {
            MessageStore.updateStatus(this, msg.id, Status.FAILED, error)
            Notifier.notify(this, msg.id, "Not sent to +${msg.phone}", error ?: "Unknown error")
        }
        current = null
        clicked = false
        // Small gap so WhatsApp settles before the next message.
        handler.postDelayed({ if (current == null) startNext() }, 2_500)
    }
}
