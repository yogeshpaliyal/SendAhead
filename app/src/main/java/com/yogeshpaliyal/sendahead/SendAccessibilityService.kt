package com.yogeshpaliyal.sendahead

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
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
        private const val TAG = "SendAhead"
        private const val TIMEOUT_MS = 45_000L
        private const val POLL_MS = 1_000L
        const val WAITING_FOR_UNLOCK = "Waiting for you to unlock the phone"

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
    /** What gets typed into WhatsApp's search box (may fall back to a shorter legacy name). */
    private var searchTerm = ""
    private var triedShortName = false
    /** A matching row must stay at the same place for a moment before it is tapped. */
    private var candidateBounds: Rect? = null
    private var candidateSince = 0L
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

    private fun log(m: String) {
        Log.i(TAG, m)
        DebugLog.log(this, m)
    }

    private fun dumpScreen(reason: String) {
        val active = rootInActiveWindow
        val sb = StringBuilder("SCREEN DUMP ($reason): active=${active?.packageName} locked=${isLocked()}\n")
        sb.append(DebugLog.dump(active))
        runCatching {
            windows.forEach { w ->
                val r = w.root
                if (r != null && r.packageName != active?.packageName) {
                    sb.append("-- window ${r.packageName} type=${w.type}\n").append(DebugLog.dump(r, 80))
                }
            }
        }
        DebugLog.log(this, sb.toString())
    }
    private val queue = ArrayDeque<Long>()
    private var current: ScheduledMessage? = null
    private var clicked = false
    /** Only ONE auto-unlock try per message, so a wrong PIN can never cause a lockout. */
    private var unlockAttempted = false
    /** Swipes done on the lock screen for this message (some phones need a swipe to show the PIN pad). */
    private var swipeCount = 0
    private var lastSwipeAt = 0L
    /** WhatsApp has been opened for the current message. */
    private var launched = false
    /** The phone stayed locked past the timeout; the message waits for the next unlock. */
    private var waitingForUnlock = false

    /** Fires on every unlock (PIN, fingerprint, face, Smart Lock…), however it happened. */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.post { onUnlocked("user present") }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (current == null || clicked || waitingForUnlock) return
            when {
                isLocked() -> tryUnlock()
                !launched -> onUnlocked("poll saw unlocked")
                else -> tryClickSend()
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val timeout = Runnable {
        val msg = current
        if (msg != null && !launched && isLocked()) {
            // Don't fail: keep the message and send it as soon as the phone is unlocked.
            waitingForUnlock = true
            handler.removeCallbacks(poll)
            MessageStore.updateStatus(this, msg.id, Status.SENDING, WAITING_FOR_UNLOCK)
            Notifier.notify(
                this, msg.id, "Unlock to send to ${msg.targetLabel}",
                "Your message is ready. Unlock your phone and it goes out right away.",
            )
            log("Still locked at timeout; waiting for unlock (id=${msg.id})")
            return@Runnable
        }
        current?.let { dumpScreen("timeout, stage=$stage") }
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
        DebugLog.log(this, "Accessibility service connected")
        instance = this
        runCatching {
            ContextCompat.registerReceiver(
                this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
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
        searchTerm = msg.chatName?.trim().orEmpty()
        triedShortName = false
        candidateBounds = null
        candidateSince = 0L
        lastSeen = emptyList()
        launched = false
        waitingForUnlock = false
        MessageStore.updateStatus(this, id, Status.SENDING)
        // Keep the keyboard out of the way while typing into WhatsApp's search (restored in finish()).
        if (msg.byName) runCatching { softKeyboardController.showMode = SHOW_MODE_HIDDEN }
        log("Start send id=$id to=${msg.targetLabel} pkg=${msg.packageName} files=${msg.attachments.size} locked=${isLocked()}")
        if (isLocked()) {
            // Ask Android to dismiss the lock screen (instant for Swipe; shows the PIN prompt otherwise).
            // WhatsApp is opened by onUnlocked(), whichever way the phone actually gets unlocked.
            try {
                startActivity(
                    Intent(this, UnlockActivity::class.java)
                        .putExtra(UnlockActivity.EXTRA_ID, msg.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } catch (e: Exception) {
                log("Unlock screen failed to start: ${e.message}")
            }
        } else if (!launchWhatsApp(msg)) {
            return
        }
        handler.postDelayed(poll, 1_500)
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    /** Opens WhatsApp for [msg]. Returns false (and fails the message) if it can't. */
    private fun launchWhatsApp(msg: ScheduledMessage): Boolean {
        try {
            startActivity(WhatsAppLauncher.buildIntent(this, msg))
            launched = true
            stageAt = System.currentTimeMillis() // settle timers start when WhatsApp actually opens
            log("Opened WhatsApp for id=${msg.id}")
            return true
        } catch (e: ActivityNotFoundException) {
            finish(msg, success = false, error = "Could not open WhatsApp")
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
            finish(msg, success = false, error = e.message ?: "Launch failed")
        }
        return false
    }

    /**
     * Called when the phone becomes unlocked (unlock broadcast, the unlock screen's callback,
     * or the poll noticing). Opens WhatsApp once and restarts the send timer.
     */
    fun onUnlocked(reason: String) {
        val msg = current ?: return
        if (launched || clicked || isLocked()) return
        log("Unlocked ($reason); opening WhatsApp for id=${msg.id}")
        val wasWaiting = waitingForUnlock
        waitingForUnlock = false
        if (wasWaiting) MessageStore.updateStatus(this, msg.id, Status.SENDING, null)
        if (!launchWhatsApp(msg)) return
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(poll)
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
        // Only accept taps on a chat row (not the search box, Back, dial pad, …).
        val isRow = src?.viewIdResourceName?.endsWith("contactpicker_row_container") == true
        // The click event's source often has no children, and its description is "Name, members"
        // (e.g. "Family, You"). Find the row's own name view on screen inside the tapped row instead.
        val byBounds = if (src != null) {
            val rowRect = Rect().also { src.getBoundsInScreen(it) }
            rootInActiveWindow?.let { r ->
                collect(r) { it.viewIdResourceName?.endsWith("contactpicker_row_name") == true && !it.text.isNullOrBlank() }
                    .firstOrNull { n -> Rect().also { b -> n.getBoundsInScreen(b) }.let { rowRect.contains(it) } }
                    ?.text
            }
        } else null
        val raw = byId ?: byBounds ?: if (isRow) src?.contentDescription else null
        // Rows describe themselves as "Name, subtitle" (e.g. "Family, You" or "Mom, Message yourself").
        // Turn that back into the chat's name.
        val name = raw?.let { chatNameFromLabel(clean(it), byId != null || byBounds != null) }
        if (name.isNullOrBlank() || name.length > 100 || name.equals("Search", true)) return true
        pickingPkg = null
        pickedChatName = name
        log("Picked chat: '$name' (event text=${event.text}, id-based=${byId != null}, bounds-based=${byBounds != null})")
        // Bring the edit screen back (finishes WhatsApp's picker if it's in our task) — nothing is sent.
        startActivity(
            Intent(this, EditMessageActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        return true
    }

    /**
     * [label] is either the row's own name ([isName] = true) or its description "Name, subtitle".
     * Prefer a chat name that's actually on screen; otherwise drop everything after the first ", ".
     */
    private fun chatNameFromLabel(label: String, isName: Boolean): String {
        if (isName || !label.contains(", ")) return label
        val onScreen = rootInActiveWindow?.let { r ->
            collect(r) { it.viewIdResourceName?.endsWith("contactpicker_row_name") == true && !it.text.isNullOrBlank() }
                .map { clean(it.text!!) }
        }.orEmpty()
        val match = onScreen.filter { label == it || label.startsWith("$it, ") }.maxByOrNull { it.length }
        log("Pick label '$label' -> on-screen names $onScreen -> ${match ?: "(prefix)"}")
        return match ?: label.substringBefore(", ")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && current == null && capturePick(event)) return
        val msg = current ?: return
        if (clicked) return
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            log("Window: ${event.packageName} / ${event.className}")
        }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in KEYGUARD_PKGS) {
            if (isLocked()) tryUnlock()
            return
        }
        if (pkg != msg.packageName) return
        tryClickSend()
    }

    /**
     * WhatsApp's own window. While the keyboard is up, some phones report the keyboard as the
     * active window, so look through all windows instead of trusting rootInActiveWindow.
     */
    private fun appRoot(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { if (it.packageName?.toString() == pkg) return it }
        return runCatching {
            windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() == pkg }
        }.getOrNull()
    }

    private var lastStateLog = 0L

    /** Every 2s while searching: what the app sees, so a stuck search explains itself in the log. */
    private fun logSearchState(root: AccessibilityNodeInfo, names: Set<String>, rows: List<AccessibilityNodeInfo>) {
        val now = System.currentTimeMillis()
        if (now - lastStateLog < 2_000) return
        lastStateLog = now
        val containers = collect(root) { it.viewIdResourceName?.endsWith("contactpicker_row_container") == true }.size
        val similar = collect(root) { n ->
            val t = norm(n.text); val d = norm(n.contentDescription)
            names.any { w -> t.contains(w) || d.contains(w) }
        }.take(6).joinToString(" | ") { n ->
            "${n.viewIdResourceName?.substringAfter(":id/") ?: n.className?.toString()?.substringAfterLast('.')}" +
                " t='${n.text?.let { clean(it) } ?: ""}' d='${n.contentDescription?.let { clean(it) } ?: ""}'" +
                " click=${n.isClickable} check=${n.isCheckable}/${n.isChecked}"
        }
        log("Search state: wanted=$names matches=${rows.size} rowContainers=$containers similar=[$similar]")
    }

    private fun tryClickSend() {
        val msg = current ?: return
        if (clicked) return
        val root = appRoot(msg.packageName) ?: return
        if (msg.byName) {
            tryNameFlow(msg, root)
            return
        }

        val target = findSendButton(root, msg.packageName) ?: return
        if (DebugSettings.dryRun(this)) {
            stopForDryRun(msg, "Send button found (${target.viewIdResourceName ?: target.contentDescription})")
            return
        }
        if (clickNode(target)) {
            clicked = true
            log("Clicked send (${target.viewIdResourceName ?: target.contentDescription}) for ${msg.id}")
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
                log("Auto-unlock: typing PIN (${secret.value.length} digits)")
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
                    if (!submitted) keyguardRoot()?.let { findEnterKey(it) }?.let { clickNode(it) }
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
        log("Swiped up on lock screen ($swipeCount)")
    }

    private fun typePin(pin: String, index: Int) {
        if (current == null) return
        if (index >= pin.length) {
            // Many lock screens auto-confirm; otherwise press Enter/OK.
            handler.postDelayed({
                if (isLocked()) keyguardRoot()?.let { findEnterKey(it) }?.let { clickNode(it) }
                checkUnlockResult()
            }, 600)
            return
        }
        keyguardRoot()?.let { findDigitKey(it, pin[index]) }?.let { clickNode(it) }
        handler.postDelayed({ typePin(pin, index + 1) }, 150)
    }

    private fun checkUnlockResult() {
        handler.postDelayed({
            log("Auto-unlock result: locked=${isLocked()}")
            if (isLocked()) dumpScreen("after auto-unlock")
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

    /**
     * Send-by-name, built so a wrong chat can never receive the message:
     * wait for WhatsApp's "Send to" screen to settle → type the name into its search → require exactly
     * one matching row → tap it once it has stopped moving → confirm that row (and only that row) is
     * ticked → Send → (attachments) Send again on the preview. Any doubt: back out, send nothing.
     */
    private fun tryNameFlow(msg: ScheduledMessage, root: AccessibilityNodeInfo) {
        val name = msg.chatName?.trim() ?: return
        val now = System.currentTimeMillis()
        val names = acceptedNames(name)
        // Safety: act only on WhatsApp's "Send to" screen. If WhatsApp is showing anything else
        // (chat list, a conversation, settings…), touch nothing and let the timeout fail the message.
        if (!isSharePicker(root, msg.packageName)) {
            if (now - lastOffPickerLog > 3_000) {
                lastOffPickerLog = now
                log("Name flow: not on the Send to screen; waiting without touching anything")
            }
            return
        }
        when (stage) {
            Stage.FIND_CHAT -> {
                // Let the picker finish loading its sections before touching anything.
                if (now - stageAt < 1_200) return
                if (searchField(root, msg.packageName) == null) {
                    val search = root.findAccessibilityNodeInfosByViewId("${msg.packageName}:id/menuitem_search")
                        ?.firstOrNull { it.isVisibleToUser }
                        ?: findNode(root) { n -> norm(n.contentDescription) == "search" }
                    if (search != null && searchClicks < 3) {
                        searchClicks++
                        log("Name flow: search click #$searchClicks ok=${clickNode(search)}")
                    } else {
                        lastSeen = visibleTexts(root)
                    }
                    return
                }
                stage = Stage.SEARCHING
                stageAt = now
            }
            Stage.SEARCHING -> {
                val field = searchField(root, msg.packageName) ?: return
                if (norm(field.text) != norm(searchTerm)) {
                    field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val ok = field.performAction(
                        AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, searchTerm) },
                    )
                    typedSearch = typedSearch || ok
                    log("Name flow: typed '$searchTerm' into search ok=$ok")
                    stageAt = now
                    candidateBounds = null
                    return
                }
                // Give the filtered list time to update.
                if (now - stageAt < 1_000) return
                lastSeen = visibleTexts(root)
                val rows = matchingRows(root, names)
                logSearchState(root, names, rows)
                when {
                    rows.isEmpty() -> {
                        // Older saved names look like "Family, You": retry with just "Family" once.
                        val short = name.substringBeforeLast(", ", "")
                        if (!triedShortName && short.isNotBlank() && now - stageAt > 3_000) {
                            triedShortName = true
                            searchTerm = short
                            log("Name flow: no match for '$name', searching '$short'")
                        }
                    }
                    rows.size > 1 -> {
                        log("Name flow: ${rows.size} chats match '$name'; refusing to guess")
                        abortNameFlow(msg, "More than one chat matches \"$name\". Nothing was sent.")
                    }
                    else -> {
                        val row = rows.single()
                        val bounds = Rect().also { row.getBoundsInScreen(it) }
                        if (bounds != candidateBounds) {
                            candidateBounds = bounds
                            candidateSince = now
                            return
                        }
                        if (now - candidateSince < 400) return
                        if (clickNode(row)) {
                            stage = Stage.SELECTED
                            stageAt = now
                            log("Name flow: tapped row '${rowName(row)}' at $bounds")
                        }
                    }
                }
            }
            Stage.SELECTED -> {
                if (now - stageAt < 600) return
                val rows = matchingRows(root, names)
                val ticked = collect(root) {
                    it.isCheckable && it.isChecked && it.viewIdResourceName?.endsWith("contactpicker_row_container") == true
                }
                val target = rows.singleOrNull()
                val verified = when {
                    target == null -> false
                    target.isCheckable -> target.isChecked && ticked.size == 1
                    // This WhatsApp version doesn't expose ticks: trust the single filtered match.
                    else -> ticked.isEmpty()
                }
                // WhatsApp also lists the selected chats in the footer ("recipients"): it must be exactly ours.
                val recipients = root.findAccessibilityNodeInfosByViewId("${msg.packageName}:id/recipients")
                    ?.firstOrNull()?.text?.let { norm(it) }
                val footerOk = recipients == null || recipients in names
                if (!verified || !footerOk) {
                    if (now - stageAt < 2_500) return // UI may still be catching up
                    log("Name flow: footer recipients='$recipients'")
                    val wrong = ticked.map { rowName(it) }
                    log("Name flow: selection not confirmed. ticked=$wrong target=${target?.let { rowName(it) }}")
                    dumpScreen("selection not confirmed")
                    abortNameFlow(msg, "Couldn't confirm \"$name\" was selected, so nothing was sent.")
                    return
                }
                val send = findSendButton(root, msg.packageName) ?: return
                if (DebugSettings.dryRun(this)) {
                    stopForDryRun(msg, "'${rowName(target!!)}' is the only selected chat and Send is on screen")
                    return
                }
                if (clickNode(send)) {
                    stage = Stage.SENT_ONCE
                    stageAt = now
                    log("Name flow: verified '${rowName(target!!)}' is the only selected chat; clicked send")
                }
            }
            Stage.SENT_ONCE -> {
                if (now - stageAt < 1_500) return
                // With attachments WhatsApp shows a preview screen with its own Send button.
                val send = findSendButton(root, msg.packageName)
                if (send != null) {
                    clickNode(send)
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
     * Debug dry run: everything is done except the final Send tap. WhatsApp is left open so the
     * selection can be checked by eye; the message is marked "Not sent" so it can be rescheduled.
     */
    private fun stopForDryRun(msg: ScheduledMessage, what: String) {
        clicked = true
        log("DRY RUN: stopped before Send. $what")
        dumpScreen("dry run")
        handler.removeCallbacks(poll)
        handler.removeCallbacks(timeout)
        handler.postDelayed({
            clicked = false
            finish(msg, success = false, error = "Dry run: stopped before Send. $what")
        }, 300)
    }

    private var lastOffPickerLog = 0L

    private fun isSharePicker(root: AccessibilityNodeInfo, pkg: String): Boolean =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/contactpicker_row_container")?.isNotEmpty() == true ||
            root.findAccessibilityNodeInfosByViewId("$pkg:id/contact_picker_layout")?.isNotEmpty() == true ||
            root.findAccessibilityNodeInfosByViewId("$pkg:id/search_src_text")?.isNotEmpty() == true

    /** Leave WhatsApp's share screen without sending and fail the message with [reason]. */
    private fun abortNameFlow(msg: ScheduledMessage, reason: String) {
        clicked = true // stop all further taps for this message
        performGlobalAction(GLOBAL_ACTION_BACK)
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 400)
        handler.postDelayed({
            clicked = false
            finish(msg, success = false, error = reason)
        }, 900)
    }

    private fun searchField(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId("$pkg:id/search_src_text")?.firstOrNull { it.isVisibleToUser }
            ?: findEditable(root)

    /** The saved name, plus "Family" for an older saved "Family, You". */
    private fun acceptedNames(name: String): Set<String> {
        val full = norm(name)
        val short = norm(name.substringBeforeLast(", ", ""))
        return if (short.isNotEmpty()) setOf(full, short) else setOf(full)
    }

    private fun rowName(row: AccessibilityNodeInfo): String =
        findNode(row) { it.viewIdResourceName?.endsWith("contactpicker_row_name") == true }?.text?.let { clean(it) }
            ?: row.contentDescription?.let { clean(it) }
            ?: ""

    /**
     * Visible chat rows whose name is one of [names]. Uses the row's own name view; falls back to
     * the row description. Rows are deduplicated by screen position.
     */
    private fun matchingRows(root: AccessibilityNodeInfo, names: Set<String>): List<AccessibilityNodeInfo> {
        // Only rows of WhatsApp's "Send to" list. No fallback: never match anything on other screens.
        val rows = collect(root) { it.viewIdResourceName?.endsWith("contactpicker_row_container") == true }
        return rows.filter { row ->
            val nameView = findNode(row) { it.viewIdResourceName?.endsWith("contactpicker_row_name") == true }
            val label = norm(row.contentDescription) // "name, subtitle", e.g. "sendahead group, you"
            norm(nameView?.text) in names || names.any { n -> label == n || label.startsWith("$n, ") }
        }.distinctBy { Rect().also { r -> it.getBoundsInScreen(r) }.flattenToString() }
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

    /**
     * Clicks [node]: clickable ancestor → the node's own click action → a real tap on its bounds.
     * WhatsApp's "Send to" rows (ListView items, contactpicker_row_container) report clickable=false,
     * so the tap fallback is what makes them work.
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        clickableAncestor(node)?.let { if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return tapOn(node)
    }

    private fun tapOn(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null,
        )
        log("Tap at ${r.centerX()},${r.centerY()} on ${node.viewIdResourceName ?: node.className} ok=$ok")
        return ok
    }

    /** The whole row for a matched name node (bigger, reliable tap target). */
    private fun rowContainer(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n: AccessibilityNodeInfo? = node
        var hops = 0
        while (n != null && hops < 6) {
            if (n.viewIdResourceName?.endsWith("contactpicker_row_container") == true) return n
            n = n.parent
            hops++
        }
        return node
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
        log("Finish id=${msg.id} success=$success error=$error")
        runCatching { softKeyboardController.showMode = SHOW_MODE_AUTO }
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
        launched = false
        waitingForUnlock = false
        // Small gap so WhatsApp settles before the next message.
        handler.postDelayed({ if (current == null) startNext() }, 2_500)
    }
}
