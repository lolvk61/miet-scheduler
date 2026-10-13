package ru.miet.calendar

import android.content.Context
import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class MietException(message: String) : Exception(message)

/** Обёртка над Python-парсером (miet_scheduler.mobile) с кэшем последнего расписания. */
class MietRepository(context: Context) {
    private val prefs = context.getSharedPreferences("miet", Context.MODE_PRIVATE)
    private val cache = File(context.filesDir, "schedule.json")

    private val parser get() = Python.getInstance().getModule("miet_scheduler.mobile")

    fun settings() = Settings(
        // До своего расписания хранилась одна группа в "group".
        picks = prefs.getString("picks", null)?.let(::parsePicks)
            ?: prefs.getString("group", null)?.let { listOf(Pick(it)) }
            ?: emptyList(),
        holidays = prefs.getBoolean("holidays", true),
        includePractice = prefs.getBoolean("practice", false),
        includeMilitary = prefs.getBoolean("military", false),
        skipDates = prefs.getString("skipDates", "").orEmpty(),
    )

    fun saveSettings(s: Settings) {
        val picks = s.picks.toJsonString()
        // Кэш относится к прежнему выбору занятий: не показываем его для нового.
        if (picks != prefs.getString("picks", null)) cache.delete()
        prefs.edit()
            .putString("picks", picks)
            .remove("group")
            .putBoolean("holidays", s.holidays)
            .putBoolean("practice", s.includePractice)
            .putBoolean("military", s.includeMilitary)
            .putString("skipDates", s.skipDates)
            .apply()
    }

    fun cached(): ScheduleData? = runCatching {
        if (cache.exists()) ScheduleData.fromJson(JSONObject(cache.readText())) else null
    }.getOrNull()

    suspend fun groups(): List<String> = withContext(Dispatchers.IO) {
        checked(parser.callAttr("list_groups").toString()).getJSONArray("groups").strings()
    }

    suspend fun subjects(group: String): List<SubjectOption> = withContext(Dispatchers.IO) {
        val subjects = checked(parser.callAttr("group_subjects", group).toString())
            .getJSONArray("subjects")
        (0 until subjects.length()).map { SubjectOption.fromJson(subjects.getJSONObject(it)) }
    }

    suspend fun refresh(s: Settings): ScheduleData = withContext(Dispatchers.IO) {
        if (s.picks.isEmpty()) throw MietException("Группа не выбрана")
        val raw = parser.callAttr(
            "load_picks",
            s.picks.toJsonString(), null, null, s.skipDates, s.holidays,
            s.includePractice, s.includeMilitary,
        ).toString()
        val json = checked(raw)
        cache.writeText(raw)
        ScheduleData.fromJson(json)
    }

    private fun checked(raw: String): JSONObject {
        val json = JSONObject(raw)
        if (json.has("error")) throw MietException(json.getString("error"))
        return json
    }
}
