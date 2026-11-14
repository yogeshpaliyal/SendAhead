package com.yogeshpaliyal.wascheduler

import org.json.JSONArray
import org.json.JSONObject

enum class Status { PENDING, SENDING, SENT, FAILED }

data class Attachment(
    /** File name inside filesDir/attachments/<messageId>/ */
    val fileName: String,
    val mimeType: String,
    val displayName: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("fileName", fileName)
        .put("mimeType", mimeType)
        .put("displayName", displayName)

    companion object {
        fun fromJson(o: JSONObject) = Attachment(
            fileName = o.getString("fileName"),
            mimeType = o.optString("mimeType", "*/*"),
            displayName = o.optString("displayName", o.getString("fileName")),
        )
    }
}

data class ScheduledMessage(
    val id: Long,
    /** Digits only, including country code, e.g. 919876543210 */
    val phone: String,
    val text: String,
    val attachments: List<Attachment>,
    val timeMillis: Long,
    val packageName: String,
    val status: Status = Status.PENDING,
    val error: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("phone", phone)
        .put("text", text)
        .put("attachments", JSONArray().apply { attachments.forEach { put(it.toJson()) } })
        .put("timeMillis", timeMillis)
        .put("packageName", packageName)
        .put("status", status.name)
        .put("error", error ?: JSONObject.NULL)

    companion object {
        const val WHATSAPP = "com.whatsapp"
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

        fun fromJson(o: JSONObject): ScheduledMessage {
            val arr = o.optJSONArray("attachments") ?: JSONArray()
            return ScheduledMessage(
                id = o.getLong("id"),
                phone = o.getString("phone"),
                text = o.optString("text", ""),
                attachments = (0 until arr.length()).map { Attachment.fromJson(arr.getJSONObject(it)) },
                timeMillis = o.getLong("timeMillis"),
                packageName = o.optString("packageName", WHATSAPP),
                status = runCatching { Status.valueOf(o.getString("status")) }.getOrDefault(Status.PENDING),
                error = if (o.isNull("error")) null else o.optString("error"),
            )
        }
    }
}
