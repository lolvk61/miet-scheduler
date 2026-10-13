package ru.miet.calendar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val RU = Locale("ru")
private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM", RU)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                App()
            }
        }
    }
}

@Composable
private fun App(vm: MainViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.showMessage(null)
        }
    }
    BackHandler(enabled = vm.screen != Screen.Calendar) { vm.back() }

    when (val screen = vm.screen) {
        Screen.Calendar -> if (vm.settings.picks.isEmpty()) {
            GroupPicker(vm, snackbar, title = "Выберите группу", onPick = vm::selectGroup) {
                OutlinedButton(onClick = { vm.openEditor() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Собрать своё расписание из разных групп")
                }
            }
        } else {
            CalendarScreen(vm, snackbar)
        }
        Screen.Editor -> EditorScreen(vm, snackbar)
        Screen.EditorGroups -> GroupPicker(
            vm,
            snackbar,
            title = "Добавить группу",
            onPick = vm::editGroup,
            onBack = vm::back,
        )
        is Screen.Subjects -> SubjectsScreen(vm, snackbar, screen.group)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPicker(
    vm: MainViewModel,
    snackbar: SnackbarHostState,
    title: String,
    onPick: (String) -> Unit,
    onBack: (() -> Unit)? = null,
    header: @Composable () -> Unit = {},
) {
    LaunchedEffect(Unit) { vm.loadGroups() }
    var query by remember { mutableStateOf("") }
    val filtered = remember(vm.groups, query) {
        vm.groups.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { onBack?.let { BackButton(it) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize()) {
            header()
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Поиск, например П-11") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
            when {
                vm.loading && vm.groups.isEmpty() -> CircularProgressIndicator()
                vm.groups.isEmpty() -> TextButton(onClick = { vm.loadGroups() }) {
                    Text("Загрузить список групп")
                }
                else -> LazyColumn {
                    items(filtered) { group ->
                        Text(
                            group,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(group) }
                                .padding(vertical = 14.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val data = vm.schedule

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(data?.title ?: vm.settings.groups.joinToString(" + "))
                        data?.let {
                            Text(it.semester, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                actions = {
                    if (vm.loading) {
                        CircularProgressIndicator(Modifier.padding(12.dp))
                    } else {
                        IconButton(onClick = { vm.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("День") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Список") })
            }
            if (data == null) {
                Text(
                    if (vm.loading) "Загрузка расписания…" else "Расписания пока нет. Нажмите «Обновить».",
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                if (data.missing.isNotEmpty()) MissingWarning(data.missing) { vm.openEditor() }
                if (tab == 0) DayScreen(vm, data) else UpcomingScreen(data)
            }
        }
    }

    if (showSettings) SettingsDialog(vm) { showSettings = false }
}

/** Выбранные занятия пропали из расписания: скорее всего, их перенесли. */
@Composable
private fun MissingWarning(missing: List<String>, onEdit: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Не найдены выбранные занятия. Возможно, расписание изменилось:",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            missing.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onEdit) { Text("Изменить выбор") }
        }
    }
}

@Composable
private fun DayScreen(vm: MainViewModel, data: ScheduleData) {
    val date = vm.selectedDate
    val lessons = remember(data, date) { data.lessons.filter { it.date == date } }
    val showGroup = data.groups.size > 1

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.selectedDate = date.minusDays(1) }) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Предыдущий день")
            }
            Column(Modifier.weight(1f).clickable { vm.selectedDate = LocalDate.now(MOSCOW) }) {
                Text(
                    date.format(DAY_FORMAT).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    lessons.firstOrNull()?.week ?: "нажмите, чтобы перейти к сегодня",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = { vm.selectedDate = date.plusDays(1) }) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Следующий день")
            }
        }
        if (lessons.isEmpty()) {
            Text("Занятий нет", modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(lessons, key = { it.uid }) { LessonCard(it, showGroup) }
            }
        }
    }
}

@Composable
private fun UpcomingScreen(data: ScheduleData) {
    val today = LocalDate.now(MOSCOW)
    val showGroup = data.groups.size > 1
    val byDate = remember(data) {
        data.lessons.filter { it.date >= today }.groupBy { it.date }.toList()
    }
    if (byDate.isEmpty()) {
        Text("Ближайших занятий нет", modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        byDate.forEach { (date, lessons) ->
            item(key = "h$date") {
                Text(
                    date.format(DAY_FORMAT).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(lessons, key = { it.uid }) { LessonCard(it, showGroup) }
        }
    }
}

@Composable
private fun LessonCard(lesson: Lesson, showGroup: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val time = "${lesson.slot} пара · ${lesson.start.format(TIME_FORMAT)}–${lesson.end.format(TIME_FORMAT)}"
            Text(
                if (showGroup && lesson.group.isNotEmpty()) "${lesson.group} · $time" else time,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(lesson.fullTitle, style = MaterialTheme.typography.titleMedium)
            if (lesson.room.isNotEmpty()) Text("Ауд. ${lesson.room}")
            if (lesson.teacher.isNotEmpty()) {
                Text(lesson.teacher, style = MaterialTheme.typography.bodySmall)
            }
            if (lesson.distance || lesson.split || lesson.conflict) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lesson.conflict) {
                        AssistChip(
                            onClick = {},
                            label = { Text("накладка") },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.error,
                            ),
                        )
                    }
                    if (lesson.distance) AssistChip(onClick = {}, label = { Text("дистанционно") })
                    if (lesson.split) AssistChip(onClick = {}, label = { Text("подгруппа") })
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val s = vm.settings
    var skipDates by remember { mutableStateOf(s.skipDates) }

    val saveIcs = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/calendar"),
    ) { uri ->
        val data = vm.schedule
        if (uri != null && data != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use {
                    it.write(buildIcs(data).toByteArray(Charsets.UTF_8))
                }
            }.onSuccess { vm.showMessage("Файл сохранён") }
                .onFailure { vm.showMessage("Не удалось сохранить: ${it.message}") }
        }
    }

    fun writeCalendar() {
        val data = vm.schedule ?: return
        runCatching { writeToSystemCalendar(context, data) }
            .onSuccess { vm.showMessage("В календарь телефона добавлено занятий: $it") }
            .onFailure { vm.showMessage("Не удалось записать в календарь: ${it.message}") }
    }

    val calendarPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) writeCalendar() else vm.showMessage("Нет доступа к календарю")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchRow("Пропускать праздники", s.holidays) { vm.update(s.copy(holidays = it)) }
                SwitchRow("Практическая подготовка", s.includePractice) {
                    vm.update(s.copy(includePractice = it))
                }
                SwitchRow("Военная подготовка", s.includeMilitary) {
                    vm.update(s.copy(includeMilitary = it))
                }
                vm.schedule?.hidden?.takeIf { it.isNotEmpty() }?.let {
                    Text(
                        "Скрыто: ${it.joinToString()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = skipDates,
                    onValueChange = { skipDates = it },
                    label = { Text("Дни без занятий") },
                    supportingText = { Text("2026-11-05 или 2026-12-29..2026-12-31, через запятую") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { vm.update(s.copy(skipDates = skipDates.trim())) },
                    enabled = skipDates.trim() != s.skipDates,
                ) { Text("Применить даты") }

                HorizontalDivider()
                Button(
                    enabled = vm.schedule != null,
                    onClick = { vm.schedule?.let { saveIcs.launch("${it.title}.ics") } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Экспорт в .ics") }
                Button(
                    enabled = vm.schedule != null,
                    onClick = {
                        val perms = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                        if (perms.all {
                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                            }
                        ) {
                            writeCalendar()
                        } else {
                            calendarPermission.launch(perms)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Записать в календарь телефона") }

                HorizontalDivider()
                OutlinedButton(
                    onClick = {
                        vm.openEditor()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Своё расписание из разных групп") }
                OutlinedButton(
                    onClick = {
                        vm.changeGroup()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Сменить группу") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
