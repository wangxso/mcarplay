#!/usr/bin/env python3
"""Extract one exact, nonempty version entry for GitHub release publication."""

from __future__ import annotations

import argparse
from pathlib import Path
import re


def extract_release_notes(changelog: str, tag: str) -> str:
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-test\.[0-9]+)?", tag):
        raise ValueError(f"Unsupported release tag: {tag}")
    pattern = rf"^## \[{re.escape(tag)}\][^\n]*\n(.*?)(?=^## |\Z)"
    entries = re.findall(pattern, changelog, re.MULTILINE | re.DOTALL)
    if len(entries) != 1:
        raise ValueError(f"Expected exactly one changelog entry for {tag}, found {len(entries)}")
    body = entries[0].strip()
    if not body:
        raise ValueError(f"Empty changelog entry for {tag}")
    return f"# mcarplay {tag}\n\n{body}\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--changelog", type=Path, default=Path("CHANGELOG.md"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        notes = extract_release_notes(args.changelog.read_text(encoding="utf-8"), args.tag)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Release notes error: {error}\n")
    args.output.write_text(notes, encoding="utf-8")


if __name__ == "__main__":
    main()
