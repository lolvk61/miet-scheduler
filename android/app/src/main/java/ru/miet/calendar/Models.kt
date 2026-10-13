package ru.miet.calendar

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

data class Lesson(
    val uid: String,
    val start: OffsetDateTime,
    val end: OffsetDateTime,
    val title: String,
    val kind: String?,
    val teacher: String,
    val room: String,
    val group: String,
    val slot: Int,
    val week: String,
    val distance: Boolean,
    val split: Boolean,
    val conflict: Boolean,
    val description: String,
) {
    val date: LocalDate get() = start.toLocalDate()

    /** Название вместе с типом занятия: "Информатика [Лек]". */
    val fullTitle: String get() = kind?.let { "$title [$it]" } ?: title

    companion object {
        fun fromJson(o: JSONObject) = Lesson(
            uid = o.getString("uid"),
            start = OffsetDateTime.parse(o.getString("start")),
            end = OffsetDateTime.parse(o.getString("end")),
            title = o.getString("title"),
            kind = if (o.isNull("kind")) null else o.getString("kind").ifEmpty { null },
            teacher = o.optString("teacher"),
            room = o.optString("room"),
            group = o.optString("group"),
            slot = o.optInt("slot"),
            week = o.optString("week"),
            distance = o.optBoolean("distance"),
            split = o.optBoolean("split"),
            conflict = o.optBoolean("conflict"),
            description = o.optString("description"),
        )
    }
}

data class ScheduleData(
    val title: String,
    val groups: List<String>,
    val semester: String,
    val start: LocalDate,
    val end: LocalDate,
    val hidden: List<String>,
    /** Выбранные занятия, которых больше нет в расписании групп. */
    val missing: List<String>,
    val lessons: List<Lesson>,
) {
    companion object {
        fun fromJson(o: JSONObject): ScheduleData {
            val events = o.getJSONArray("events")
            // Кэш до появления своего расписания хранил одну группу в "group".
            val title = o.optString("title").ifEmpty { o.optString("group") }
            return ScheduleData(
                title = title,
                groups = o.optJSONArray("groups")?.strings() ?: listOf(title),
                semester = o.getString("semester"),
                start = LocalDate.parse(o.getString("start")),
                end = LocalDate.parse(o.getString("end")),
                hidden = o.getJSONArray("hidden").strings(),
                missing = o.optJSONArray("missing")?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it).getString("label") }
                }.orEmpty(),
                lessons = (0 until events.length()).map { Lesson.fromJson(events.getJSONObject(it)) },
            )
        }
    }
}

/**
 * Что взять из расписания группы, как Pick в парсере. Пустое поле выбор не ограничивает:
 * только group — все занятия группы, с name — предмет, с day/week/slot — одна пара цикла.
 */
data class Pick(
    val group: String,
    val name: String? = null,
    val day: Int? = null,
    val week: Int? = null,
    val slot: Int? = null,
) {
    val wholeGroup: Boolean get() = name == null && day == null && week == null && slot == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("group", group)
        name?.let { put("name", it) }
        day?.let { put("day", it) }
        week?.let { put("week", it) }
        slot?.let { put("slot", it) }
    }

    companion object {
        fun fromJson(o: JSONObject) = Pick(
            group = o.getString("group"),
            name = if (o.has("name")) o.getString("name") else null,
            day = if (o.has("day")) o.getInt("day") else null,
            week = if (o.has("week")) o.getInt("week") else null,
            slot = if (o.has("slot")) o.getInt("slot") else null,
        )
    }
}

fun List<Pick>.toJsonString(): String = JSONArray(map { it.toJson() }).toString()

fun parsePicks(raw: String): List<Pick> {
    val arr = JSONArray(raw)
    return (0 until arr.length()).map { Pick.fromJson(arr.getJSONObject(it)) }
}

/** Одна пара 4-недельного цикла у предмета группы. */
data class SubjectEntry(
    val day: Int,
    val week: Int,
    val slot: Int,
    val label: String,
    val teacher: String,
    val room: String,
)

/** Предмет группы для выбора своего расписания. name — полное название с типом. */
data class SubjectOption(
    val name: String,
    val subject: String,
    val kind: String?,
    val entries: List<SubjectEntry>,
) {
    companion object {
        fun fromJson(o: JSONObject): SubjectOption {
            val entries = o.getJSONArray("entries")
            return SubjectOption(
                name = o.getString("name"),
                subject = o.getString("subject"),
                kind = if (o.isNull("kind")) null else o.getString("kind"),
                entries = (0 until entries.length()).map {
                    val e = entries.getJSONObject(it)
                    SubjectEntry(
                        day = e.getInt("day"),
                        week = e.getInt("week"),
                        slot = e.getInt("slot"),
                        label = e.getString("label"),
                        teacher = e.optString("teacher"),
                        room = e.optString("room"),
                    )
                },
            )
        }
    }
}

data class Settings(
    val picks: List<Pick> = emptyList(),
    val holidays: Boolean = true,
    val includePractice: Boolean = false,
    val includeMilitary: Boolean = false,
    val skipDates: String = "",
) {
    /** Группы в порядке выбора. */
    val groups: List<String> get() = picks.map { it.group }.distinct()

    /** Обычное расписание одной группы, без своего выбора. */
    val singleGroup: String? get() = picks.singleOrNull()?.takeIf { it.wholeGroup }?.group
}

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
