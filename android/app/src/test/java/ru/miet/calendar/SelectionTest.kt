package ru.miet.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private val group = "П-31"

    private fun entry(day: Int, week: Int, slot: Int) =
        SubjectEntry(day, week, slot, label = "", teacher = "", room = "")

    private val lecture = SubjectOption(
        name = "Химия [Лек]",
        subject = "Химия",
        kind = "Лек",
        entries = listOf(entry(4, 0, 2), entry(4, 2, 2)),
    )
    private val lab = SubjectOption(
        name = "Химия [Лаб]",
        subject = "Химия",
        kind = "Лаб",
        entries = listOf(entry(2, 0, 3), entry(2, 1, 3), entry(2, 2, 3), entry(2, 3, 3)),
    )
    private val options = listOf(lab, lecture)

    @Test
    fun wholeGroupSelectsEverything() {
        val selection = Selection.of(listOf(Pick(group)), options)
        assertTrue(selection.whole)
        assertEquals(6, selection.keys.size)
        assertEquals(listOf(Pick(group)), selection.toPicks(group, options))
    }

    @Test
    fun newGroupStartsEmpty() {
        val selection = Selection.of(emptyList(), options)
        assertFalse(selection.whole)
        assertTrue(selection.keys.isEmpty())
        assertTrue(selection.toPicks(group, options).isEmpty())
    }

    @Test
    fun fullySelectedSubjectIsSavedBySubject() {
        val selection = Selection(false, lecture.keys().toSet())
        assertEquals(listOf(Pick(group, "Химия [Лек]")), selection.toPicks(group, options))
    }

    @Test
    fun partialSubjectIsSavedByEntries() {
        // Лабораторные только по числителям: своя подгруппа.
        val keys = lab.keys().filter { it.week % 2 == 0 }.toSet()
        val picks = Selection(false, keys).toPicks(group, options)
        assertEquals(
            listOf(
                Pick(group, "Химия [Лаб]", day = 2, week = 0, slot = 3),
                Pick(group, "Химия [Лаб]", day = 2, week = 2, slot = 3),
            ),
            picks,
        )
    }

    @Test
    fun savedPicksRoundTrip() {
        val picks = listOf(
            Pick(group, "Химия [Лек]"),
            Pick(group, "Химия [Лаб]", day = 2, week = 1, slot = 3),
        )
        val selection = Selection.of(picks, options)
        assertFalse(selection.whole)
        assertEquals(3, selection.keys.size)
        assertEquals(picks.sortedBy { it.toString() }, selection.toPicks(group, options).sortedBy { it.toString() })
    }

    @Test
    fun picksThatNoLongerMatchAreDropped() {
        val gone = Pick(group, "Химия [Лаб]", day = 5, week = 0, slot = 1)
        val selection = Selection.of(listOf(gone, Pick(group, "Химия [Лек]")), options)
        assertEquals(listOf(Pick(group, "Химия [Лек]")), selection.toPicks(group, options))
    }

    @Test
    fun picksSurviveJson() {
        val picks = listOf(Pick(group), Pick(group, "Химия [Лаб]", day = 2, week = 0, slot = 3))
        assertEquals(picks, parsePicks(picks.toJsonString()))
    }
}
