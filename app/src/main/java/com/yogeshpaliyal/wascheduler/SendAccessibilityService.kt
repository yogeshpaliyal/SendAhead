package com.yogeshpaliyal.wascheduler

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
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

        private val KEYGUARD_PKGS = setOf("com.android.systemui", "com.android.keyguard")

        @Volatile
        var instance: SendAccessibilityService? = null
            private set

        /** Set by the chat picker; read (and cleared) by EditMessageActivity. */
        @Volatile
        var pickedChatName: String? = null
    }

    private enum class Stage { FIND_CHAT, SEARCHING, SELECTED, SENT_ONCE }
    private var stage = Stage.FIND_CHAT
    private var stageAt = 0L
    private var pickingPkg: String? = null
    private var searchClicks = 0
    private var typedSearch = false
    /** What the name flow last saw on screen, for a useful error message. */
    private var lastSeen: List<String> = emptyList()

    /** Removes invisible direction marks WhatsApp wraps names in, and collapses spaces. */
    private fun clean(s: CharSequence): String =
        s.toString()
            .replace(Regex("[\\p{Cf}\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"), "")
            .replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun norm(s: CharSequence?): String = if (s == null) "" else clean(s).lowercase()

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Long>()
    private var current: ScheduledMessage? = null
    private var clicked = false
    /** Only ONE auto-unlock try per message, so a wrong PIN can never cause a lockout. */
    private var unlockAttempted = false
    /** Swipes done on the lock screen for this message (some phones need a swipe to show the PIN pad). */
    private var swipeCount = 0
    private var lastSwipeAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (current == null || clicked) return
            if (isLocked()) tryUnlock() else tryClickSend()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val timeout = Runnable {
        current?.let {
            val error = when {
                isLocked() && unlockAttempted -> "Phone stayed locked (auto-unlock didn't work)"
                isLocked() -> "Phone was locked"
                it.byName && (stage == Stage.FIND_CHAT || stage == Stage.SEARCHING) ->
                    "Chat \"${it.chatName}\" not found in WhatsApp " +
                        "(search ${if (searchClicks > 0) "opened" else "not found"}, " +
                        "${if (typedSearch) "name typed" else "name not typed"}). " +
                        "On screen: ${lastSeen.joinToString(" | ").take(400)}"
                else -> "Send button not found in time"
            }
            finish(it, success = false, error = error)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
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
        unlockAttempted = false
        swipeCount = 0
        lastSwipeAt = 0L
        stage = Stage.FIND_CHAT
        stageAt = System.currentTimeMillis()
        searchClicks = 0
        typedSearch = false
        lastSeen = emptyList()
        MessageStore.updateStatus(this, id, Status.SENDING)
        try {
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (keyguard.isKeyguardLocked) {
                // Dismiss the lock screen first (works for Swipe; shows PIN prompt for secure locks).
                startActivity(
                    Intent(this, UnlockActivity::class.java)
                        .putExtra(UnlockActivity.EXTRA_ID, msg.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } else {
                startActivity(WhatsAppLauncher.buildIntent(this, msg))
            }
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

    /** Chat picker: open WhatsApp's "Send to" list and capture the name of the chat the user taps. */
    fun startPicking(pkg: String) {
        pickingPkg = pkg
        pickedChatName = null
    }

    private fun capturePick(event: AccessibilityEvent): Boolean {
        val pkg = pickingPkg ?: return false
        if (event.packageName?.toString() != pkg) return false
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return true
        val src = event.source
        val byId = src?.let { root ->
            findNode(root) { n ->
                val id = n.viewIdResourceName.orEmpty()
                !n.text.isNullOrBlank() && (id.endsWith("row_name") || id.endsWith("contact_name") || id.endsWith("_name"))
            }?.text
        }
        val raw = byId
            ?: src?.let { root -> findNode(root) { !it.isEditable && !it.text.isNullOrBlank() }?.text }
            ?: event.text.firstOrNull { !it.isNullOrBlank() }
            ?: src?.contentDescription
        val name = raw?.let { clean(it) }
        if (name.isNullOrBlank() || name.length > 100 || name.equals("Search", true)) return true
        pickingPkg = null
        pickedChatName = name
        Log.i(TAG, "Picked chat: $name")
        // Bring the edit screen back (finishes WhatsApp's picker if it's in our task) — nothing is sent.
        startActivity(
            Intent(this, EditMessageActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && current == null && capturePick(event)) return
        val msg = current ?: return
        if (clicked) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in KEYGUARD_PKGS) {
            if (isLocked()) tryUnlock()
            return
        }
        if (pkg != msg.packageName) return
        tryClickSend()
    }

    private fun tryClickSend() {
        val msg = current ?: return
        if (clicked) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != msg.packageName) return
        if (msg.byName) {
            tryNameFlow(msg, root)
            return
        }

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

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    // ---------- Optional auto-unlock (PIN / password) ----------

    private fun keyguardRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() in KEYGUARD_PKGS) return it }
        return windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() in KEYGUARD_PKGS }
    }

    private fun tryUnlock() {
        if (current == null) return
        val secret = UnlockSecret.load(this)
        val root = keyguardRoot()
        val firstDigit = secret?.value?.firstOrNull { it.isDigit() } ?: '1'
        val inputVisible = root != null && (findDigitKey(root, firstDigit) != null || findEditable(root) != null)
        if (!inputVisible) {
            // Lock screen shows the clock first (Samsung, Xiaomi, …): swipe up to bring up the PIN/password screen.
            swipeUp()
            return
        }
        if (root == null || secret == null || unlockAttempted) return
        when (secret.type) {
            UnlockSecret.Type.PIN -> {
                // Wait until the PIN pad is actually on screen.
                if (secret.value.isEmpty() || findDigitKey(root, secret.value[0]) == null) return
                unlockAttempted = true
                typePin(secret.value, 0)
            }
            UnlockSecret.Type.PASSWORD -> {
                val field = findEditable(root) ?: return
                unlockAttempted = true
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, secret.value)
                }
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                handler.postDelayed({
                    val submitted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        (keyguardRoot()?.let { findEditable(it) } ?: field)
                            .performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    if (!submitted) keyguardRoot()?.let { findEnterKey(it) }?.let { clickableAncestor(it) }
                        ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    checkUnlockResult()
                }, 300)
            }
        }
    }

    private fun swipeUp() {
        val now = System.currentTimeMillis()
        if (swipeCount >= 3 || now - lastSwipeAt < 2_000) return
        swipeCount++
        lastSwipeAt = now
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.85f)
            lineTo(x, dm.heightPixels * 0.30f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        dispatchGesture(gesture, null, null)
        Log.i(TAG, "Swiped up on lock screen ($swipeCount)")
    }

    private fun typePin(pin: String, index: Int) {
        if (current == null) return
        if (index >= pin.length) {
            // Many lock screens auto-confirm; otherwise press Enter/OK.
            handler.postDelayed({
                if (isLocked()) keyguardRoot()?.let { findEnterKey(it) }?.let { clickableAncestor(it) }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                checkUnlockResult()
            }, 600)
            return
        }
        keyguardRoot()?.let { findDigitKey(it, pin[index]) }?.let { clickableAncestor(it) }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({ typePin(pin, index + 1) }, 150)
    }

    private fun checkUnlockResult() {
        handler.postDelayed({
            if (isLocked() && UnlockSecret.isEnabled(this)) {
                // Never retry a possibly wrong secret: turn the feature off and tell the user.
                UnlockSecret.clear(this)
                Notifier.notify(
                    this, 1L, "Auto-unlock turned off",
                    "Unlocking didn't work on this lock screen, so auto-unlock was disabled to avoid lockouts. You can set it again in the app.",
                )
            }
        }, 3_000)
    }

    private fun findDigitKey(root: AccessibilityNodeInfo, digit: Char): AccessibilityNodeInfo? {
        for (pkg in KEYGUARD_PKGS) {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/key$digit")
                ?.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        return findNode(root) { n ->
            val t = n.text?.toString()?.trim()
            val d = n.contentDescription?.toString()?.trim()
            t == digit.toString() || d == digit.toString() || (d != null && d.startsWith("$digit,"))
        }
    }

    private fun findEnterKey(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (pkg in KEYGUARD_PKGS) {
            for (id in listOf("key_enter", "key_ok", "delete_button_ok")) {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                    ?.firstOrNull { it.isVisibleToUser }?.let { return it }
            }
        }
        val labels = setOf("enter", "ok", "done", "confirm", "unlock")
        return findNode(root) { n ->
            val d = n.contentDescription?.toString()?.trim()?.lowercase()
            val t = n.text?.toString()?.trim()?.lowercase()
            d in labels || t in labels
        }
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNode(root) { it.isEditable }

    private fun findNode(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (node.isVisibleToUser && predicate(node)) return node
        for (i in 0 until node.childCount) {
            findNode(node.getChild(i), depth + 1, predicate)?.let { return it }
        }
        return null
    }

    // ---------- WhatsApp ----------

    /** Send-by-name: WhatsApp's "Send to" picker → find chat → tap → Send → (preview) Send. */
    private fun tryNameFlow(msg: ScheduledMessage, root: AccessibilityNodeInfo) {
        val name = msg.chatName?.trim() ?: return
        val now = System.currentTimeMillis()
        when (stage) {
            Stage.FIND_CHAT, Stage.SEARCHING -> {
                val wanted = norm(name)
                lastSeen = visibleTexts(root)
                val row = findChatRow(root, wanted)
                if (row != null) {
                    val target = clickableAncestor(row) ?: return
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        stage = Stage.SELECTED
                        stageAt = now
                    }
                    return
                }
                val field = findEditable(root)
                if (stage == Stage.FIND_CHAT || (field == null && now - stageAt > 4_000 && searchClicks < 3)) {
                    if (field == null) {
                        val search = root.findAccessibilityNodeInfosByViewId("${msg.packageName}:id/menuitem_search")
                            ?.firstOrNull { it.isVisibleToUser }
                            ?: findNode(root) { n ->
                                norm(n.contentDescription) == "search" || norm(n.text).startsWith("search")
                            }
                        if (search != null) {
                            clickableAncestor(search)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            searchClicks++
                        }
                    }
                    stage = Stage.SEARCHING
                    stageAt = now
                    return
                }
                if (field == null) return
                if (norm(field.text) != wanted) {
                    field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val ok = field.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name)
                        },
                    )
                    if (ok) typedSearch = true
                }
            }
            Stage.SELECTED -> {
                if (now - stageAt < 700) return
                val send = findSendButton(root, msg.packageName) ?: return
                if (clickableAncestor(send)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    stage = Stage.SENT_ONCE
                    stageAt = now
                }
            }
            Stage.SENT_ONCE -> {
                if (now - stageAt < 1_500) return
                // With attachments WhatsApp shows a preview screen with its own Send button.
                val send = findSendButton(root, msg.packageName)
                if (send != null) {
                    clickableAncestor(send)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else if (now - stageAt < 4_000) {
                    return
                }
                clicked = true
                handler.postDelayed({
                    finish(msg, success = true, error = null)
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }, 2_000)
            }
        }
    }

    /**
     * Finds the chat row for [wanted] (already normalised). Exact match on text or content
     * description first; then a unique row whose label starts with the name (e.g. "Family, 12 members").
     */
    private fun findChatRow(root: AccessibilityNodeInfo, wanted: String): AccessibilityNodeInfo? {
        if (wanted.isEmpty()) return null
        findNode(root) { n ->
            !n.isEditable && (norm(n.text) == wanted || norm(n.contentDescription) == wanted)
        }?.let { return it }
        val prefixed = mutableListOf<AccessibilityNodeInfo>()
        collect(root) { n ->
            !n.isEditable && listOf(norm(n.text), norm(n.contentDescription)).any {
                it.startsWith("$wanted ") || it.startsWith("$wanted,")
            }
        }.let { prefixed += it }
        return prefixed.singleOrNull()
    }

    private fun visibleTexts(root: AccessibilityNodeInfo): List<String> =
        collect(root) { !it.text.isNullOrBlank() && !it.isEditable }
            .mapNotNull { it.text?.let { t -> clean(t) } }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(15)

    private fun collect(
        node: AccessibilityNodeInfo?,
        depth: Int = 0,
        out: MutableList<AccessibilityNodeInfo> = mutableListOf(),
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): List<AccessibilityNodeInfo> {
        if (node == null || depth > 40) return out
        if (node.isVisibleToUser && predicate(node)) out += node
        for (i in 0 until node.childCount) collect(node.getChild(i), depth + 1, out, predicate)
        return out
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
            Notifier.notify(this, msg.id, "Sent to ${msg.targetLabel}", msg.text.ifBlank { "${msg.attachments.size} attachment(s)" })
        } else {
            MessageStore.updateStatus(this, msg.id, Status.FAILED, error)
            Notifier.notify(this, msg.id, "Not sent to ${msg.targetLabel}", error ?: "Unknown error")
        }
        current = null
        clicked = false
        unlockAttempted = false
        // Small gap so WhatsApp settles before the next message.
        handler.postDelayed({ if (current == null) startNext() }, 2_500)
    }
}
