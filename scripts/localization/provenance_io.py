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

The shipped ``localization_credits/<locale>.json`` assets additionally carry three
GENERATED integer fields (``total_keys``, ``ai_authored``, ``ai_unreviewed``)
summarizing each sidecar — stamped by stamp-provenance.py, hard-checked by
check-provenance.py, and rendered in-game as the blue AI-fraction ring around the
DT logo in the language-selection list.
"""
import json
from pathlib import Path

# scripts/localization/provenance_io.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_LANG_DIR = REPO_ROOT / "src" / "main" / "resources" / "assets" / "dungeontrain" / "lang"
DEFAULT_PROVENANCE_DIR = REPO_ROOT / "localization" / "provenance"
DEFAULT_AUTHORS_FILE = REPO_ROOT / "localization" / "authors.json"
DEFAULT_CREDITS_DIR = (
    REPO_ROOT / "src" / "main" / "resources" / "assets" / "dungeontrain" / "localization_credits"
)

AUTHOR_KINDS = ("ai", "human")

# Generated summary fields stamped into each shipped localization credit file.
CREDIT_COUNT_FIELDS = ("total_keys", "ai_authored", "ai_unreviewed")

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


def load_authors(path: Path) -> dict[str, str]:
    """The author registry: credited name → "ai" | "human".

    This is what makes "how much of this locale is AI-generated without human
    review" computable — the sidecars store names, the registry classifies them
    once, and check-provenance.py enforces that every name used is registered.
    """
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a flat JSON object of name -> kind")
    for name, kind in data.items():
        if kind not in AUTHOR_KINDS:
            raise ValueError(
                f"{path}: {name!r} has kind {kind!r} — must be one of {AUTHOR_KINDS}"
            )
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


def ai_counts(prov: dict, authors: dict[str, str]) -> tuple[int, int, int]:
    """(total keys, AI-authored, AI-authored-and-unreviewed) for one sidecar.

    The (total, ai, unrev) triple behind both check-provenance.py's report and the
    generated credit-file fields. Tolerant of shape errors (skips non-dict entries)
    so callers can compute it before structural validation has run.
    """
    ai = unrev = 0
    for entry in prov.values():
        if not isinstance(entry, dict):
            continue
        if authors.get(entry.get("author")) == "ai":
            ai += 1
            if not entry.get("reviewer"):
                unrev += 1
    return len(prov), ai, unrev


def load_credit(path: Path) -> dict:
    """Parse a shipped localization credit file. Raises ValueError on a non-object root."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a JSON object")
    return data


def credit_paths_for_locale(credits_dir: Path, locale: str) -> list[Path]:
    """Sorted credit files whose ``locale`` field is ``locale``.

    Matches by field, not filename, mirroring LocalizationCreditRegistry (a
    localization pack ships one file per contributor). Malformed files are
    skipped, same as the runtime loader.
    """
    if not credits_dir.is_dir():
        return []
    out: list[Path] = []
    for path in sorted(credits_dir.glob("*.json")):
        try:
            data = load_credit(path)
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError):
            continue
        if data.get("locale") == locale:
            out.append(path)
    return out


def write_credit(path: Path, credit: dict) -> None:
    """Write a credit file in the shipped format: indent-2, raw UTF-8, trailing newline.

    All 19 bundled files already match ``json.dumps(..., indent=2) + "\\n"`` byte-for-
    byte, so a load-modify-write round trip diffs only the changed fields.
    """
    path.write_text(json.dumps(credit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


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
