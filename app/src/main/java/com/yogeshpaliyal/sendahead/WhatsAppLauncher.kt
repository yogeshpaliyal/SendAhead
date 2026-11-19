package com.yogeshpaliyal.sendahead

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object WhatsAppLauncher {

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Opens the chat (text only) or the media preview (with attachments) for [msg]. */
    fun buildIntent(ctx: Context, msg: ScheduledMessage): Intent {
        val pkg = msg.packageName
        if (msg.byName) return buildShareIntent(ctx, msg, withJid = false)
        if (msg.attachments.isEmpty()) {
            val uri = Uri.parse(
                "https://api.whatsapp.com/send?phone=${msg.phone}&text=${Uri.encode(msg.text)}"
            )
            return Intent(Intent.ACTION_VIEW, uri)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return buildShareIntent(ctx, msg, withJid = true)
    }

    /** Opens WhatsApp's "Send to…" picker (no jid) so the chat can be chosen by name. */
    fun buildPickerIntent(pkg: String): Intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "Sendahead: picking a chat (this is not sent)")
        .setPackage(pkg)

    private fun buildShareIntent(ctx: Context, msg: ScheduledMessage, withJid: Boolean): Intent {
        val pkg = msg.packageName
        if (msg.attachments.isEmpty()) {
            return Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, msg.text)
                .setPackage(pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val dir = MessageStore.attachmentDir(ctx, msg.id)
        val uris = msg.attachments.map {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(dir, it.fileName))
        }
        uris.forEach { ctx.grantUriPermission(pkg, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }

        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = commonMime(msg.attachments.map { it.mimeType })
        if (msg.text.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT, msg.text)
        // Undocumented but long-standing: jumps straight to this chat instead of the contact picker.
        if (withJid) intent.putExtra("jid", "${msg.phone}@s.whatsapp.net")
        intent.setPackage(pkg)

        val clip = ClipData.newRawUri("attachment", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return intent
    }

    private fun commonMime(types: List<String>): String {
        if (types.size == 1) return types[0]
        if (types.all { it == types[0] }) return types[0]
        val top = types.map { it.substringBefore('/') }.distinct()
        return if (top.size == 1) "${top[0]}/*" else "*/*"
    }
}
