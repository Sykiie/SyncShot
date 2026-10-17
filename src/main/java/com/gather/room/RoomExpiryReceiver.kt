package com.gather.room

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.NotificationManager

/** Keeps room cleanup scheduled if Android stops the room process or the phone restarts. */
object RoomExpiryScheduler {
    private const val REQUEST_CODE = 9041
    private const val EXPIRY_PREF = "expiry_deadline"
    private const val EXPIRY_ROOM_PREF = "expiry_room"
    private const val SESSION_MILLIS = 10 * 60 * 1000L

    fun refresh(context: Context, room: RoomState) {
        val prefs = context.getSharedPreferences(RoomService.PREFS, Context.MODE_PRIVATE)
        val deadline = if (room.isHost) {
            val heartbeatAt = prefs.getLong("heartbeat_at", 0L)
            if (prefs.getString("heartbeat_room", null) == room.id && heartbeatAt > 0L) {
                val storedDeadline = heartbeatAt + SESSION_MILLIS
                if (RoomService.isRunning) maxOf(storedDeadline, System.currentTimeMillis() + SESSION_MILLIS) else storedDeadline
            } else null
        } else if (room.status == RoomStatus.PAUSED && room.disconnectedAt != null) {
            room.disconnectedAt + SESSION_MILLIS
        } else null

        if (deadline == null) {
            cancel(context)
            return
        }
        prefs.edit().putString(EXPIRY_ROOM_PREF, room.id).putLong(EXPIRY_PREF, deadline).apply()
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, deadline, pendingIntent(context))
    }

    fun cancel(context: Context) {
        val prefs = context.getSharedPreferences(RoomService.PREFS, Context.MODE_PRIVATE)
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm?.cancel(pendingIntent(context))
        prefs.edit().remove(EXPIRY_ROOM_PREF).remove(EXPIRY_PREF).apply()
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, RoomExpiryReceiver::class.java).setAction(RoomExpiryReceiver.ACTION_EXPIRE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    internal fun onAlarm(context: Context) {
        val store = RoomStore(context)
        val room = store.current() ?: run { cancel(context); return }
        val prefs = context.getSharedPreferences(RoomService.PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val scheduledRoom = prefs.getString(EXPIRY_ROOM_PREF, null)
        val scheduledAt = prefs.getLong(EXPIRY_PREF, 0L)
        if (scheduledRoom != room.id || scheduledAt == 0L || now < scheduledAt) {
            refresh(context, room)
            return
        }

        val heartbeatAt = prefs.getLong("heartbeat_at", 0L)
        val hostExpired = room.isHost && !RoomService.isRunning &&
            prefs.getString("heartbeat_room", null) == room.id && heartbeatAt > 0L && now - heartbeatAt >= SESSION_MILLIS
        val guestExpired = !room.isHost && room.status == RoomStatus.PAUSED && room.disconnectedAt != null &&
            now >= room.disconnectedAt + SESSION_MILLIS
        if (hostExpired || guestExpired) {
            store.clear()
            prefs.edit().putString("last_message", "This room expired after 10 minutes offline").apply()
            context.getSystemService(NotificationManager::class.java)?.cancel(71)
            context.stopService(Intent(context, RoomService::class.java))
        } else {
            refresh(context, room)
        }
    }
}

class RoomExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_EXPIRE -> RoomExpiryScheduler.onAlarm(context)
            Intent.ACTION_BOOT_COMPLETED -> RoomStore(context).current()?.let { RoomExpiryScheduler.refresh(context, it) }
        }
    }

    companion object {
        const val ACTION_EXPIRE = "com.gather.room.EXPIRE_ROOM"
    }
}
