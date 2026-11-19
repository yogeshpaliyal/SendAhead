package com.yogeshpaliyal.sendahead

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object Scheduler {
    const val EXTRA_ID = "message_id"

    private fun pendingIntent(ctx: Context, id: Long): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            ctx,
            (id % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(AlarmManager::class.java)
        return am.canScheduleExactAlarms()
    }

    fun schedule(ctx: Context, msg: ScheduledMessage) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        // Overdue messages (e.g. after a reboot) go out a few seconds from now.
        val at = maxOf(msg.timeMillis, System.currentTimeMillis() + 5_000)
        val pi = pendingIntent(ctx, msg.id)
        try {
            if (canScheduleExact(ctx)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pendingIntent(ctx, id))
    }

    fun rescheduleAll(ctx: Context) {
        MessageStore.all(ctx)
            .filter { it.status == Status.PENDING || it.status == Status.SENDING }
            .forEach {
                if (it.status == Status.SENDING) MessageStore.updateStatus(ctx, it.id, Status.PENDING)
                schedule(ctx, it)
            }
    }
}
