package com.yogeshpaliyal.sendahead

import android.content.Intent
import android.view.View
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.OpenableColumns
import android.telephony.TelephonyManager
import android.text.format.DateFormat as AndroidDateFormat
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import com.yogeshpaliyal.sendahead.databinding.ActivityEditMessageBinding
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class EditMessageActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "id"
        /** Open a new message pre-filled from this one (recipient, app, text, attachments). */
        const val EXTRA_COPY_FROM = "copy_from"
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

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@registerForActivityResult
        try {
            contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val number = toWhatsAppNumber(c.getString(0).orEmpty())
                    binding.targetGroup.check(R.id.targetPhone)
                    binding.phone.setText(number)
                    renderTarget()
                    Toast.makeText(this, "${c.getString(1)}: +$number — check the country code", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't read contact: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** "+91 98765 43210" / "098765 43210" / "9876543210" -> "919876543210" (best effort). */
    private fun toWhatsAppNumber(raw: String): String {
        val trimmed = raw.trim()
        var digits = trimmed.filter { it.isDigit() }
        if (trimmed.startsWith("+")) return digits
        if (digits.startsWith("00")) return digits.drop(2)
        val iso = (getSystemService(TelephonyManager::class.java)?.simCountryIso ?: "").lowercase()
        val cc = mapOf(
            "in" to "91", "us" to "1", "ca" to "1", "gb" to "44", "ae" to "971", "au" to "61",
            "de" to "49", "sg" to "65", "sa" to "966", "pk" to "92", "bd" to "880", "np" to "977",
        )[iso] ?: return digits
        if (digits.startsWith("0")) digits = digits.drop(1)
        return if (digits.startsWith(cc) && digits.length > 10) digits else cc + digits
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
            if (existing.byName) {
                binding.targetGroup.check(R.id.targetName)
                binding.chatName.setText(existing.chatName)
            }
            binding.message.setText(existing.text)
            attachments += existing.attachments
            if (existing.packageName == ScheduledMessage.WHATSAPP_BUSINESS) binding.appGroup.check(R.id.appBusiness)
            if (existing.timeMillis > System.currentTimeMillis()) calendar.timeInMillis = existing.timeMillis
            binding.toolbar.title = "Edit message"
        } else if (copyFrom(intent.getLongExtra(EXTRA_COPY_FROM, -1L))) {
            binding.toolbar.title = "Copy message"
        } else if (!WhatsAppLauncher.isInstalled(this, ScheduledMessage.WHATSAPP) &&
            WhatsAppLauncher.isInstalled(this, ScheduledMessage.WHATSAPP_BUSINESS)
        ) {
            binding.appBusiness.isChecked = true
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        // Use the id from the callback: when switching, the new button reports checked
        // before the old one is unchecked, so reading isChecked here gives a stale answer.
        binding.targetGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) renderTarget(byName = checkedId == R.id.targetName)
        }
        binding.chatNameLayout.setEndIconOnClickListener { pickChat() }
        binding.phoneLayout.setEndIconOnClickListener {
            try {
                pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
            } catch (e: Exception) {
                Toast.makeText(this, "No contacts app found", Toast.LENGTH_SHORT).show()
            }
        }
        renderTarget()

        binding.addAttachments.setOnClickListener { pickFiles.launch(arrayOf("*/*")) }
        binding.dateTile.setOnClickListener { showDatePicker() }
        binding.timeTile.setOnClickListener { showTimePicker() }
        setupQuickTimes()
        binding.save.setOnClickListener { save(sendNow = false) }
        binding.sendNow.setOnClickListener { save(sendNow = true) }

        renderAttachments()
        renderTime()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Returned from the WhatsApp chat picker; keep the original intent (EXTRA_ID).
    }

    override fun onResume() {
        super.onResume()
        SendAccessibilityService.pickedChatName?.let {
            SendAccessibilityService.pickedChatName = null
            binding.targetGroup.check(R.id.targetName)
            binding.chatName.setText(it)
            renderTarget()
        }
    }

    private fun showDatePicker() {
        // MaterialDatePicker works in UTC midnights.
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
        }
        val todayUtc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            val now = Calendar.getInstance()
            clear()
            set(now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH))
        }
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText("Send on")
            .setSelection(utc.timeInMillis)
            .setCalendarConstraints(
                CalendarConstraints.Builder()
                    .setStart(todayUtc.timeInMillis)
                    .setValidator(DateValidatorPointForward.from(todayUtc.timeInMillis))
                    .build()
            )
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            val picked = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sel }
            calendar.set(picked.get(Calendar.YEAR), picked.get(Calendar.MONTH), picked.get(Calendar.DAY_OF_MONTH))
            renderTime()
        }
        picker.show(supportFragmentManager, "date")
    }

    private fun showTimePicker() {
        val picker = MaterialTimePicker.Builder()
            .setTitleText("Send at")
            .setTimeFormat(if (AndroidDateFormat.is24HourFormat(this)) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(calendar.get(Calendar.HOUR_OF_DAY))
            .setMinute(calendar.get(Calendar.MINUTE))
            .build()
        picker.addOnPositiveButtonClickListener {
            calendar.set(Calendar.HOUR_OF_DAY, picker.hour)
            calendar.set(Calendar.MINUTE, picker.minute)
            calendar.set(Calendar.SECOND, 0)
            renderTime()
        }
        picker.show(supportFragmentManager, "time")
    }

    private fun setupQuickTimes() {
        fun label(h: Int): String {
            val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, 0) }
            val (t, ampm) = formatTime(this, c.timeInMillis)
            return if (ampm.isEmpty()) t else "$t $ampm"
        }
        val now = Calendar.getInstance()
        binding.chipTonight.text = "Tonight, ${label(21)}"
        binding.chipTonight.visibility = if (now.get(Calendar.HOUR_OF_DAY) < 20) View.VISIBLE else View.GONE
        binding.chipTomorrow.text = "Tomorrow, ${label(9)}"

        binding.chipInHour.setOnClickListener {
            calendar.timeInMillis = System.currentTimeMillis() + 60 * 60_000L
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            renderTime()
        }
        binding.chipTonight.setOnClickListener {
            calendar.timeInMillis = System.currentTimeMillis()
            calendar.set(Calendar.HOUR_OF_DAY, 21); calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
            renderTime()
        }
        binding.chipTomorrow.setOnClickListener {
            calendar.timeInMillis = System.currentTimeMillis()
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            calendar.set(Calendar.HOUR_OF_DAY, 9); calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
            renderTime()
        }
    }

    private fun selectedPackage() =
        if (binding.appBusiness.isChecked) ScheduledMessage.WHATSAPP_BUSINESS else ScheduledMessage.WHATSAPP

    private fun isByName() = binding.targetGroup.checkedButtonId == R.id.targetName

    private fun renderTarget(byName: Boolean = isByName()) {
        binding.chatNameLayout.visibility = if (byName) View.VISIBLE else View.GONE
        binding.phoneLayout.visibility = if (byName) View.GONE else View.VISIBLE
    }

    private fun pickChat() {
        val service = SendAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "Enable the accessibility service first", Toast.LENGTH_LONG).show()
            return
        }
        val pkg = selectedPackage()
        if (!WhatsAppLauncher.isInstalled(this, pkg)) {
            Toast.makeText(this, "That WhatsApp app isn't installed", Toast.LENGTH_SHORT).show()
            return
        }
        service.startPicking(pkg)
        Toast.makeText(this, "Tap the group or contact. Nothing will be sent.", Toast.LENGTH_LONG).show()
        startActivity(WhatsAppLauncher.buildPickerIntent(pkg))
    }

    private fun renderTime() {
        val today = Calendar.getInstance()
        val tomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        binding.dayName.text = when {
            sameDay(calendar, today) -> "Today"
            sameDay(calendar, tomorrow) -> "Tomorrow"
            else -> SimpleDateFormat("EEEE", Locale.getDefault()).format(calendar.time)
        }
        binding.dateText.text = SimpleDateFormat("d MMM", Locale.getDefault()).format(calendar.time)
        val (t, ampm) = formatTime(this, calendar.timeInMillis)
        binding.timeText.text = t
        binding.meridiem.text = ampm.ifEmpty { "At" }
        val whenText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(calendar.time)
        binding.dateTile.contentDescription = "Date: ${binding.dayName.text}, ${binding.dateText.text}. Tap to change."
        binding.timeTile.contentDescription = "Time: $whenText. Tap to change."
        val past = calendar.timeInMillis < System.currentTimeMillis()
        binding.save.isEnabled = !past
        binding.save.text = if (past) "Pick a time in the future" else "Schedule"
    }

    /**
     * Pre-fills a new message from [sourceId]. Attachments are copied into this message's own
     * folder, so deleting either message never breaks the other. The time keeps the source's
     * time of day on its next occurrence; the date and time tiles are what you'd usually change.
     */
    private fun copyFrom(sourceId: Long): Boolean {
        if (sourceId == -1L) return false
        val source = MessageStore.get(this, sourceId) ?: return false
        binding.phone.setText(source.phone)
        if (source.byName) {
            binding.targetGroup.check(R.id.targetName)
            binding.chatName.setText(source.chatName)
        }
        binding.message.setText(source.text)
        if (source.packageName == ScheduledMessage.WHATSAPP_BUSINESS) binding.appGroup.check(R.id.appBusiness)

        val fromDir = MessageStore.attachmentDir(this, source.id)
        val toDir = MessageStore.attachmentDir(this, messageId)
        source.attachments.forEach { a ->
            val src = File(fromDir, a.fileName)
            if (src.exists()) {
                runCatching { src.copyTo(File(toDir, a.fileName), overwrite = true) }
                    .onSuccess { attachments += a }
            }
        }

        val at = Calendar.getInstance().apply { timeInMillis = source.timeMillis }
        calendar.timeInMillis = System.currentTimeMillis()
        calendar.set(Calendar.HOUR_OF_DAY, at.get(Calendar.HOUR_OF_DAY))
        calendar.set(Calendar.MINUTE, at.get(Calendar.MINUTE))
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        if (source.timeMillis > System.currentTimeMillis()) {
            calendar.timeInMillis = source.timeMillis
            calendar.add(Calendar.DAY_OF_MONTH, 1) // a copy of an upcoming message: same time, next day
        } else if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return true
    }

    private fun renderAttachments() {
        val group = binding.attachmentChips
        // Keep the "Add files" chip; rebuild the rest.
        for (i in group.childCount - 1 downTo 0) {
            val v = group.getChildAt(i)
            if (v.id != binding.addAttachments.id) group.removeViewAt(i)
        }
        attachments.forEach { a ->
            val chip = Chip(this).apply {
                text = a.displayName
                isCloseIconVisible = true
                closeIconContentDescription = "Remove ${a.displayName}"
                setChipIconResource(R.drawable.ic_attach)
                isChipIconVisible = true
                setOnCloseIconClickListener {
                    attachments.remove(a)
                    renderAttachments()
                }
            }
            group.addView(chip, group.childCount - 1)
        }
        binding.addAttachments.text = if (attachments.isEmpty()) "Add files" else "Add more"
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
        val byName = isByName()
        val chatName = binding.chatName.text?.toString()?.trim().orEmpty()
        val phone = if (byName) "" else binding.phone.text?.toString().orEmpty().filter { it.isDigit() }
        val text = binding.message.text?.toString().orEmpty()
        if (byName && chatName.isEmpty()) {
            binding.chatName.error = "Pick a chat or type its exact name"
            return
        }
        if (!byName && phone.length < 8) {
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
        val pkg = selectedPackage()
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
            chatName = if (byName) chatName else null,
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
