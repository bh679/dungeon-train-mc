#!/usr/bin/env python3
"""No lang file or provenance sidecar may carry the same key twice.

JSON lets an object repeat a key, and every parser this project touches (Gson in the game,
``json.load`` in the scripts) quietly keeps the **last** value. So a duplicated key is not an
error anywhere — it is a dead line that reads as live. #1938 shortened the options tooltips by
adding the new text above the old instead of replacing it: the old, longer text kept winning in
20 of 21 locales for two months, and every check stayed green, because each one loads the file
into a dict first and never sees the line that lost.

This reads the raw key/value pairs before they collapse into a dict.

Usage:  check-lang-duplicates.py [<file-or-dir> ...]
        (default: every ``assets/*/lang/*.json`` and every provenance sidecar)
Exit code is non-zero if any file repeats a key, or fails to parse.
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_GLOBS = (
    "src/main/resources/assets/*/lang/*.json",
    "localization/provenance/**/*.json",
)


def duplicate_keys(text: str) -> list[str]:
    """Every key repeated within one JSON object of ``text``, once per repeat, in file order."""
    repeats: list[str] = []

    def hook(pairs):
        seen = set()
        for key, _ in pairs:
            if key in seen:
                repeats.append(key)
            seen.add(key)
        return dict(pairs)

    json.loads(text, object_pairs_hook=hook)
    return repeats


def key_lines(text: str, key: str) -> list[int]:
    """1-based line numbers whose first token is ``key`` — every file here is one key per line."""
    needle = json.dumps(key, ensure_ascii=False) + ":"
    alt = json.dumps(key) + ":"
    return [n for n, line in enumerate(text.splitlines(), 1)
            if line.lstrip().startswith((needle, alt))]


def default_files() -> list[Path]:
    return sorted({p for g in DEFAULT_GLOBS for p in REPO.glob(g)})


def expand(args: list[str]) -> list[Path]:
    files: list[Path] = []
    for arg in args:
        path = Path(arg)
        files.extend(sorted(path.rglob("*.json")) if path.is_dir() else [path])
    return files


def check(files: list[Path]) -> list[str]:
    problems: list[str] = []
    for path in files:
        try:
            text = path.read_text(encoding="utf-8")
            repeats = duplicate_keys(text)
        except (OSError, ValueError) as e:
            problems.append(f"{path}: cannot parse ({e})")
            continue
        for key in dict.fromkeys(repeats):
            lines = ", ".join(map(str, key_lines(text, key))) or "?"
            problems.append(f"{path}: duplicate key {key!r} (lines {lines})")
    return problems


def main(argv: list[str]) -> int:
    files = expand(argv) if argv else default_files()
    problems = check(files)
    for p in problems:
        print(p)
    if problems:
        print(f"\n{len(problems)} problem(s). The parser keeps the LAST value of a repeated key, "
              "so delete the stale line rather than the one you meant to ship.")
        return 1
    print(f"OK: no duplicate keys in {len(files)} file(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
