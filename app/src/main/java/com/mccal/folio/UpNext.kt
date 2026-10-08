package com.mccal.folio

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract

/** The next thing on your calendar, as iOS's Up Next shows it. */
data class UpNextEvent(val id: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int?, val location: String?)

internal object UpNext {
    fun hasCalendar(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Events that haven't ended, starting within [withinMs], soonest first; declined invitations are left out. Off the main thread. */
    fun events(context: Context, now: Long = System.currentTimeMillis(), withinMs: Long = 36 * 60 * 60 * 1000L, limit: Int = 3): List<UpNextEvent> {
        if (!hasCalendar(context) || ScreenshotMode.on.value) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 12 * 60 * 60 * 1000L); ContentUris.appendId(it, now + withinMs)
        }.build()
        val columns = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.SELF_ATTENDEE_STATUS)
        val selection = "${CalendarContract.Instances.VISIBLE} = 1"
        return runCatching {
            context.contentResolver.query(uri, columns, selection, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val allDay = c.getInt(4) == 1
                        val end = c.getLong(3)
                        // All-day times are UTC midnights; they're over once the local day ends.
                        val over = if (allDay) end - java.util.TimeZone.getDefault().getOffset(end) <= now else end <= now
                        if (over || c.getInt(7) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                        add(UpNextEvent(c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() } ?: "Event", c.getLong(2), end, allDay,
                            if (c.isNull(5)) null else c.getInt(5), c.getString(6)?.takeIf { it.isNotBlank() }))
                    }
                }
            }.orEmpty().sortedWith(compareBy({ it.allDay }, { it.begin })).take(limit)
        }.getOrDefault(emptyList())
    }

    fun openEvent(context: Context, event: UpNextEvent) = runCatching {
        AppSecurity.startActivity(context, Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The next alarm the Clock app has set, if any. */
    fun nextAlarm(context: Context): Long? = if (ScreenshotMode.on.value) null else context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
}
