package ru.miet.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val groups = vm.draft.map { it.group }.distinct()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Своё расписание") },
                navigationIcon = { BackButton(vm::back) },
            )
        },
        bottomBar = {
            Button(
                onClick = { vm.saveDraft() },
                enabled = vm.draft.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            ) { Text("Сохранить") }
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Добавьте группы, с которыми вы занимаетесь, и отметьте свои предметы. " +
                        "Поточная лекция, общая для нескольких групп, попадёт в расписание один раз, " +
                        "а занятия разных групп в одно время будут помечены как накладка.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(groups, key = { it }) { group ->
                DraftGroupCard(
                    group = group,
                    picks = vm.draft.filter { it.group == group },
                    onEdit = { vm.editGroup(group) },
                    onRemove = { vm.removeFromDraft(group) },
                )
            }
            item {
                OutlinedButton(onClick = { vm.addGroupToDraft() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Добавить группу", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun DraftGroupCard(group: String, picks: List<Pick>, onEdit: () -> Unit, onRemove: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(group, style = MaterialTheme.typography.titleMedium)
            if (picks.any { it.wholeGroup }) {
                Text("Все занятия группы", style = MaterialTheme.typography.bodyMedium)
            } else {
                picks.groupBy { it.name }.forEach { (name, byName) ->
                    val partial = byName.any { it.day != null || it.week != null || it.slot != null }
                    Text(
                        "• ${name ?: "все предметы"}" + if (partial) " (выбранные пары: ${byName.size})" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row {
                TextButton(onClick = onEdit) { Text("Изменить") }
                TextButton(onClick = onRemove) { Text("Убрать") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectsScreen(vm: MainViewModel, snackbar: SnackbarHostState, group: String) {
    val options = vm.subjects[group]
    // Отметки строятся из черновика один раз, когда предметы загрузились.
    val initial = remember(options) {
        options?.let { Selection.of(vm.draft.filter { it.group == group }, it) }
    }
    var whole by remember(initial) { mutableStateOf(initial?.whole ?: false) }
    var selected by remember(initial) { mutableStateOf(initial?.keys ?: emptySet()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(group) },
                navigationIcon = { BackButton(vm::back) },
            )
        },
        bottomBar = {
            if (options != null) {
                Button(
                    onClick = { vm.applySubjects(group, Selection(whole, selected).toPicks(group, options)) },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text("Готово") }
            }
        },
    ) { padding ->
        if (options == null) {
            Column(Modifier.padding(padding).padding(16.dp)) {
                CircularProgressIndicator()
                Text("Загрузка предметов группы…", Modifier.padding(top = 8.dp))
            }
            return@Scaffold
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Все занятия группы", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (whole) "Выключите, чтобы выбрать отдельные предметы" else "Отметьте свои предметы и пары",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = whole, onCheckedChange = { whole = it })
                }
            }
            if (!whole) {
                items(options, key = { it.name }) { subject ->
                    val keys = subject.keys()
                    val count = keys.count { it in selected }
                    SubjectRow(
                        subject = subject,
                        state = when (count) {
                            0 -> ToggleableState.Off
                            keys.size -> ToggleableState.On
                            else -> ToggleableState.Indeterminate
                        },
                        expanded = subject.name in expanded,
                        onToggle = {
                            selected = if (count == keys.size) selected - keys.toSet() else selected + keys
                        },
                        onExpand = {
                            expanded = if (subject.name in expanded) expanded - subject.name else expanded + subject.name
                        },
                    )
                    if (subject.name in expanded) {
                        subject.entries.zip(keys).forEach { (entry, key) ->
                            EntryRow(entry, checked = key in selected) {
                                selected = if (key in selected) selected - key else selected + key
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectRow(
    subject: SubjectOption,
    state: ToggleableState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onExpand: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state, onClick = onToggle)
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(subject.name, style = MaterialTheme.typography.bodyLarge)
            val n = subject.entries.size
            Text(
                "$n ${plural(n, "пара", "пары", "пар")} за 4 недели",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Icon(
            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Свернуть" else "Показать пары",
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun EntryRow(entry: SubjectEntry, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 40.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(entry.label, style = MaterialTheme.typography.bodyMedium)
            val details = listOf(entry.teacher, entry.room.takeIf { it.isNotEmpty() }?.let { "ауд. $it" })
                .filter { !it.isNullOrEmpty() }
                .joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
