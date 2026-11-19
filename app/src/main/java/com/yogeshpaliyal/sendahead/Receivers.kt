package com.yogeshpaliyal.sendahead

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(Scheduler.EXTRA_ID, -1L)
        if (id == -1L) return
        val msg = MessageStore.get(context, id) ?: return
        if (msg.status != Status.PENDING) return

        wakeScreen(context)

        val service = SendAccessibilityService.instance
        if (service == null) {
            val err = "Accessibility service is off"
            MessageStore.updateStatus(context, id, Status.FAILED, err)
            Notifier.notify(context, id, "Not sent to ${msg.targetLabel}", "$err. Open Sendahead and enable it.")
            return
        }
        service.enqueue(id)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(context: Context) {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "Sendahead:send",
        )
        wl.acquire(60_000L)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rescheduleAll(context)
    }
}
