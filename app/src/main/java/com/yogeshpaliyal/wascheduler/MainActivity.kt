package com.yogeshpaliyal.wascheduler

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import android.widget.Button
import android.view.Gravity
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yogeshpaliyal.wascheduler.databinding.ActivityMainBinding
import com.yogeshpaliyal.wascheduler.databinding.ItemMessageBinding
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MENU_AUTO_UNLOCK = 1
        private const val MENU_SHARE_LOG = 2
        private const val MENU_CLEAR_LOG = 3
    }

    private lateinit var binding: ActivityMainBinding
    private val adapter = MessageAdapter()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.fab.setOnClickListener {
            startActivity(Intent(this, EditMessageActivity::class.java))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_AUTO_UNLOCK, 0, "Auto-unlock (PIN / password)")
        menu.add(0, MENU_SHARE_LOG, 1, "Share debug log")
        menu.add(0, MENU_CLEAR_LOG, 2, "Clear debug log")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_AUTO_UNLOCK -> { showAutoUnlockDialog(); return true }
            MENU_SHARE_LOG -> { shareLog(); return true }
            MENU_CLEAR_LOG -> {
                DebugLog.clear(this)
                Toast.makeText(this, "Debug log cleared", Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showAutoUnlockDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val enabled = UnlockSecret.isEnabled(this)
        val info = TextView(this).apply {
            text = (if (enabled) "Status: ON\n\n" else "Status: OFF\n\n") +
                "If the phone is locked when a message is due, the accessibility service types this PIN/password " +
                "on the lock screen.\n\n" +
                "• Stored encrypted with a key in the Android Keystore; app backups are disabled.\n" +
                "• Anyone who can use this app's code on your phone could unlock it. Use only on your own phone.\n" +
                "• Only ONE try per message. If it fails, auto-unlock turns itself off.\n" +
                "• Patterns and fingerprint-only locks are not supported."
        }
        val pin = RadioButton(this).apply { id = View.generateViewId(); text = "PIN"; isChecked = true }
        val password = RadioButton(this).apply { id = View.generateViewId(); text = "Password" }
        val input = EditText(this).apply {
            hint = "Enter PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val type = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(pin)
            addView(password)
            setOnCheckedChangeListener { _, checked ->
                if (checked == password.id) {
                    input.hint = "Enter password"
                    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    input.hint = "Enter PIN"
                    input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                }
            }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(info)
            addView(type)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle("Auto-unlock")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString()
                if (value.isEmpty()) return@setPositiveButton
                val t = if (password.isChecked) UnlockSecret.Type.PASSWORD else UnlockSecret.Type.PIN
                UnlockSecret.save(this, UnlockSecret.Secret(t, value))
                Toast.makeText(this, "Auto-unlock on. Test it with a message 2 min from now.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (enabled) {
            builder.setNeutralButton("Turn off") { _, _ ->
                UnlockSecret.clear(this)
                Toast.makeText(this, "Auto-unlock off", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        renderSetup()
        val items = MessageStore.all(this).sortedWith(
            compareBy<ScheduledMessage> { it.status != Status.PENDING && it.status != Status.SENDING }
                .thenBy { if (it.status == Status.PENDING) it.timeMillis else -it.timeMillis }
        )
        adapter.submit(items)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private data class SetupItem(val ok: Boolean, val title: String, val action: () -> Unit)

    /** SKEDit-style checklist of everything the phone needs for reliable sending. */
    private fun renderSetup() {
        val pm = getSystemService(PowerManager::class.java)
        val items = mutableListOf(
            SetupItem(isAccessibilityEnabled(), "Accessibility service (required)") { showAccessibilityExplainer() },
            SetupItem(pm.isIgnoringBatteryOptimizations(packageName), "Run in background / battery unrestricted (required)") {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            },
            SetupItem(Settings.canDrawOverlays(this), "Display over other apps (needed on many phones)") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            items += SetupItem(Scheduler.canScheduleExact(this), "Alarms & reminders (on-time sending)") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            items += SetupItem(granted, "Notifications (sent / failed alerts)") {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
        val missing = items.filterNot { it.ok }
        val maker = Build.MANUFACTURER.lowercase()
        val aggressiveOem = listOf("xiaomi", "redmi", "poco", "oppo", "vivo", "realme", "oneplus", "tecno", "infinix", "huawei", "honor")
            .any { maker.contains(it) }

        val list = binding.setupList
        list.removeAllViews()
        val pad = (6 * resources.displayMetrics.density).toInt()
        list.addView(TextView(this).apply {
            text = "Finish setup so messages are sent reliably"
            setPadding(0, 0, 0, pad)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        missing.forEach { item ->
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "✗  ${item.title}"
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(context).apply {
                    text = "Fix"
                    setOnClickListener { item.action() }
                })
            })
        }
        if (aggressiveOem) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "ⓘ  ${Build.MANUFACTURER}: in App info enable Autostart and \"Display pop-up windows while running in background\""
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(Button(context).apply {
                    text = "Open"
                    setOnClickListener {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                })
            })
        }
        binding.setupCard.visibility = if (missing.isEmpty() && !aggressiveOem) View.GONE else View.VISIBLE
    }

    private fun shareLog() {
        val f = DebugLog.file(this)
        if (!f.exists() || f.length() == 0L) {
            Toast.makeText(this, "Debug log is empty. Try a send first.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "WA Scheduler debug log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Share debug log (contains screen text from WhatsApp)"))
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
        AlertDialog.Builder(this)
            .setTitle(R.string.accessibility_label)
            .setMessage(
                getString(R.string.accessibility_description) +
                    "\n\nOn the next screen open \"Installed apps\" / \"Downloaded apps\" → WA Scheduler and turn it on." +
                    "\n\nAndroid 13+: if it's greyed out, open App info → ⋮ → \"Allow restricted settings\" first."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("App info") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class MessageAdapter : RecyclerView.Adapter<MessageAdapter.VH>() {
        private var items: List<ScheduledMessage> = emptyList()

        fun submit(list: List<ScheduledMessage>) {
            items = list
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemMessageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val m = items[position]
            val b = holder.b
            b.phone.text = m.targetLabel
            b.time.text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.timeMillis))
            b.text.text = m.text.ifBlank { "(no text)" }
            val app = if (m.packageName == ScheduledMessage.WHATSAPP_BUSINESS) "WhatsApp Business" else "WhatsApp"
            val files = if (m.attachments.isEmpty()) "" else " • ${m.attachments.size} attachment(s)"
            val err = if (m.status == Status.FAILED && m.error != null) "\n${m.error}" else ""
            b.meta.text = "$app$files$err"
            b.status.text = when (m.status) {
                Status.PENDING -> "Scheduled"
                Status.SENDING -> "Sending…"
                Status.SENT -> "Sent ✓"
                Status.FAILED -> "Failed"
            }
            b.retry.visibility = if (m.status == Status.FAILED) View.VISIBLE else View.GONE

            b.root.setOnClickListener {
                if (m.status == Status.PENDING || m.status == Status.FAILED) {
                    startActivity(
                        Intent(this@MainActivity, EditMessageActivity::class.java)
                            .putExtra(EditMessageActivity.EXTRA_ID, m.id)
                    )
                }
            }
            b.retry.setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, EditMessageActivity::class.java)
                        .putExtra(EditMessageActivity.EXTRA_ID, m.id)
                )
            }
            b.delete.setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Delete this scheduled message?")
                    .setPositiveButton("Delete") { _, _ ->
                        Scheduler.cancel(this@MainActivity, m.id)
                        MessageStore.delete(this@MainActivity, m.id)
                        refresh()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
