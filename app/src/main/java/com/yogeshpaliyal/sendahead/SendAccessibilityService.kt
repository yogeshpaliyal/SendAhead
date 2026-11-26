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
        /** WhatsApp's message/caption boxes. SendAhead must never type a chat name into these. */
        private val MESSAGE_FIELD_IDS = listOf("link_message_text_entry", "entry", "caption")
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
    private var sawPicker = false
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

    /** Moves the send flow to [next] and logs how long the previous stage took and why it moved. */
    private fun setStage(next: Stage, why: String) {
        val now = System.currentTimeMillis()
        log("Stage $stage -> $next after ${now - stageAt}ms ($why)")
        stage = next
        stageAt = now
    }

    private var sendStartedAt = 0L
    private var eventCount = 0
    private var lastEventSig = ""
    private var lastEventLogAt = 0L

    /** Logs WhatsApp/lock screen events, skipping repeats of the same event within 1s. */
    private fun logEvent(event: AccessibilityEvent) {
        eventCount++
        val type = AccessibilityEvent.eventTypeToString(event.eventType).removePrefix("TYPE_")
        val sig = "$type ${event.packageName} ${event.className}"
        val now = System.currentTimeMillis()
        if (sig == lastEventSig && now - lastEventLogAt < 1_000) return
        lastEventSig = sig
        lastEventLogAt = now
        val txt = event.text?.joinToString("|")?.take(60).orEmpty()
        log("Event #$eventCount $sig${if (txt.isNotBlank()) " text='$txt'" else ""}")
    }

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
                ensureWhatsAppInFront() -> tryClickSend()
                else -> current?.let { traceSend(it, null) } // WhatsApp not in front: still trace
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
                    it.byName && !sawPicker ->
                    "WhatsApp's \"Send to\" screen never appeared. " +
                        "On screen: ${lastSeen.joinToString(" | ").take(400)}"
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
        DebugLog.log(this, "Accessibility service unbound (turned off or killed); current=${current?.id}")
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {
        DebugLog.log(this, "Accessibility service interrupted")
    }

    fun enqueue(id: Long) {
        handler.post {
            log("Enqueue id=$id (current=${current?.id}, queued=${queue.toList()})")
            if (current?.id != id && id !in queue) queue.addLast(id)
            if (current == null) startNext()
        }
    }

    private fun startNext() {
        val id = queue.removeFirstOrNull() ?: return
        val msg = MessageStore.get(this, id)
        if (msg == null || msg.status != Status.PENDING) {
            log("Skip id=$id: ${if (msg == null) "deleted" else "status ${msg.status}"}")
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
        sawPicker = false
        typedSearch = false
        searchTerm = msg.chatName?.trim().orEmpty()
        triedShortName = false
        candidateBounds = null
        candidateSince = 0L
        lastSeen = emptyList()
        launched = false
        waitingForUnlock = false
        launchPending = false
        launchAttempts = 0
        seenWhatsApp = false
        traceStage = null
        dumpedStuckStage = null
        lastTrace = 0L
        MessageStore.updateStatus(this, id, Status.SENDING)
        // Keep the keyboard out of the way while typing into WhatsApp's search (restored in finish()).
        if (msg.byName) runCatching { softKeyboardController.showMode = SHOW_MODE_HIDDEN }
        sendStartedAt = System.currentTimeMillis()
        eventCount = 0
        log("Start send id=$id to=${msg.targetLabel} pkg=${msg.packageName} files=${msg.attachments.size} locked=${isLocked()}")
        log(
            "Message: byName=${msg.byName} chatName='${msg.chatName.orEmpty()}' phone=${if (msg.byName) "-" else "+" + msg.phone} " +
                "textChars=${msg.text.length} textStart='${msg.text.take(30).replace('\n', ' ')}' " +
                "files=[${msg.attachments.joinToString { "${it.mimeType} ${it.displayName}" }}] " +
                "due ${DebugLog.time(msg.timeMillis)} (${(sendStartedAt - msg.timeMillis) / 1000}s ago), queued=${queue.size}",
        )
        log(DebugLog.environment(this))
        log("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}), SendAhead ${BuildConfig.VERSION_NAME}, WhatsApp ${whatsAppVersion(msg.packageName)}")
        // Always go through SendAhead's own invisible screen: Android (and MIUI in particular)
        // silently drops app launches from the background, but allows them from a visible activity.
        // If locked, that screen also asks Android to dismiss the lock screen; WhatsApp is opened
        // once the phone is unlocked, whichever way that happens.
        startLaunchScreen(msg, if (isLocked()) "phone locked" else "phone unlocked")
        handler.postDelayed(poll, 1_500)
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    /** SendAhead's invisible screen that is in front while WhatsApp is being opened. */
    private var launchScreen: java.lang.ref.WeakReference<UnlockActivity>? = null

    fun onLaunchScreenShown(activity: UnlockActivity) {
        launchScreen = java.lang.ref.WeakReference(activity)
        log("Launch screen is in front (locked=${isLocked()})")
    }

    fun onLaunchScreenGone(activity: UnlockActivity) {
        if (launchScreen?.get() === activity) launchScreen = null
    }

    private fun liveLaunchScreen(): UnlockActivity? =
        launchScreen?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun startLaunchScreen(msg: ScheduledMessage, why: String) {
        try {
            startActivity(
                Intent(this, UnlockActivity::class.java)
                    .putExtra(UnlockActivity.EXTRA_ID, msg.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
            log("Started the launch screen ($why)")
        } catch (e: Exception) {
            log("Launch screen failed to start ($why): ${e.message}")
        }
    }

    /** Opens WhatsApp for [msg]. Returns false (and fails the message) if it can't. */
    private fun launchWhatsApp(msg: ScheduledMessage): Boolean {
        try {
            val intent = WhatsAppLauncher.buildIntent(this, msg)
            // Launch from the visible SendAhead screen when there is one: a launch from the
            // background service is dropped on MIUI unless "background pop-ups" is allowed.
            val screen = liveLaunchScreen()
            log(
                "Launch intent: action=${intent.action} type=${intent.type} data=${intent.data?.let { "${it.scheme}://${it.host}${it.path}" }} " +
                    "extras=${intent.extras?.keySet()?.sorted()} flags=0x${Integer.toHexString(intent.flags)}",
            )
            if (screen != null) {
                screen.startActivity(intent)
                screen.finishSoon()
            } else {
                startActivity(intent)
            }
            log("Launch via ${if (screen != null) "the launch screen" else "the background service (may be blocked)"}")
            launched = true
            launchAttempts++
            launchedAt = System.currentTimeMillis()
            seenWhatsApp = false
            stageAt = System.currentTimeMillis() // settle timers start when WhatsApp actually opens
            log("Opened WhatsApp for id=${msg.id} (attempt $launchAttempts)")
            return true
        } catch (e: ActivityNotFoundException) {
            log("Launch failed: no activity (${e.message})")
            finish(msg, success = false, error = "Could not open WhatsApp")
        } catch (e: Exception) {
            Log.e(TAG, "launch failed", e)
            log("Launch failed: ${e.javaClass.simpleName}: ${e.message}")
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
        if (launched || clicked || isLocked() || launchPending) return
        log("Unlocked ($reason); opening WhatsApp for id=${msg.id} shortly")
        val wasWaiting = waitingForUnlock
        waitingForUnlock = false
        if (wasWaiting) MessageStore.updateStatus(this, msg.id, Status.SENDING, null)
        // Let the lock screen finish going away first: on some phones (seen on MIUI) an app opened
        // during that animation is dropped and the previous app comes back instead.
        launchPending = true
        handler.removeCallbacks(poll)
        handler.postDelayed({
            launchPending = false
            if (current?.id != msg.id || launched || clicked) return@postDelayed
            if (isLocked()) {
                log("Locked again before WhatsApp could open; waiting")
                handler.postDelayed(poll, POLL_MS)
                return@postDelayed
            }
            if (!launchWhatsApp(msg)) return@postDelayed
            handler.removeCallbacks(timeout)
            handler.postDelayed(poll, 1_500)
            handler.postDelayed(timeout, TIMEOUT_MS)
        }, 800)
    }

    private var launchPending = false
    private var launchAttempts = 0
    private var launchedAt = 0L
    private var seenWhatsApp = false

    /**
     * WhatsApp was asked to open, but is it actually on screen? If not after a few seconds
     * (the launch was dropped, or another app came back on top), open it again, up to 3 times.
     * Returns true when WhatsApp is showing and the send can continue.
     */
    private fun ensureWhatsAppInFront(): Boolean {
        val msg = current ?: return false
        if (appRoot(msg.packageName) != null) {
            if (!seenWhatsApp) log("WhatsApp is on screen (attempt $launchAttempts)")
            seenWhatsApp = true
            return true
        }
        // Once the send flow has started inside WhatsApp, never reopen it (that could restart a share).
        if (seenWhatsApp) return false
        val waited = System.currentTimeMillis() - launchedAt
        if (waited > 3_500 && launchAttempts < 3 && !isLocked()) {
            log("WhatsApp didn't come to the front after ${waited}ms; opening it again")
            if (liveLaunchScreen() != null) {
                launchWhatsApp(msg)
            } else {
                // Bring SendAhead's screen to the front first, then open WhatsApp from it.
                launched = false
                launchedAt = System.currentTimeMillis()
                startLaunchScreen(msg, "relaunch #$launchAttempts")
            }
        }
        return false
    }

    private fun whatsAppVersion(pkg: String): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0).versionName ?: "?"
    }.getOrDefault("not installed")

    private var lastTrace = 0L
    private var traceStage: Stage? = null
    private var traceStageSince = 0L
    private var dumpedStuckStage: Stage? = null

    /**
     * Every 2s while sending to a group/contact by name: where the flow is and what's on screen.
     * If one stage lasts more than 10s, also saves one screen dump for that stage.
     */
    private fun traceSend(msg: ScheduledMessage, root: AccessibilityNodeInfo?) {
        val now = System.currentTimeMillis()
        if (traceStage != stage) {
            traceStage = stage
            traceStageSince = now
        }
        val inStage = now - traceStageSince
        if (inStage > 10_000 && dumpedStuckStage != stage) {
            dumpedStuckStage = stage
            dumpScreen("stuck in $stage for ${inStage / 1000}s")
        }
        if (now - lastTrace < 2_000) return
        lastTrace = now
        val windowsDesc = windowsDesc()
        val onPicker = root?.let { isSharePicker(it, msg.packageName) }
        val field = root?.let { searchField(it, msg.packageName) }?.text?.let { clean(it) }
        log("Trace: t=${(now - sendStartedAt) / 1000}s locked=${isLocked()} launched=$launched launches=$launchAttempts stage=$stage for ${inStage}ms, sendBtn=${root?.let { findSendButton(it, msg.packageName) != null }}, whatsappRoot=${root != null}, onSendTo=$onPicker, search='${field ?: "-"}', windows=[$windowsDesc]")
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
        } else if (event != null) {
            logEvent(event)
        }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg in KEYGUARD_PKGS) {
            if (isLocked()) tryUnlock()
            return
        }
        if (pkg != msg.packageName) return
        if (launched && !seenWhatsApp) {
            seenWhatsApp = true
            log("WhatsApp is on screen (attempt $launchAttempts)")
        }
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
        val found = appRoot(msg.packageName)
        traceSend(msg, found)
        val root = found ?: return
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
        logUnlockState(root, secret, inputVisible)
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
        val ok = dispatchGesture(gesture, null, null)
        if (!ok) log("Swipe gesture was rejected (gestures allowed=${serviceInfo?.capabilities?.and(32) != 0})")
        log("Swiped up on lock screen ($swipeCount)")
    }

    private fun typePin(pin: String, index: Int) {
        if (current == null) return
        if (index >= pin.length) {
            // Many lock screens auto-confirm; otherwise press Enter/OK.
            handler.postDelayed({
                if (isLocked()) {
                    val enter = keyguardRoot()?.let { findEnterKey(it) }
                    log("Auto-unlock: PIN typed, still locked; enter key ${if (enter != null) "found" else "not found"}")
                    enter?.let { clickNode(it) }
                }
                checkUnlockResult()
            }, 600)
            return
        }
        val key = keyguardRoot()?.let { findDigitKey(it, pin[index]) }
        if (key == null) log("Auto-unlock: key ${index + 1}/${pin.length} not found on screen")
        else if (!clickNode(key)) log("Auto-unlock: key ${index + 1}/${pin.length} click failed")
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
                lastSeen = visibleTexts(root)
                log(
                    "Name flow: not on the Send to screen; waiting without touching anything. " +
                        "class=${root.className} ids=${viewIds(root).take(15)} texts=${lastSeen.take(8)}",
                )
            }
            return
        }
        if (!sawPicker) { sawPicker = true; log("Name flow: on the Send to screen") }
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
                        if (now - lastOffPickerLog > 3_000) {
                            lastOffPickerLog = now
                            log(
                                "Name flow: no search box or search button (clicks=$searchClicks). " +
                                    "ids=${viewIds(root).take(20)} texts=${lastSeen.take(8)}",
                            )
                        }
                    }
                    return
                }
                setStage(Stage.SEARCHING, "search box is open")
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
                        if (row.isCheckable && row.isChecked) {
                            // Already ticked (e.g. left over from an earlier share). Tapping would untick it.
                            setStage(Stage.SELECTED, "the only matching row was already selected; not tapping it")
                            return
                        }
                        if (clickNode(row)) {
                            setStage(Stage.SELECTED, "tapped the only matching row")
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
                if (now - lastVerifyLog > 1_000) {
                    lastVerifyLog = now
                    log("Verify: target=${target?.let { rowName(it) }} checkable=${target?.isCheckable} checked=${target?.isChecked} " +
                        "ticked=${ticked.map { rowName(it) }} footer='${recipients ?: "-"}' matches=${rows.size}")
                }
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
                if (!messageIntact(root, msg)) {
                    dumpScreen("message box changed")
                    abortNameFlow(msg, "The message text was changed on WhatsApp's screen, so nothing was sent.")
                    return
                }
                if (DebugSettings.dryRun(this)) {
                    stopForDryRun(msg, "'${rowName(target!!)}' is the only selected chat, the message is intact and Send is on screen")
                    return
                }
                if (clickNode(send)) {
                    setStage(Stage.SENT_ONCE, "clicked Send on the picker")
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

    /** View ids on screen (without the package prefix), to see which WhatsApp screen this is. */
    private fun viewIds(root: AccessibilityNodeInfo): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30 || out.size >= 60) return
            n.viewIdResourceName?.let { out += it.substringAfter(":id/") }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out.toList()
    }

    private var lastUnlockLog = 0L

    private fun logUnlockState(root: AccessibilityNodeInfo?, secret: UnlockSecret.Secret?, inputVisible: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastUnlockLog < 2_000) return
        lastUnlockLog = now
        log(
            "Lock screen: keyguardWindow=${root?.packageName} inputVisible=$inputVisible autoUnlock=${secret?.type ?: "off"} " +
                "attempted=$unlockAttempted swipes=$swipeCount windows=[${windowsDesc()}]",
        )
    }

    private fun windowsDesc(): String = runCatching {
        windows.joinToString { w -> "${w.root?.packageName ?: "?"}/t${w.type}${if (w.isActive) "*" else ""}" }
    }.getOrDefault("?")

    private var lastOffPickerLog = 0L
    private var lastVerifyLog = 0L

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

    /**
     * WhatsApp's search box, and nothing else. Never fall back to "any text field": on the Send to
     * screen that is the message/caption box (link_message_text_entry), and typing the chat name
     * there replaced the message that was sent.
     */
    private fun searchField(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        root.findAccessibilityNodeInfosByViewId("$pkg:id/search_src_text")?.firstOrNull { it.isVisibleToUser }
            ?.let { return it }
        // Other WhatsApp versions: an editable field that says it is a search box.
        return findNode(root) { n ->
            val id = n.viewIdResourceName.orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString().orEmpty() else ""
            n.isEditable && id !in MESSAGE_FIELD_IDS.map { "$pkg:id/$it" } &&
                (id.contains("search", ignoreCase = true) || hint.contains("search", ignoreCase = true))
        }
    }

    /** The share screen's message box (text shares put the message here). */
    private fun messageField(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? =
        MESSAGE_FIELD_IDS.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")?.firstOrNull { it.isVisibleToUser }
        }

    /**
     * Before Send: the message box must still hold exactly the scheduled text. Puts it back if
     * something changed it; returns false (send nothing) if it can't.
     */
    private fun messageIntact(root: AccessibilityNodeInfo, msg: ScheduledMessage): Boolean {
        val field = messageField(root, msg.packageName) ?: return true // no message box on this screen
        if (msg.attachments.isNotEmpty() && msg.text.isBlank()) return true
        val now = field.text?.toString().orEmpty()
        if (now == msg.text) return true
        log("Message box holds '${now.take(60)}' instead of the scheduled text; restoring it")
        field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg.text) },
        )
        field.refresh()
        val after = field.text?.toString().orEmpty()
        if (after != msg.text) log("Restore failed: message box holds '${after.take(60)}'")
        return after == msg.text
    }

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
        val label = node.viewIdResourceName?.substringAfter(":id/") ?: node.className?.toString()?.substringAfterLast('.')
        clickableAncestor(node)?.let {
            if (it.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                log("Click $label via clickable parent ${it.viewIdResourceName?.substringAfter(":id/") ?: it.className}")
                return true
            }
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            log("Click $label via its own click action")
            return true
        }
        return tapOn(node)
    }

    private fun tapOn(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        coveringWindow(node, r.centerX(), r.centerY())?.let {
            log("Not tapping ${node.viewIdResourceName ?: node.className}: covered by $it (e.g. a notification); will retry")
            return false
        }
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null,
        )
        log("Tap at ${r.centerX()},${r.centerY()} on ${node.viewIdResourceName ?: node.className} ok=$ok")
        return ok
    }

    /**
     * Another app's window (a heads-up notification, a bubble, a system dialog) lying on top of
     * the point a tap would hit. A tap there would land on that window instead.
     */
    private fun coveringWindow(node: AccessibilityNodeInfo, x: Int, y: Int): String? = runCatching {
        val pkg = node.packageName?.toString()
        val all = windows
        val appLayer = all.firstOrNull { it.root?.packageName?.toString() == pkg }?.layer ?: return@runCatching null
        all.firstOrNull { w ->
            val b = Rect().also { w.getBoundsInScreen(it) }
            w.layer > appLayer && b.contains(x, y) &&
                w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                w.root?.packageName?.toString() != pkg
        }?.let { w ->
            val b = Rect().also { w.getBoundsInScreen(it) }
            "${w.root?.packageName ?: "?"} window type ${w.type} at $b"
        }
    }.getOrNull()

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
        log(
            "Finish id=${msg.id} success=$success after ${(System.currentTimeMillis() - sendStartedAt) / 1000}s " +
                "stage=$stage launches=$launchAttempts sawWhatsApp=$seenWhatsApp sawSendTo=$sawPicker " +
                "searchClicks=$searchClicks typed=$typedSearch events=$eventCount error=$error",
        )
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
        launchPending = false
        seenWhatsApp = false
        launchAttempts = 0
        waitingForUnlock = false
        // Small gap so WhatsApp settles before the next message.
        handler.postDelayed({ if (current == null) startNext() }, 2_500)
    }
}
