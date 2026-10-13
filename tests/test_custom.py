from typing import Any

import pytest

from miet_scheduler.custom import Pick, combine, unmatched
from miet_scheduler.events import build_occurrences
from miet_scheduler.models import Schedule
from miet_scheduler.semester import Semester

from .sample import (
    ENTRIES,
    GROUP,
    OPTIONAL_ENTRIES,
    OTHER,
    OTHER_ENTRIES,
    SEMESTER_TITLE,
    TIMES,
)


def make_schedule(
    entries: list[dict[str, Any]], title: str = SEMESTER_TITLE
) -> Schedule:
    return Schedule.from_api({"Times": TIMES, "Data": entries, "Semestr": title})


@pytest.fixture
def schedules() -> dict[str, Schedule]:
    return {
        GROUP: make_schedule([*ENTRIES, *OPTIONAL_ENTRIES]),
        OTHER: make_schedule(OTHER_ENTRIES),
    }


def names(schedule: Schedule) -> list[tuple[str, str]]:
    return sorted((x.group, x.name) for x in schedule.lessons)


def test_whole_group_keeps_all_lessons(schedules: dict[str, Schedule]) -> None:
    result = combine(schedules, [Pick(GROUP)])
    assert result.semester == SEMESTER_TITLE
    assert names(result) == names(schedules[GROUP])


def test_subject_from_another_group(schedules: dict[str, Schedule]) -> None:
    result = combine(schedules, [Pick(GROUP), Pick(OTHER, "Химия [Лек]")])
    chemistry = [x for x in result.lessons if x.group == OTHER]
    assert [(x.name, x.week) for x in chemistry] == [
        ("Химия [Лек]", 0),
        ("Химия [Лек]", 2),
    ]
    assert len(result.lessons) == len(schedules[GROUP].lessons) + 2


def test_single_entry_of_the_cycle(schedules: dict[str, Schedule]) -> None:
    pick = Pick(OTHER, "Химия [Лек]", day=4, week=2, slot=2)
    result = combine(schedules, [pick])
    assert [(x.day, x.week, x.slot.code) for x in result.lessons] == [(4, 2, 2)]


def test_stream_lecture_is_not_duplicated(schedules: dict[str, Schedule]) -> None:
    result = combine(
        schedules,
        [
            Pick(GROUP, "Математический анализ [Лек]"),
            Pick(OTHER, "Математический анализ [Лек]"),
        ],
    )
    assert [(x.group, x.week) for x in result.lessons] == [(GROUP, 0), (GROUP, 1)]


def test_exclude_only_applies_without_subject_name(
    schedules: dict[str, Schedule],
) -> None:
    excluded = ["Военная подготовка"]
    whole = combine(schedules, [Pick(GROUP)], excluded)
    assert "Военная подготовка [Пр]" not in {x.name for x in whole.lessons}
    assert "[ФТД] Основы военной подготовки [Лек]" in {x.name for x in whole.lessons}

    explicit = combine(schedules, [Pick(GROUP, "Военная подготовка [Пр]")], excluded)
    assert [x.name for x in explicit.lessons] == ["Военная подготовка [Пр]"]


def test_semesters_must_match(schedules: dict[str, Schedule]) -> None:
    schedules[OTHER] = make_schedule(OTHER_ENTRIES, "Весенний семестр 2026/2027")
    with pytest.raises(ValueError, match="разных семестров"):
        combine(schedules, [Pick(GROUP), Pick(OTHER)])


def test_empty_picks(schedules: dict[str, Schedule]) -> None:
    with pytest.raises(ValueError, match="Не выбрано"):
        combine(schedules, [])


def test_unmatched_reports_missing_lessons(schedules: dict[str, Schedule]) -> None:
    gone = Pick(OTHER, "Химия [Лек]", day=5, week=0, slot=1)
    renamed = Pick(GROUP, "Физика [Лек]")
    assert unmatched(schedules, [Pick(GROUP), gone, renamed]) == [gone, renamed]


def test_pick_from_dict() -> None:
    assert Pick.from_dict({"group": "П-11"}) == Pick("П-11")
    assert Pick.from_dict(
        {"group": "П-11", "name": "А [Лек]", "day": 1, "week": 2, "slot": 3}
    ) == Pick("П-11", "А [Лек]", 1, 2, 3)


def test_conflicts_between_groups_and_subgroups(
    schedules: dict[str, Schedule], semester: Semester
) -> None:
    result = combine(schedules, [Pick(GROUP), Pick(OTHER, "Химия [Лаб]")])
    occurrences = build_occurrences(result, semester)

    clash = {o.lesson.name for o in occurrences if o.conflict}
    assert clash == {"Физика [Лаб]", "Химия [Лаб]"}
    assert "Пересекается с занятием другой группы" in next(
        o.description for o in occurrences if o.conflict
    )

    # Подгруппы внутри одной группы — не накладка.
    language = [o for o in occurrences if o.lesson.name == "Иностранный язык [Пр]"]
    assert language
    assert all(o.split and not o.conflict for o in language)
