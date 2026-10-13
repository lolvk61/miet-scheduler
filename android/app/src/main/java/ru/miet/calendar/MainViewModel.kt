package ru.miet.calendar

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

sealed interface Screen {
    data object Calendar : Screen

    /** Своё расписание: какие группы и что из них взято. */
    data object Editor : Screen

    /** Выбор группы, которую добавить в своё расписание. */
    data object EditorGroups : Screen

    /** Выбор предметов и пар одной группы. */
    data class Subjects(val group: String) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MietRepository(app)

    var settings by mutableStateOf(repo.settings())
        private set
    var schedule by mutableStateOf(repo.cached())
        private set
    var groups by mutableStateOf<List<String>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var selectedDate by mutableStateOf(LocalDate.now(MOSCOW))

    var screen by mutableStateOf<Screen>(Screen.Calendar)
        private set

    /** Черновик своего расписания, пока он открыт в редакторе. */
    var draft by mutableStateOf<List<Pick>>(emptyList())
        private set

    /** Предметы групп, загруженные для редактора. */
    var subjects by mutableStateOf<Map<String, List<SubjectOption>>>(emptyMap())
        private set

    init {
        if (settings.picks.isEmpty()) schedule = null else refresh()
    }

    fun loadGroups() {
        if (groups.isNotEmpty() || loading) return
        launchLoading { groups = repo.groups() }
    }

    /** Обычное расписание одной группы. */
    fun selectGroup(group: String) {
        update(settings.copy(picks = listOf(Pick(group))))
        screen = Screen.Calendar
    }

    fun update(new: Settings) {
        settings = new
        repo.saveSettings(new)
        if (new.picks.isEmpty()) schedule = null else refresh()
    }

    fun changeGroup() {
        update(settings.copy(picks = emptyList()))
        screen = Screen.Calendar
    }

    fun refresh() {
        if (settings.picks.isEmpty()) return
        launchLoading {
            val data = repo.refresh(settings)
            schedule = data
            // Сайт мог вернуть расписание на другой семестр: показываем ближайшую дату.
            if (selectedDate !in data.start..data.end) selectedDate = data.start
        }
    }

    fun openEditor() {
        draft = settings.picks
        screen = Screen.Editor
    }

    fun addGroupToDraft() {
        loadGroups()
        screen = Screen.EditorGroups
    }

    fun editGroup(group: String) {
        screen = Screen.Subjects(group)
        if (group in subjects) return
        viewModelScope.launch {
            try {
                subjects = subjects + (group to repo.subjects(group))
            } catch (e: Exception) {
                message = e.message ?: e::class.java.simpleName
                if (screen == Screen.Subjects(group)) screen = Screen.Editor
            }
        }
    }

    /** Заменяет в черновике выбор из группы, сохраняя её место в списке. */
    fun applySubjects(group: String, picks: List<Pick>) {
        val index = draft.indexOfFirst { it.group == group }
        val rest = draft.filter { it.group != group }
        draft = if (index < 0) rest + picks else rest.toMutableList().apply { addAll(index, picks) }
        screen = Screen.Editor
    }

    fun removeFromDraft(group: String) {
        draft = draft.filter { it.group != group }
    }

    fun saveDraft() {
        update(settings.copy(picks = draft))
        screen = Screen.Calendar
    }

    fun back() {
        screen = when (screen) {
            is Screen.Subjects, Screen.EditorGroups -> Screen.Editor
            else -> Screen.Calendar
        }
    }

    fun showMessage(text: String?) {
        message = text
    }

    private fun launchLoading(block: suspend () -> Unit) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: MietException) {
                message = e.message
            } catch (e: Exception) {
                message = "Ошибка: ${e.message ?: e::class.java.simpleName}"
            } finally {
                loading = false
            }
        }
    }
}
