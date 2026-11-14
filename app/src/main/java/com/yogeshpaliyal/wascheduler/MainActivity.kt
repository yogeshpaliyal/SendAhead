package com.yogeshpaliyal.wascheduler

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        binding.enableAccessibility.setOnClickListener { showAccessibilityExplainer() }
        binding.allowAlarms.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))
                )
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.accessibilityBanner.visibility = if (isAccessibilityEnabled()) View.GONE else View.VISIBLE
        binding.alarmBanner.visibility = if (Scheduler.canScheduleExact(this)) View.GONE else View.VISIBLE
        val items = MessageStore.all(this).sortedWith(
            compareBy<ScheduledMessage> { it.status != Status.PENDING && it.status != Status.SENDING }
                .thenBy { if (it.status == Status.PENDING) it.timeMillis else -it.timeMillis }
        )
        adapter.submit(items)
        binding.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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
            b.phone.text = "+${m.phone}"
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
