"""Синхронный JSON-интерфейс парсера для мобильного приложения.

Не импортирует ical (pydantic-core не собирается под Android), поэтому работает
в Chaquopy. Все функции возвращают JSON-строки, а ошибки — {"error": "..."}.
"""

import asyncio
import datetime
import json
from collections.abc import Awaitable, Callable
from dataclasses import replace
from typing import Any

from .api import MietApiError, MietClient, create_http_client
from .custom import Pick, combine, unmatched
from .events import build_occurrences
from .holidays import parse_dates, public_holidays
from .models import Lesson, Schedule
from .semester import WEEK_NAMES, Semester

OPTIONAL_SUBJECTS = ("Практическая подготовка", "Военная подготовка")
DAY_NAMES = ("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")


def list_groups() -> str:
    """JSON: {"groups": [...]}."""

    async def run(client: MietClient) -> dict[str, Any]:
        return {"groups": await client.groups()}

    return _call(run)


def group_subjects(group: str) -> str:
    """Предметы группы для выбора своего расписания.

    JSON: {"group", "semester", "subjects": [{"name", "subject", "kind",
    "entries": [{"day", "week", "slot", "label", "teacher", "room"}]}]}.
    name — полное название с типом занятия, по нему выбирается предмет.
    entries — занятия 4-недельного цикла, по ним выбирается отдельная пара.
    """

    async def run(client: MietClient) -> dict[str, Any]:
        name = await client.resolve_group(group)
        schedule = Schedule.from_api(await client.schedule(name))

        by_name: dict[str, dict[tuple[int, int, int], list[Lesson]]] = {}
        for lesson in schedule.lessons:
            key = (lesson.day, lesson.week, lesson.slot.code)
            by_name.setdefault(lesson.name, {}).setdefault(key, []).append(lesson)

        subjects = []
        for subject_name, entries in by_name.items():
            first = next(iter(entries.values()))[0]
            subjects.append(
                {
                    "name": subject_name,
                    "subject": first.base_subject,
                    "kind": first.kind,
                    "entries": [
                        {
                            "day": day,
                            "week": week,
                            "slot": slot,
                            "label": _entry_label(lessons[0]),
                            "teacher": _join(
                                x.teacher_full or x.teacher for x in lessons
                            ),
                            "room": _join(x.room for x in lessons),
                        }
                        for (day, week, slot), lessons in sorted(
                            entries.items(), key=lambda item: _cycle_order(*item[0])
                        )
                    ],
                }
            )
        # Пометки вроде "[ДВ]" не должны уводить предмет в начало списка.
        subjects.sort(key=lambda s: (s["subject"].casefold(), s["name"].casefold()))
        return {"group": name, "semester": schedule.semester, "subjects": subjects}

    return _call(run)


def load_schedule(
    group: str,
    start: str | None = None,
    end: str | None = None,
    skip_dates: str = "",
    holidays: bool = True,
    include_practice: bool = False,
    include_military: bool = False,
) -> str:
    """Расписание одной группы. Формат — как у load_picks."""
    return load_picks(
        json.dumps([{"group": group}]),
        start,
        end,
        skip_dates,
        holidays,
        include_practice,
        include_military,
    )


