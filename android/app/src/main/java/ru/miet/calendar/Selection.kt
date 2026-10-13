package ru.miet.calendar

/** Пара цикла у предмета: по ней выбирается отдельное занятие. */
data class EntryKey(val name: String, val day: Int, val week: Int, val slot: Int)

fun SubjectOption.keys(): List<EntryKey> = entries.map { EntryKey(name, it.day, it.week, it.slot) }

fun Pick.matches(key: EntryKey): Boolean =
    (name == null || name == key.name) &&
        (day == null || day == key.day) &&
        (week == null || week == key.week) &&
        (slot == null || slot == key.slot)

/** Что отмечено на экране выбора предметов одной группы. */
data class Selection(val whole: Boolean, val keys: Set<EntryKey>) {
    /**
     * Записи Pick для группы: целиком группа, предмет, если отмечены все его пары,
     * или отдельные пары.
     */
    fun toPicks(group: String, options: List<SubjectOption>): List<Pick> =
        if (whole) {
            listOf(Pick(group))
        } else {
            options.flatMap { s ->
                val all = s.keys()
                val chosen = all.filter { it in keys }
                when (chosen.size) {
                    0 -> emptyList()
                    all.size -> listOf(Pick(group, s.name))
                    else -> chosen.map { Pick(group, s.name, it.day, it.week, it.slot) }
                }
            }
        }

    companion object {
        /** Отметки по сохранённым записям группы. Пары, которых больше нет, не отмечаются. */
        fun of(picks: List<Pick>, options: List<SubjectOption>): Selection {
            val all = options.flatMap { it.keys() }
            return if (picks.any { it.wholeGroup }) {
                Selection(true, all.toSet())
            } else {
                Selection(false, all.filter { key -> picks.any { it.matches(key) } }.toSet())
            }
        }
    }
}
