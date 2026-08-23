#!/usr/bin/env python3
"""Validate Pulsaride V2 event examples against their JSON schemas."""

from __future__ import annotations

import json
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[1]
EVENTS_DIR = ROOT / "docs" / "events"
SCHEMAS_DIR = EVENTS_DIR / "schemas"
EXAMPLES_DIR = EVENTS_DIR / "examples"


REQUEST_KEY_EVENTS = {
    "request.created.v1",
    "request.triaged.v1",
    "triage.failed.v1",
    "dispatch.proposed.v1",
    "dispatch.accepted.v1",
    "dispatch.refused.v1",
    "dispatch.timed-out.v1",
    "dispatch.closed.v1",
}


def load_json(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def validator_for(schema_path: Path) -> Draft202012Validator:
    return Draft202012Validator(load_json(schema_path), format_checker=FormatChecker())


def assert_operational_key(event: dict, example_path: Path) -> None:
    event_type = event["eventType"]
    payload = event["payload"]

    if event_type in REQUEST_KEY_EVENTS:
        expected = payload["requestId"]
    elif event_type == "availability.changed.v1":
        expected = payload["professionalId"]
    else:
        raise AssertionError(f"{example_path}: unsupported eventType {event_type}")

    if event["aggregateId"] != expected:
        raise AssertionError(
            f"{example_path}: aggregateId must be {expected!r}, "
            f"got {event['aggregateId']!r}"
        )


def main() -> int:
    envelope_validator = validator_for(SCHEMAS_DIR / "event-envelope.schema.json")
    example_paths = sorted(EXAMPLES_DIR.glob("*.json"))

    if not example_paths:
        raise AssertionError(f"No event examples found under {EXAMPLES_DIR}")

    for example_path in example_paths:
        event = load_json(example_path)
        envelope_validator.validate(event)

        payload_schema = SCHEMAS_DIR / f"{event['eventType']}.payload.schema.json"
        if not payload_schema.exists():
            raise AssertionError(f"{example_path}: missing payload schema {payload_schema.name}")

        validator_for(payload_schema).validate(event["payload"])
        assert_operational_key(event, example_path)
        print(f"OK {example_path.relative_to(ROOT)}")

    print(f"Validated {len(example_paths)} V2 event example(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