def load_picks(
    picks: str,
    start: str | None = None,
    end: str | None = None,
    skip_dates: str = "",
    holidays: bool = True,
    include_practice: bool = False,
    include_military: bool = False,
) -> str:
    """Расписание из выбранных занятий одной или нескольких групп.

    picks — JSON-список [{"group", "name"?, "day"?, "week"?, "slot"?}], см. Pick.
    JSON: {"title", "groups", "semester", "start", "end", "hidden", "missing",
    "events": [...]}. Даты — ГГГГ-ММ-ДД, время событий — ISO 8601 с часовым
    поясом Europe/Moscow. missing — выбранные занятия, которых больше нет
    в расписании.
    """

    async def run(client: MietClient) -> dict[str, Any]:
        parsed = [Pick.from_dict(p) for p in json.loads(picks)]
        if not parsed:
            raise ValueError("Не выбрано ни одного занятия")

        names = {g: await client.resolve_group(g) for g in {p.group for p in parsed}}
        parsed = [replace(p, group=names[p.group]) for p in parsed]
        groups = list(dict.fromkeys(p.group for p in parsed))
        raw = await asyncio.gather(*(client.schedule(g) for g in groups))
        schedules = {
            g: Schedule.from_api(data) for g, data in zip(groups, raw, strict=True)
        }

        excluded = [
            s
            for s, include in zip(
                OPTIONAL_SUBJECTS, (include_practice, include_military), strict=True
            )
            if not include
        ]
        schedule = combine(schedules, parsed, excluded)

        whole = {
            x.base_subject.casefold()
            for p in parsed
            if p.name is None
            for x in schedules[p.group].lessons
        }
        shown = {x.base_subject.casefold() for x in schedule.lessons}
        hidden = [
            s for s in excluded if s.casefold() in whole and s.casefold() not in shown
        ]
        if not schedule.lessons:
            if hidden:
                raise MietApiError(
                    "В расписании есть только: "
                    + ", ".join(hidden)
                    + ". Включите их в настройках"
                )
            raise MietApiError("В выбранном расписании нет занятий")

        semester = Semester.from_title(schedule.semester).with_bounds(
            datetime.date.fromisoformat(start) if start else None,
            datetime.date.fromisoformat(end) if end else None,
        )
        skip = parse_dates(skip_dates)
        if holidays:
            skip |= public_holidays(semester.start, semester.end)

        return {
            "title": " + ".join(groups),
            "groups": groups,
            "semester": schedule.semester,
            "start": semester.start.isoformat(),
            "end": semester.end.isoformat(),
            "hidden": hidden,
            "missing": [
                {"group": p.group, "name": p.name, "label": _pick_label(p)}
                for p in unmatched(schedules, parsed)
            ],
            "events": [
                {
                    "uid": o.uid,
                    "start": o.start.isoformat(),
                    "end": o.end.isoformat(),
                    "title": o.lesson.subject,
                    "kind": o.lesson.kind,
                    "teacher": o.lesson.teacher_full or o.lesson.teacher,
                    "room": o.lesson.room,
                    "group": o.lesson.group,
                    "slot": o.lesson.slot.code,
                    "week": WEEK_NAMES[o.lesson.week],
                    "distance": o.lesson.distance,
                    "split": o.split,
                    "conflict": o.conflict,
                    "description": o.description,
                }
                for o in build_occurrences(schedule, semester, skip)
            ],
        }

    return _call(run)


def _entry_label(lesson: Lesson) -> str:
    slot = lesson.slot
    return (
        f"{DAY_NAMES[lesson.day - 1]}, {slot.name} "
        f"({slot.start:%H:%M}–{slot.end:%H:%M}), {WEEK_NAMES[lesson.week]}"
    )


def _pick_label(pick: Pick) -> str:
    parts = [pick.group, pick.name or "все занятия"]
    if pick.day is not None:
        parts.append(DAY_NAMES[pick.day - 1])
    if pick.slot is not None:
        parts.append(f"{pick.slot} пара")
    if pick.week is not None:
        parts.append(WEEK_NAMES[pick.week])
    return ", ".join(parts)


def _cycle_order(day: int, week: int, slot: int) -> tuple[int, int, int]:
    return day, slot, week


def _join(values: Any) -> str:
    return ", ".join(dict.fromkeys(v for v in values if v))


def _call(run: Callable[[MietClient], Awaitable[dict[str, Any]]]) -> str:
    async def main() -> dict[str, Any]:
        async with create_http_client() as http:
            return await run(MietClient(http))

    try:
        result = asyncio.run(main())
    except (MietApiError, ValueError) as e:
        result = {"error": str(e)}
    return json.dumps(result, ensure_ascii=False)
