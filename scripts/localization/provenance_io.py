#!/usr/bin/env python3
"""Shared IO for the per-line translation provenance sidecars.

Every non-English locale in ``src/main/resources/assets/dungeontrain/lang/`` has a
sidecar at ``localization/provenance/<locale>.json`` (repo-side only — nothing under
``localization/`` ships in the jar) recording, for every translation key, who produced
the current value and who human-reviewed it:

    {
      "gui.dungeontrain.book_vote.ask_prefix": {"author": "Opus 4.8 (Claude)", "reviewer": ""},
      "gui.dungeontrain.support.title": {"author": "阿世xAsh", "reviewer": "阿世xAsh"}
    }

Format contract (enforced by check-provenance.py, produced by write_provenance):
  * flat object, keys in the SAME ORDER as that locale's lang file, so provenance
    diffs align line-for-line with lang-file diffs;
  * each entry is exactly ``{"author": str, "reviewer": str}`` — author non-empty,
    reviewer ``""`` meaning "not human-reviewed";
  * one entry per line, raw UTF-8 (``ensure_ascii=False`` — translator names are CJK),
    trailing newline.

The one-entry-per-line shape is why this module has its own serializer instead of
reusing ``scripts/release-notes/changelog_io.write_json`` (which ASCII-escapes and
nests objects across lines).
"""
import json
from pathlib import Path

# scripts/localization/provenance_io.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_LANG_DIR = REPO_ROOT / "src" / "main" / "resources" / "assets" / "dungeontrain" / "lang"
DEFAULT_PROVENANCE_DIR = REPO_ROOT / "localization" / "provenance"
DEFAULT_CREDITS_DIR = (
    REPO_ROOT / "src" / "main" / "resources" / "assets" / "dungeontrain" / "localization_credits"
)

# The source language — dev-authored English, not a translation. Never gets a sidecar.
SOURCE_LOCALE = "en_us"

ENTRY_FIELDS = ("author", "reviewer")


def locales(lang_dir: Path) -> list[str]:
    """The non-English locale codes present in ``lang_dir``, sorted."""
    return sorted(
        p.stem for p in lang_dir.glob("*.json") if p.stem != SOURCE_LOCALE
    )


def load_lang(path: Path) -> dict[str, str]:
    """Load a lang file preserving key order (``json.load`` is insertion-ordered)."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a flat JSON object")
    return data


def load_provenance(path: Path) -> dict:
    """Parse a provenance sidecar. Parse only — validate with validate_entries()."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a flat JSON object")
    return data


def validate_entries(prov: dict) -> list[str]:
    """Structural errors for a parsed provenance object (empty list == valid).

    Checks entry shape only — key alignment against the lang file is the caller's
    job (check-provenance.py), because it needs the lang file for context.
    """
    errors: list[str] = []
    for key, entry in prov.items():
        if not isinstance(entry, dict):
            errors.append(f"{key}: entry must be an object, got {type(entry).__name__}")
            continue
        unknown = sorted(set(entry) - set(ENTRY_FIELDS))
        missing = [f for f in ENTRY_FIELDS if f not in entry]
        if unknown:
            errors.append(f"{key}: unknown field(s) {', '.join(unknown)}")
        if missing:
            errors.append(f"{key}: missing field(s) {', '.join(missing)}")
        if unknown or missing:
            continue
        for field in ENTRY_FIELDS:
            if not isinstance(entry[field], str):
                errors.append(
                    f"{key}: {field} must be a string, got {type(entry[field]).__name__}"
                )
        if isinstance(entry.get("author"), str) and not entry["author"].strip():
            errors.append(f"{key}: author must be non-empty (reviewer may be \"\", author may not)")
    return errors


def write_provenance(path: Path, prov: dict) -> None:
    """Write a sidecar in the canonical one-entry-per-line format.

    Emits keys in ``prov``'s iteration order — callers are responsible for ordering
    entries to match the locale's lang file before writing.
    """
    lines = ["{"]
    items = list(prov.items())
    for i, (key, entry) in enumerate(items):
        ordered = {f: entry[f] for f in ENTRY_FIELDS}
        line = (
            "  "
            + json.dumps(key, ensure_ascii=False)
            + ": "
            + json.dumps(ordered, ensure_ascii=False, separators=(", ", ": "))
        )
        if i < len(items) - 1:
            line += ","
        lines.append(line)
    lines.append("}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
