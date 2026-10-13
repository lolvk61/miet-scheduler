import json
import subprocess
import sys
from typing import Any

import httpx
import pytest

from miet_scheduler import mobile

from .sample import GROUP, OTHER, OTHER_ENTRIES, SEMESTER_TITLE, TIMES


@pytest.fixture(autouse=True)
def fake_site(monkeypatch: pytest.MonkeyPatch, payload: dict[str, Any]) -> None:
    other = {"Times": TIMES, "Data": OTHER_ENTRIES, "Semestr": SEMESTER_TITLE}

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/schedule/groups":
            return httpx.Response(200, json=[GROUP, OTHER])
        return httpx.Response(
            200, json=other if request.url.params["group"] == OTHER else payload
        )

    monkeypatch.setattr(
        mobile,
        "create_http_client",
        lambda: httpx.AsyncClient(transport=httpx.MockTransport(handler)),
    )


def load_picks(*picks: dict[str, Any], **kwargs: Any) -> dict[str, Any]:
    return json.loads(mobile.load_picks(json.dumps(list(picks)), **kwargs))


def test_list_groups() -> None:
    assert json.loads(mobile.list_groups()) == {"groups": [GROUP, OTHER]}


def test_load_schedule_returns_events() -> None:
    data = json.loads(mobile.load_schedule(GROUP.lower()))
    assert data["title"] == GROUP
    assert data["groups"] == [GROUP]
    assert data["start"] == "2026-09-01"
    assert data["missing"] == []
    assert data["events"]
    first = data["events"][0]
    assert first["start"].endswith("+03:00")
    assert {"uid", "title", "room", "teacher", "group", "slot", "week"} <= first.keys()
    assert not any(e["conflict"] for e in data["events"])
    uids = [e["uid"] for e in data["events"]]
    assert len(uids) == len(set(uids))


def test_skip_dates_remove_events() -> None:
    all_events = json.loads(mobile.load_schedule(GROUP))["events"]
    day = all_events[0]["start"][:10]
    left = json.loads(mobile.load_schedule(GROUP, skip_dates=day))["events"]
    assert all(e["start"][:10] != day for e in left)
    assert len(left) < len(all_events)


def test_errors_are_returned_as_json() -> None:
    assert "error" in json.loads(mobile.load_schedule("нет-такой"))
    assert "error" in json.loads(mobile.load_schedule(GROUP, start="не дата"))
    assert "error" in load_picks()


def test_group_subjects() -> None:
    data = json.loads(mobile.group_subjects(OTHER.lower()))
    assert data["group"] == OTHER
    by_name = {s["name"]: s for s in data["subjects"]}
    assert list(by_name) == [
        "Математический анализ [Лек]",
        "Химия [Лаб]",
        "Химия [Лек]",
    ]

    lecture = by_name["Химия [Лек]"]
    assert (lecture["subject"], lecture["kind"]) == ("Химия", "Лек")
    assert [(e["day"], e["week"], e["slot"]) for e in lecture["entries"]] == [
        (4, 0, 2),
        (4, 2, 2),
    ]
    assert lecture["entries"][0]["label"] == "Чт, 2 пара (10:30–11:50), 1 числитель"
    assert lecture["entries"][0]["room"] == "2101"


def test_group_subjects_merges_parallel_rooms() -> None:
    data = json.loads(mobile.group_subjects(GROUP))
    # "[ДСТ] История России" сортируется по названию без пометки.
    assert [s["subject"] for s in data["subjects"]] == [
        "Иностранный язык",
        "История России",
        "Математический анализ",
        "Программирование",
        "Физика",
    ]
    language = next(s for s in data["subjects"] if s["name"] == "Иностранный язык [Пр]")
    [entry] = language["entries"]
    assert entry["room"] == "3242, 3303 а"


def test_load_picks_from_two_groups() -> None:
    data = load_picks(
        {"group": GROUP},
        {"group": OTHER, "name": "Химия [Лек]"},
        {"group": OTHER, "name": "Химия [Лаб]", "day": 2, "week": 0, "slot": 3},
    )
    assert data["title"] == f"{GROUP} + {OTHER}"
    assert data["groups"] == [GROUP, OTHER]
    groups = {(e["group"], e["title"]) for e in data["events"]}
    assert (OTHER, "Химия") in groups
    assert (GROUP, "Физика") in groups
    conflicts = {e["title"] for e in data["events"] if e["conflict"]}
    assert conflicts == {"Физика", "Химия"}


def test_load_picks_reports_missing() -> None:
    data = load_picks(
        {"group": OTHER, "name": "Химия [Лек]"},
        {"group": OTHER, "name": "Химия [Лек]", "day": 5, "week": 1, "slot": 1},
    )
    assert data["missing"] == [
        {
            "group": OTHER,
            "name": "Химия [Лек]",
            "label": f"{OTHER}, Химия [Лек], Пт, 1 пара, 1 знаменатель",
        }
    ]


def test_load_picks_without_lessons() -> None:
    data = load_picks({"group": OTHER, "name": "Нет такого [Лек]"})
    assert data["error"] == "В выбранном расписании нет занятий"


def test_mobile_does_not_import_ical() -> None:
    code = "import sys, miet_scheduler.mobile; assert 'ical' not in sys.modules"
    subprocess.run([sys.executable, "-c", code], check=True)
