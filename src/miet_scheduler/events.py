"""Занятия, развёрнутые по датам семестра. Не зависит от библиотеки ical."""

import datetime
import hashlib
from collections import Counter, defaultdict
from collections.abc import Collection
from dataclasses import dataclass
from zoneinfo import ZoneInfo

from .models import KIND_NAMES, Lesson, Schedule
from .semester import WEEK_NAMES, Semester

TIMEZONE = "Europe/Moscow"


@dataclass(frozen=True)
class Occurrence:
    """Одно занятие в конкретный день."""

    uid: str
    start: datetime.datetime
    end: datetime.datetime
    lesson: Lesson
    split: bool
    conflict: bool = False

    @property
    def description(self) -> str:
        return make_description(self.lesson, self.split, self.conflict)


def build_occurrences(
    schedule: Schedule,
    semester: Semester,
    skip_dates: Collection[datetime.date] = frozenset(),
) -> list[Occurrence]:
    """Каждое занятие расписания на каждую дату семестра, кроме skip_dates.

    В своём расписании из нескольких групп занятия разных групп в одной паре
    помечаются как накладка (conflict).
    """
    tz = ZoneInfo(TIMEZONE)
    # Несколько записей группы в одном слоте — это занятия по подгруппам.
    slot_counts = Counter(
        (x.group, x.week, x.day, x.slot.code) for x in schedule.lessons
    )
    slot_groups = defaultdict(set)
    for x in schedule.lessons:
        slot_groups[(x.week, x.day, x.slot.code)].add(x.group)

    result = []
    for lesson in schedule.lessons:
        slot = (lesson.week, lesson.day, lesson.slot.code)
        split = slot_counts[(lesson.group, *slot)] > 1
        conflict = len(slot_groups[slot]) > 1
        for date in semester.dates_for(lesson.day, lesson.week):
            if date in skip_dates:
                continue
            result.append(
                Occurrence(
                    uid=make_uid(lesson, date, schedule.semester),
                    start=datetime.datetime.combine(date, lesson.slot.start, tzinfo=tz),
                    end=datetime.datetime.combine(date, lesson.slot.end, tzinfo=tz),
                    lesson=lesson,
                    split=split,
                    conflict=conflict,
                )
            )
    result.sort(key=lambda o: (o.start, o.lesson.name))
    return result


def make_description(lesson: Lesson, split: bool, conflict: bool = False) -> str:
    slot = lesson.slot
    lines = []
    if teacher := lesson.teacher_full or lesson.teacher:
        lines.append(f"Преподаватель: {teacher}")
    if lesson.kind:
        lines.append(f"Тип: {KIND_NAMES.get(lesson.kind, lesson.kind)}")
    if lesson.room:
        lines.append(f"Аудитория: {lesson.room}")
    lines.append(f"Пара: {slot.name} ({slot.start:%H:%M}–{slot.end:%H:%M})")
    lines.append(f"Неделя: {WEEK_NAMES[lesson.week]}")
    if lesson.group:
        lines.append(f"Группа: {lesson.group}")
    if lesson.distance:
        lines.append("Формат: дистанционно")
    if split:
        lines.append("Занятие по подгруппам")
    if conflict:
        lines.append("Пересекается с занятием другой группы")
    return "\n".join(lines)


def make_uid(lesson: Lesson, date: datetime.date, semester_title: str) -> str:
    # UID стабилен между запусками: при повторном импорте события обновятся,
    # а не задублируются.
    key = "|".join(
        str(x)
        for x in (
            lesson.group,
            semester_title,
            lesson.class_code,
            lesson.name,
            lesson.slot.code,
            lesson.room_code,
            lesson.teacher_full,
            date.isoformat(),
        )
    )
    return f"{hashlib.sha1(key.encode()).hexdigest()}@miet-scheduler"
