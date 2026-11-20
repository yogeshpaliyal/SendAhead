package com.yogeshpaliyal.sendahead

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat as AndroidDateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.AttrRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.widget.doAfterTextChanged
import com.yogeshpaliyal.sendahead.databinding.SheetAutoUnlockBinding
import com.yogeshpaliyal.sendahead.databinding.ActivityMainBinding
import com.yogeshpaliyal.sendahead.databinding.ItemHeaderBinding
import com.yogeshpaliyal.sendahead.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.google.android.material.R as M

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MENU_AUTO_UNLOCK = 1
        private const val MENU_SHARE_LOG = 2
        private const val MENU_CLEAR_LOG = 3
        private const val MENU_DRY_RUN = 4
    }

    private lateinit var binding: ActivityMainBinding
    private val adapter = MessageAdapter()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderSetup() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (IntroActivity.shouldShow(this)) {
            startActivity(Intent(this, IntroActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.fab.setOnClickListener { startActivity(Intent(this, EditMessageActivity::class.java)) }
        // Extended FAB shrinks to an icon while scrolling down and grows back when scrolling up.
        binding.scroll.setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, oldY: Int ->
            if (y > oldY + 4 && binding.fab.isExtended) binding.fab.shrink()
            else if (y < oldY - 4 && !binding.fab.isExtended) binding.fab.extend()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_AUTO_UNLOCK, 0, "Auto-unlock")
        menu.add(0, MENU_SHARE_LOG, 1, "Share debug log")
        menu.add(0, MENU_CLEAR_LOG, 2, "Clear debug log")
        if (BuildConfig.DEBUG) {
            menu.add(0, MENU_DRY_RUN, 3, "Dry run (stop before Send)").isCheckable = true
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_DRY_RUN)?.isChecked = DebugSettings.dryRun(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_AUTO_UNLOCK -> { showAutoUnlockDialog(); return true }
            MENU_SHARE_LOG -> { shareLog(); return true }
            MENU_DRY_RUN -> {
                val on = !DebugSettings.dryRun(this)
                DebugSettings.setDryRun(this, on)
                Toast.makeText(
                    this,
                    if (on) "Dry run on: messages stop before Send" else "Dry run off: messages are sent",
                    Toast.LENGTH_SHORT,
                ).show()
                refresh()
                return true
            }
            MENU_CLEAR_LOG -> {
                DebugLog.clear(this)
                Toast.makeText(this, "Debug log cleared", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // ---------- List ----------

    private fun refresh() {
        renderSetup()
        val all = MessageStore.all(this)
        val upcoming = all.filter { it.status == Status.PENDING || it.status == Status.SENDING }.sortedBy { it.timeMillis }
        val history = all.filter { it.status == Status.SENT || it.status == Status.FAILED }.sortedByDescending { it.timeMillis }

        val rows = mutableListOf<Row>()
        upcoming.groupBy { dayLabel(it.timeMillis) }.forEach { (label, msgs) ->
            rows += Row.Header(label)
            msgs.forEachIndexed { i, m -> rows += Row.Item(m, first = i == 0, last = i == msgs.lastIndex) }
        }
        if (history.isNotEmpty()) {
            rows += Row.Header("Sent and failed")
            history.forEachIndexed { i, m -> rows += Row.Item(m, first = i == 0, last = i == history.lastIndex) }
        }
        adapter.submit(rows)

        binding.empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        val summary = when {
            upcoming.isEmpty() && all.isNotEmpty() -> "No messages waiting to send."
            upcoming.isEmpty() -> ""
            else -> {
                val next = upcoming.first()
                val count = if (upcoming.size == 1) "1 message waiting" else "${upcoming.size} messages waiting"
                "$count. Next one goes to ${next.targetLabel} ${relativeWhen(next.timeMillis)}."
            }
        }
        // Make a forgotten dry run impossible to miss.
        val shown = if (DebugSettings.dryRun(this)) {
            "Dry run is on: messages stop before Send. Turn it off in the menu.\n\n$summary".trim()
        } else summary
        binding.summary.text = shown
        binding.summary.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun dayLabel(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val diff = daysBetween(Calendar.getInstance(), c)
        return when {
            diff <= 0 -> "Today"
            diff == 1 -> "Tomorrow"
            diff < 7 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(c.time)
            else -> SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(c.time)
        }
    }

    private fun relativeWhen(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        val (t, ampm) = formatTime(this, millis)
        val time = if (ampm.isEmpty()) t else "$t $ampm"
        return when (daysBetween(Calendar.getInstance(), c)) {
            0 -> "today at $time"
            1 -> "tomorrow at $time"
            else -> "on ${SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(c.time)} at $time"
        }
    }

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        fun dayStart(c: Calendar) = (c.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return ((dayStart(b) - dayStart(a)) / 86_400_000L).toInt()
    }

    private sealed class Row {
        data class Header(val label: String) : Row()
        data class Item(val msg: ScheduledMessage, val first: Boolean, val last: Boolean) : Row()
    }

    private inner class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(list: List<Row>) {
            rows = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        inner class HeaderVH(val b: ItemHeaderBinding) : RecyclerView.ViewHolder(b.root)
        inner class ItemVH(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1
        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderVH(ItemHeaderBinding.inflate(inf, parent, false))
            else ItemVH(ItemMessageBinding.inflate(inf, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).b.header.text = row.label
                is Row.Item -> bindItem((holder as ItemVH).b, row)
            }
        }

        private fun bindItem(b: ItemMessageBinding, row: Row.Item) {
            val m = row.msg
            val ctx = b.root.context

            // Connected group: big outer corners, tight inner corners (M3 Expressive list grouping).
            val big = dp(24f)
            val small = dp(6f)
            b.card.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setTopLeftCornerSize(if (row.first) big else small)
                .setTopRightCornerSize(if (row.first) big else small)
                .setBottomLeftCornerSize(if (row.last) big else small)
                .setBottomRightCornerSize(if (row.last) big else small)
                .build()

            val (time, ampm) = formatTime(ctx, m.timeMillis)
            b.time.text = time
            val isHistory = m.status == Status.SENT || m.status == Status.FAILED
            val sub = if (isHistory) {
                listOf(ampm, SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(m.timeMillis)))
                    .filter { it.isNotEmpty() }.joinToString(", ")
            } else ampm
            b.meridiem.text = sub
            b.meridiem.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE

            b.recipient.text = m.targetLabel
            b.recipient.setCompoundDrawablesRelativeWithIntrinsicBounds(if (m.byName) R.drawable.ic_group else 0, 0, 0, 0)
            b.recipient.compoundDrawableTintList = ColorStateList.valueOf(themeColor(ctx, M.attr.colorOnSurfaceVariant))
            b.text.text = m.text.ifBlank { "No text, attachments only" }

            val app = if (m.packageName == ScheduledMessage.WHATSAPP_BUSINESS) "WhatsApp Business" else "WhatsApp"
            val files = when (m.attachments.size) {
                0 -> ""
                1 -> ", 1 file"
                else -> ", ${m.attachments.size} files"
            }
            b.meta.text = "$app$files"

            val (label, bgAttr, fgAttr) = when (m.status) {
                Status.PENDING -> Triple("Waiting", M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer)
                Status.SENDING -> if (m.error == SendAccessibilityService.WAITING_FOR_UNLOCK) {
                    Triple("Waiting for unlock", M.attr.colorTertiaryContainer, M.attr.colorOnTertiaryContainer)
                } else {
                    Triple("Sending", androidx.appcompat.R.attr.colorPrimary, M.attr.colorOnPrimary)
                }
                Status.SENT -> Triple("Sent", M.attr.colorPrimaryContainer, M.attr.colorOnPrimaryContainer)
                Status.FAILED -> Triple("Not sent", M.attr.colorErrorContainer, M.attr.colorOnErrorContainer)
            }
            b.status.text = label
            b.status.backgroundTintList = ColorStateList.valueOf(themeColor(ctx, bgAttr))
            b.status.setTextColor(themeColor(ctx, fgAttr))

            // Finished messages step back; upcoming ones keep full contrast.
            b.time.setTextColor(themeColor(ctx, if (isHistory) M.attr.colorOnSurfaceVariant else M.attr.colorOnSurface))

            val failed = m.status == Status.FAILED
            b.error.visibility = if (failed && m.error != null) View.VISIBLE else View.GONE
            b.error.text = m.error
            b.retry.visibility = if (failed) View.VISIBLE else View.GONE

            val editable = m.status == Status.PENDING || m.status == Status.FAILED
            b.card.setOnClickListener { if (editable) openEditor(m.id) }
            b.retry.setOnClickListener { openEditor(m.id) }
            b.more.setOnClickListener { v ->
                PopupMenu(ctx, v).apply {
                    if (editable) menu.add(0, 1, 0, "Edit")
                    menu.add(0, 2, 1, "Delete")
                    setOnMenuItemClickListener {
                        when (it.itemId) {
                            1 -> openEditor(m.id)
                            2 -> confirmDelete(m)
                        }
                        true
                    }
                }.show()
            }
        }
    }

    private fun openEditor(id: Long) {
        startActivity(Intent(this, EditMessageActivity::class.java).putExtra(EditMessageActivity.EXTRA_ID, id))
    }

    private fun confirmDelete(m: ScheduledMessage) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete message to ${m.targetLabel}?")
            .setMessage(if (m.status == Status.PENDING) "It won't be sent." else "It will be removed from this list.")
            .setPositiveButton("Delete") { _, _ ->
                Scheduler.cancel(this, m.id)
                MessageStore.delete(this, m.id)
                refresh()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    // ---------- Setup checklist ----------

    private data class SetupItem(val ok: Boolean, val title: String, val why: String, val action: () -> Unit)

    private fun renderSetup() {
        val pm = getSystemService(PowerManager::class.java)
        val items = mutableListOf(
            SetupItem(isAccessibilityEnabled(), "Turn on auto-send", "Lets the app tap Send in WhatsApp") {
                showAccessibilityExplainer()
            },
            SetupItem(pm.isIgnoringBatteryOptimizations(packageName), "Allow running in background", "Stops the battery saver from closing the app") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            SetupItem(Settings.canDrawOverlays(this), "Allow display over other apps", "Needed to open WhatsApp from the background") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            items += SetupItem(Scheduler.canScheduleExact(this), "Allow alarms and reminders", "Sends on the minute, not minutes late") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            items += SetupItem(granted, "Allow notifications", "Tells you when a message was sent or failed") {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        // Screen lock: like SKEDit's "Bypass screen lock". Only shown when the phone has a secure lock.
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isDeviceSecure) {
            val lockOk = UnlockSecret.isEnabled(this) || lockSetupSkipped()
            items += SetupItem(lockOk, "Send while the phone is locked", "Save your PIN or password so the app can unlock it at send time") {
                showAutoUnlockDialog()
            }
        }
        val missing = items.filterNot { it.ok }
        val maker = Build.MANUFACTURER.lowercase()
        val aggressiveOem = listOf("xiaomi", "redmi", "poco", "oppo", "vivo", "realme", "oneplus", "tecno", "infinix", "huawei", "honor")
            .any { maker.contains(it) }

        binding.setupCard.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE
        if (missing.isEmpty()) return

        val onCard = themeColor(this, M.attr.colorOnTertiaryContainer)
        val list = binding.setupList
        list.removeAllViews()
        list.addView(TextView(this).apply {
            text = "Finish setup"
            setTextAppearance(M.style.TextAppearance_Material3_TitleLarge)
            setTextColor(onCard)
        })
        val done = items.size - missing.size
        list.addView(TextView(this).apply {
            text = "$done of ${items.size} done. Messages may not go out until the rest are on."
            setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onCard)
            setPadding(0, dp(4f).toInt(), 0, dp(12f).toInt())
        })
        list.addView(LinearProgressIndicator(this).apply {
            max = items.size
            progress = done
            trackCornerRadius = dp(4f).toInt()
            trackThickness = dp(6f).toInt()
            setIndicatorColor(themeColor(context, M.attr.colorTertiary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8f).toInt() }
        })
        missing.forEach { item -> list.addView(setupRow(item.title, item.why, "Fix", onCard, item.action)) }
        if (aggressiveOem) {
            list.addView(
                setupRow(
                    "Allow autostart",
                    "${Build.MANUFACTURER} phones also need Autostart and pop-ups from background, in App info",
                    "Open", onCard,
                ) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            )
        }
    }

    private fun setupRow(title: String, why: String, cta: String, onCard: Int, action: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f).toInt(), 0, 0)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_error)
                imageTintList = ColorStateList.valueOf(onCard)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt()).apply { marginEnd = dp(12f).toInt() }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = title
                    setTextAppearance(M.style.TextAppearance_Material3_TitleSmall)
                    setTextColor(onCard)
                })
                addView(TextView(context).apply {
                    text = why
                    setTextAppearance(M.style.TextAppearance_Material3_BodySmall)
                    setTextColor(onCard)
                })
            })
            addView(MaterialButton(context).apply {
                text = cta
                backgroundTintList = ColorStateList.valueOf(themeColor(context, M.attr.colorTertiary))
                setTextColor(themeColor(context, M.attr.colorOnTertiary))
                setOnClickListener { action() }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(8f).toInt() }
            })
        }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val cn = ComponentName(this, SendAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    }

    private fun showAccessibilityExplainer() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn on auto-send")
            .setMessage(
                getString(R.string.accessibility_description) +
                    "\n\nOn the next screen, open Installed apps (or Downloaded apps), choose SendAhead and turn it on." +
                    "\n\nIf the switch is greyed out, open App info, tap the menu at the top right and choose Allow restricted settings, then try again."
            )
            .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNeutralButton("App info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ---------- Auto-unlock ----------

    private fun showAutoUnlockDialog() {
        val sheet = BottomSheetDialog(this)
        val b = SheetAutoUnlockBinding.inflate(layoutInflater)
        sheet.setContentView(b.root)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true

        val enabled = UnlockSecret.isEnabled(this)
        b.status.text = if (enabled) "On" else "Off"
        b.status.backgroundTintList = ColorStateList.valueOf(
            themeColor(this, if (enabled) M.attr.colorPrimaryContainer else M.attr.colorSurfaceContainerHighest)
        )
        b.status.setTextColor(themeColor(this, if (enabled) M.attr.colorOnPrimaryContainer else M.attr.colorOnSurfaceVariant))

        // Rules as icon rows rather than a paragraph.
        val onCard = themeColor(this, M.attr.colorOnSurfaceVariant)
        listOf(
            R.drawable.ic_lock to "Encrypted with a key that never leaves this phone",
            R.drawable.ic_refresh to "One try per message. If it fails, auto-unlock turns itself off",
            R.drawable.ic_error to "Patterns and fingerprint-only locks aren't supported",
        ).forEach { (icon, text) ->
            b.rules.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(onCard)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = LinearLayout.LayoutParams(dp(20f).toInt(), dp(20f).toInt()).apply { marginEnd = dp(14f).toInt() }
                })
                addView(TextView(context).apply {
                    this.text = text
                    setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(onCard)
                })
            })
        }

        fun isPassword() = b.typeGroup.checkedButtonId == R.id.typePassword
        fun applyType(password: Boolean) {
            b.secretLayout.hint = if (password) "Your password" else "Your PIN"
            b.secretLayout.error = null
            b.secret.inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            b.secret.setSelection(b.secret.text?.length ?: 0)
        }
        // checkedId from the callback: isChecked can be stale while the group switches.
        b.typeGroup.addOnButtonCheckedListener { _, id, checked -> if (checked) applyType(id == R.id.typePassword) }
        applyType(false)

        b.save.text = if (enabled) "Update" else "Turn on"
        b.save.setIconResource(R.drawable.ic_check)
        b.save.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
        b.save.setOnClickListener {
            val value = b.secret.text?.toString().orEmpty()
            val password = isPassword()
            when {
                value.isEmpty() -> {
                    b.secretLayout.error = if (password) "Enter your password" else "Enter your PIN"
                    return@setOnClickListener
                }
                !password && (value.length < 4 || !value.all { it.isDigit() }) -> {
                    b.secretLayout.error = "A PIN is at least 4 digits"
                    return@setOnClickListener
                }
            }
            val t = if (password) UnlockSecret.Type.PASSWORD else UnlockSecret.Type.PIN
            UnlockSecret.save(this, UnlockSecret.Secret(t, value))
            sheet.dismiss()
            Toast.makeText(this, "Auto-unlock is on. Test it with a message a few minutes from now.", Toast.LENGTH_LONG).show()
            renderSetup()
        }
        b.secret.doAfterTextChanged { b.secretLayout.error = null }

        b.secondary.text = if (enabled) "Turn off" else "Don't use"
        b.secondary.setOnClickListener {
            if (enabled) UnlockSecret.clear(this)
            setLockSetupSkipped(true)
            sheet.dismiss()
            Toast.makeText(
                this,
                if (enabled) "Auto-unlock is off" else "OK. Messages due while the phone is locked will wait for you to unlock it.",
                Toast.LENGTH_LONG,
            ).show()
            renderSetup()
        }

        sheet.show()
    }

    private fun lockSetupSkipped() =
        getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("lock_setup_skipped", false)

    private fun setLockSetupSkipped(v: Boolean) =
        getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("lock_setup_skipped", v).apply()

    private fun shareLog() {
        val f = DebugLog.file(this)
        if (!f.exists() || f.length() == 0L) {
            Toast.makeText(this, "The debug log is empty. Try sending a message first.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "SendAhead debug log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share debug log (includes text from your WhatsApp screen)"))
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}

/** Theme color lookup used by both screens. */
fun themeColor(ctx: Context, @AttrRes attr: Int): Int {
    val tv = TypedValue()
    ctx.theme.resolveAttribute(attr, tv, true)
    return if (tv.resourceId != 0) ContextCompat.getColor(ctx, tv.resourceId) else tv.data
}

/** ("9:30", "pm") in 12-hour locales, ("21:30", "") in 24-hour ones. */
fun formatTime(ctx: Context, millis: Long): Pair<String, String> {
    val d = Date(millis)
    return if (AndroidDateFormat.is24HourFormat(ctx)) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(d) to ""
    } else {
        SimpleDateFormat("h:mm", Locale.getDefault()).format(d) to
            SimpleDateFormat("a", Locale.getDefault()).format(d).lowercase(Locale.getDefault())
    }
}
