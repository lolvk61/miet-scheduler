package ru.miet.calendar

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
private const val ACCOUNT_NAME = "МИЭТ"
private const val INDIGO = 0xFF3F51B5.toInt()

/** iCalendar (.ics) со временем в UTC, чтобы не тащить VTIMEZONE. */
fun buildIcs(data: ScheduleData): String {
    val lines = mutableListOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//miet-scheduler//android//RU",
        "X-WR-CALNAME:${escape("МИЭТ ${data.title} — ${data.semester}")}",
        "X-WR-TIMEZONE:Europe/Moscow",
    )
    val stamp = UTC_STAMP.format(OffsetDateTime.now(ZoneOffset.UTC))
    for (l in data.lessons) {
        lines += "BEGIN:VEVENT"
        lines += "UID:${l.uid}"
        lines += "DTSTAMP:$stamp"
        lines += "DTSTART:${UTC_STAMP.format(l.start.withOffsetSameInstant(ZoneOffset.UTC))}"
        lines += "DTEND:${UTC_STAMP.format(l.end.withOffsetSameInstant(ZoneOffset.UTC))}"
        lines += "SUMMARY:${escape(l.fullTitle)}"
        if (l.room.isNotEmpty()) lines += "LOCATION:${escape(l.room)}"
        lines += "DESCRIPTION:${escape(l.description)}"
        l.kind?.let { lines += "CATEGORIES:${escape(it)}" }
        lines += "END:VEVENT"
    }
    lines += "END:VCALENDAR"
    return lines.joinToString("") { fold(it) + "\r\n" }
}

private fun escape(s: String) = s
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r", "")
    .replace("\n", "\\n")

/** RFC 5545: строки не длиннее 75 октетов, продолжение начинается с пробела. */
private fun fold(line: String): String {
    val out = StringBuilder()
    var octets = 0
    line.codePoints().forEach { cp ->
        val text = String(Character.toChars(cp))
        val size = text.toByteArray(Charsets.UTF_8).size
        if (octets + size > 75) {
            out.append("\r\n ")
            octets = 1
        }
        out.append(text)
        octets += size
    }
    return out.toString()
}

/**
 * Записывает расписание в локальный календарь телефона «МИЭТ <группа>».
 * Старые события этого календаря удаляются, поэтому повторная запись безопасна.
 * Нужны разрешения READ_CALENDAR и WRITE_CALENDAR.
 */
fun writeToSystemCalendar(context: Context, data: ScheduleData): Int {
    val resolver = context.contentResolver

    fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.ACCOUNT_TYPE_LOCAL,
        )
        .build()

    val existing = resolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
        arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL),
        null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    val displayName = "МИЭТ ${data.title}"
    val calendarId = existing?.also { id ->
        // Состав групп мог измениться с прошлой записи.
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
        }
        resolver.update(
            ContentUris.withAppendedId(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), id),
            values,
            null,
            null,
        )
    } ?: run {
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, "miet")
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName)
            put(CalendarContract.Calendars.CALENDAR_COLOR, INDIGO)
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER,
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "Europe/Moscow")
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        }
        val uri = resolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), values)
            ?: error("Не удалось создать календарь")
        ContentUris.parseId(uri)
    }

    val eventsUri = asSyncAdapter(CalendarContract.Events.CONTENT_URI)
    resolver.delete(
        eventsUri,
        "${CalendarContract.Events.CALENDAR_ID}=?",
        arrayOf(calendarId.toString()),
    )

    data.lessons.chunked(200).forEach { chunk ->
        val ops = chunk.map { l ->
            ContentProviderOperation.newInsert(eventsUri)
                .withValue(CalendarContract.Events.CALENDAR_ID, calendarId)
                .withValue(CalendarContract.Events.TITLE, l.fullTitle)
                .withValue(CalendarContract.Events.EVENT_LOCATION, l.room.ifEmpty { null })
                .withValue(CalendarContract.Events.DESCRIPTION, l.description)
                .withValue(CalendarContract.Events.DTSTART, l.start.toInstant().toEpochMilli())
                .withValue(CalendarContract.Events.DTEND, l.end.toInstant().toEpochMilli())
                .withValue(CalendarContract.Events.EVENT_TIMEZONE, "Europe/Moscow")
                .withValue(CalendarContract.Events.UID_2445, l.uid)
                .build()
        }
        resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(ops))
    }
    return data.lessons.size
}
