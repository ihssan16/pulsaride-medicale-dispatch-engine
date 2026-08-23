#!/usr/bin/env python3
"""Fail if repository files contain obvious API secrets."""

from __future__ import annotations

import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OPENAI_KEY_PATTERN = re.compile(r"\bsk-[A-Za-z0-9][A-Za-z0-9_-]{20,}\b")


def candidate_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    )
    return [
        ROOT / item.decode("utf-8")
        for item in result.stdout.split(b"\0")
        if item
    ]


def is_probably_text(path: Path) -> bool:
    try:
        chunk = path.read_bytes()[:4096]
    except OSError:
        return False
    return b"\0" not in chunk


def main() -> int:
    findings: list[str] = []
    for path in candidate_files():
        if not path.is_file() or not is_probably_text(path):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for line_number, line in enumerate(text.splitlines(), start=1):
            if OPENAI_KEY_PATTERN.search(line):
                findings.append(f"{path.relative_to(ROOT)}:{line_number}")

    if findings:
        print("Potential OpenAI API key found in repository files:")
        for finding in findings:
            print(f"- {finding}")
        print("Move secrets to .env or deployment secrets, then rotate the exposed key.")
        return 1

    print("No obvious API secrets found in repository files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
