package com.yogeshpaliyal.sendahead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Scheduler.EXTRA_ID, -1L)
        if (id == -1L) return
        val msg = MessageStore.get(context, id)
        if (msg == null) {
            DebugLog.log(context, "Alarm fired for id=$id but the message no longer exists")
            return
        }
        val lateSec = (System.currentTimeMillis() - msg.timeMillis) / 1000
        DebugLog.log(
            context,
            "Alarm fired id=$id to=${msg.targetLabel} status=${msg.status} " +
                "due ${DebugLog.time(msg.timeMillis)} (${if (lateSec >= 0) "${lateSec}s late" else "${-lateSec}s early"})",
        )
        DebugLog.log(context, DebugLog.environment(context))
        if (msg.status != Status.PENDING) {
            DebugLog.log(context, "Skipping id=$id: status is ${msg.status}, not PENDING")
            return
        }

        wakeScreen(context)

        val service = SendAccessibilityService.instance
        if (service == null) {
            DebugLog.log(context, "Accessibility service not running; failing id=$id")
            val err = "Accessibility service is off"
            MessageStore.updateStatus(context, id, Status.FAILED, err)
            Notifier.notify(context, id, "Not sent to ${msg.targetLabel}", "$err. Open SendAhead and enable it.")
            return
        }
        service.enqueue(id)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        DebugLog.log(context, "Screen was off; waking it")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "SendAhead:send",
        )
        wl.acquire(60_000L)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.log(context, "Boot/update broadcast: ${intent.action}")
        Scheduler.rescheduleAll(context)
    }
}
