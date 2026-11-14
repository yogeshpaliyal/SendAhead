package com.yogeshpaliyal.wascheduler

import android.content.Context
import org.json.JSONArray
import java.io.File

/** Tiny JSON-file store. Everything stays on the device. */
object MessageStore {
    private const val FILE = "messages.json"
    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun attachmentDir(ctx: Context, messageId: Long): File =
        File(ctx.filesDir, "attachments/$messageId").apply { mkdirs() }

    fun all(ctx: Context): List<ScheduledMessage> = synchronized(lock) {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { ScheduledMessage.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(ctx: Context, id: Long): ScheduledMessage? = all(ctx).firstOrNull { it.id == id }

    fun upsert(ctx: Context, msg: ScheduledMessage) = synchronized(lock) {
        val list = all(ctx).filterNot { it.id == msg.id } + msg
        save(ctx, list)
    }

    fun updateStatus(ctx: Context, id: Long, status: Status, error: String? = null) = synchronized(lock) {
        val list = all(ctx).map { if (it.id == id) it.copy(status = status, error = error) else it }
        save(ctx, list)
    }

    fun delete(ctx: Context, id: Long) = synchronized(lock) {
        save(ctx, all(ctx).filterNot { it.id == id })
        File(ctx.filesDir, "attachments/$id").deleteRecursively()
    }

    private fun save(ctx: Context, list: List<ScheduledMessage>) {
        val arr = JSONArray()
        list.sortedBy { it.timeMillis }.forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }
}
