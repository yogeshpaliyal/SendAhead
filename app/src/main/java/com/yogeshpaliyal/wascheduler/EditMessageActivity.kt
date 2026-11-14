package com.yogeshpaliyal.wascheduler

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat as AndroidDateFormat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yogeshpaliyal.wascheduler.databinding.ActivityEditMessageBinding
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class EditMessageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
    }

    private lateinit var binding: ActivityEditMessageBinding
    private var messageId: Long = System.currentTimeMillis()
    private var isNew = true
    private val attachments = mutableListOf<Attachment>()
    private val calendar: Calendar = Calendar.getInstance().apply {
        add(Calendar.MINUTE, 5)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { copyIn(it) }
        renderAttachments()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditMessageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val existingId = intent.getLongExtra(EXTRA_ID, -1L)
        val existing = if (existingId != -1L) MessageStore.get(this, existingId) else null
        if (existing != null) {
            isNew = false
            messageId = existing.id
            binding.phone.setText(existing.phone)
            binding.message.setText(existing.text)
            attachments += existing.attachments
            if (existing.packageName == ScheduledMessage.WHATSAPP_BUSINESS) binding.appBusiness.isChecked = true
            if (existing.timeMillis > System.currentTimeMillis()) calendar.timeInMillis = existing.timeMillis
            title = "Edit message"
        } else if (!WhatsAppLauncher.isInstalled(this, ScheduledMessage.WHATSAPP) &&
            WhatsAppLauncher.isInstalled(this, ScheduledMessage.WHATSAPP_BUSINESS)
        ) {
            binding.appBusiness.isChecked = true
        }

        binding.addAttachments.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        binding.clearAttachments.setOnClickListener {
            attachments.clear()
            renderAttachments()
        }
        binding.pickDate.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                calendar.set(y, m, d)
                renderTime()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.minDate = System.currentTimeMillis() - 1000
            }.show()
        }
        binding.pickTime.setOnClickListener {
            TimePickerDialog(this, { _, h, min ->
                calendar.set(Calendar.HOUR_OF_DAY, h)
                calendar.set(Calendar.MINUTE, min)
                calendar.set(Calendar.SECOND, 0)
                renderTime()
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), AndroidDateFormat.is24HourFormat(this)).show()
        }
        binding.save.setOnClickListener { save(sendNow = false) }
        binding.sendNow.setOnClickListener { save(sendNow = true) }

        renderAttachments()
        renderTime()
    }

    private fun renderTime() {
        binding.pickDate.text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(calendar.time)
        binding.pickTime.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(calendar.time)
    }

    private fun renderAttachments() {
        binding.attachments.text = if (attachments.isEmpty()) {
            "No attachments"
        } else {
            attachments.joinToString(separator = "\n", prefix = "Attachments:\n") { "• ${it.displayName}" }
        }
    }

    /** Copies the picked file into app storage so it's still there at send time. */
    private fun copyIn(uri: Uri) {
        try {
            val name = queryName(uri) ?: "file"
            val mime = contentResolver.getType(uri) ?: "*/*"
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val fileName = "${attachments.size}_${System.nanoTime()}_$safe"
            val dest = File(MessageStore.attachmentDir(this, messageId), fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return
            attachments += Attachment(fileName, mime, name)
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't add file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun save(sendNow: Boolean) {
        val phone = binding.phone.text?.toString().orEmpty().filter { it.isDigit() }
        val text = binding.message.text?.toString().orEmpty()
        if (phone.length < 8) {
            binding.phone.error = "Enter number with country code, e.g. 919876543210"
            return
        }
        if (text.isBlank() && attachments.isEmpty()) {
            Toast.makeText(this, "Add a message or an attachment", Toast.LENGTH_SHORT).show()
            return
        }
        val time = if (sendNow) System.currentTimeMillis() else calendar.timeInMillis
        if (!sendNow && time < System.currentTimeMillis()) {
            Toast.makeText(this, "Pick a time in the future", Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = if (binding.appBusiness.isChecked) ScheduledMessage.WHATSAPP_BUSINESS else ScheduledMessage.WHATSAPP
        if (!WhatsAppLauncher.isInstalled(this, pkg)) {
            Toast.makeText(this, "That WhatsApp app isn't installed", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = ScheduledMessage(
            id = messageId,
            phone = phone,
            text = text,
            attachments = attachments.toList(),
            timeMillis = time,
            packageName = pkg,
            status = Status.PENDING,
        )
        MessageStore.upsert(this, msg)
        // Remove files that were picked and then cleared.
        val keep = attachments.map { it.fileName }.toSet()
        MessageStore.attachmentDir(this, messageId).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }

        if (sendNow) {
            Scheduler.cancel(this, msg.id)
            val service = SendAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
                Scheduler.schedule(this, msg)
            } else {
                service.enqueue(msg.id)
            }
        } else {
            Scheduler.schedule(this, msg)
            Toast.makeText(
                this,
                "Scheduled for " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time)),
                Toast.LENGTH_SHORT,
            ).show()
        }
        isNew = false
        finish()
    }

    override fun onDestroy() {
        // Discard copied files if a brand-new message was never saved.
        if (isNew && isFinishing) File(filesDir, "attachments/$messageId").deleteRecursively()
        super.onDestroy()
    }
}
