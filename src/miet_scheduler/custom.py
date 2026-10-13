"""Своё расписание из занятий разных групп."""

from collections.abc import Collection, Iterable, Mapping
from dataclasses import dataclass, replace
from typing import Any

from .models import Lesson, Schedule


@dataclass(frozen=True)
class Pick:
    """Что взять из расписания группы. Пустое поле выбор не ограничивает.

    Pick("П-11") — все занятия группы.
    Pick("П-11", "Информатика [Лек]") — все лекции по информатике.
    Pick("П-11", "Информатика [Лек]", day=1, week=0, slot=2) — одно занятие
    4-недельного цикла: понедельник, 1-й числитель, 2-я пара.
    """

    group: str
    name: str | None = None
    day: int | None = None
    week: int | None = None
    slot: int | None = None

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> Pick:
        return cls(
            group=str(data["group"]),
            name=data.get("name"),
            day=data.get("day"),
            week=data.get("week"),
            slot=data.get("slot"),
        )

    def matches(self, lesson: Lesson) -> bool:
        return all(
            want is None or want == have
            for want, have in (
                (self.name, lesson.name),
                (self.day, lesson.day),
                (self.week, lesson.week),
                (self.slot, lesson.slot.code),
            )
        )


def combine(
    schedules: Mapping[str, Schedule],
    picks: Iterable[Pick],
    exclude: Collection[str] = (),
) -> Schedule:
    """Собирает расписание из выбранных занятий нескольких групп.

    schedules — расписания по названию группы, picks ссылаются на эти названия.
    exclude — предметы, которые не попадают в выбор без названия предмета
    (например, «Военная подготовка» при выборе всех занятий группы). Выбранные
    по названию занятия добавляются всегда.
    Одно и то же занятие у нескольких групп (поточная лекция) попадает один раз.
    """
    picks = list(picks)
    if not picks:
        raise ValueError("Не выбрано ни одного занятия")
    titles = {schedules[p.group].semester for p in picks}
    if len(titles) > 1:
        raise ValueError("Группы из разных семестров: " + ", ".join(sorted(titles)))

    excluded = {s.casefold() for s in exclude}
    seen = set()
    lessons = []
    for pick in picks:
        for lesson in schedules[pick.group].lessons:
            if not pick.matches(lesson):
                continue
            if pick.name is None and lesson.base_subject.casefold() in excluded:
                continue
            key = (
                lesson.name,
                lesson.day,
                lesson.week,
                lesson.slot.code,
                lesson.teacher_full,
                lesson.room,
            )
            if key in seen:
                continue
            seen.add(key)
            lessons.append(
                lesson if lesson.group else replace(lesson, group=pick.group)
            )

    lessons.sort(key=lambda x: (x.week, x.day, x.slot.code))
    return Schedule(semester=titles.pop(), lessons=lessons)


def unmatched(schedules: Mapping[str, Schedule], picks: Iterable[Pick]) -> list[Pick]:
    """Выбранные занятия, которых больше нет в расписании групп.

    Так видно, что занятие перенесли или убрали, и оно не пропадает молча.
    """
    return [
        p
        for p in picks
        if not any(p.matches(lesson) for lesson in schedules[p.group].lessons)
    ]
