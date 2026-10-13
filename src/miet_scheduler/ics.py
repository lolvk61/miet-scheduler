"""Сборка календаря iCalendar из расписания."""

import datetime
from collections.abc import Collection, Mapping
from pathlib import Path

from ical.calendar import Calendar
from ical.calendar_stream import IcsCalendarStream
from ical.event import Event
from ical.parsing.property import ParsedProperty
from ical.timezone import Timezone

from .events import TIMEZONE, Occurrence, build_occurrences
from .models import Lesson, Schedule
from .semester import Semester

DEFAULT_COLOR = "gray"
PRODID = "-//miet-scheduler//miet-scheduler//RU"


def build_calendar(
    schedule: Schedule,
    semester: Semester,
    color: str = DEFAULT_COLOR,
    color_map: Mapping[str, str] | None = None,
    skip_dates: Collection[datetime.date] = frozenset(),
) -> Calendar:
    """Создаёт календарь, в котором каждое занятие — отдельное событие.

    color — цвет всех событий по умолчанию (имя цвета CSS3, RFC 7986).
    color_map — цвета для отдельных предметов или типов занятий. Ключом может быть
    полное название ("Информатика [Лек]"), название предмета ("Информатика")
    или тип занятия ("Лек").
    skip_dates — даты без занятий (праздники, каникулы).
    """
    color_map = color_map or {}
    group = next((x.group for x in schedule.lessons if x.group), "")
    title = " — ".join(filter(None, [f"МИЭТ {group}".strip(), schedule.semester]))

    calendar = Calendar(
        prodid=PRODID,
        name=[title],
        color=color,
        x_wr_timezone=TIMEZONE,
    )
    calendar.extras.append(ParsedProperty(name="x-wr-calname", value=title))
    calendar.timezones.append(Timezone.from_tzif(TIMEZONE))

    for occurrence in build_occurrences(schedule, semester, skip_dates):
        lesson_color = _resolve_color(occurrence.lesson, color, color_map)
        calendar.events.append(_make_event(occurrence, lesson_color))

    calendar.events.sort(key=lambda e: (e.dtstart, e.summary or ""))
    return calendar


def calendar_to_ics(calendar: Calendar) -> str:
    return IcsCalendarStream.calendar_to_ics(calendar)


def write_ics(calendar: Calendar, path: Path) -> None:
    # RFC 5545 требует CRLF. Библиотека отдаёт "\n", Python заменит его при записи.
    with path.open("w", encoding="utf-8", newline="\r\n") as f:
        f.write(calendar_to_ics(calendar))


def _make_event(occurrence: Occurrence, color: str) -> Event:
    lesson = occurrence.lesson
    return Event(
        uid=occurrence.uid,
        dtstart=occurrence.start,
        dtend=occurrence.end,
        summary=lesson.name,
        location=lesson.room or None,
        description=occurrence.description,
        categories=[lesson.kind] if lesson.kind else [],
        color=color,
    )


def _resolve_color(lesson: Lesson, default: str, color_map: Mapping[str, str]) -> str:
    for key in (lesson.name, lesson.subject, lesson.kind):
        if key and key in color_map:
            return color_map[key]
    return default
