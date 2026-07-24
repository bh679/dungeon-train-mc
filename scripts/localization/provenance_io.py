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
# The single generated, shipped translator-credits file (human-grouped) the Credits page reads.
DEFAULT_CONTRIBUTORS_FILE = DEFAULT_CREDITS_DIR.parent / "translation_contributors.json"

AUTHOR_KINDS = ("ai", "human")

# Generated summary fields stamped into each shipped localization credit file.
CREDIT_COUNT_FIELDS = ("total_keys", "ai_authored", "ai_unreviewed")

# Marker written into the generated translation-contributors file so a reader knows
# not to hand-edit it (see build_contributors). Part of the canonical output, so the
# shipped file and a fresh build compare equal.
CONTRIBUTORS_NOTE = (
    "Generated from localization/provenance + authors.json by "
    "scripts/localization/stamp-provenance.py — do not hand-edit."
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


def _load_authors_raw(path: Path) -> dict:
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a flat JSON object of name -> kind")
    return data


def _author_kind(name: str, value, path: Path) -> str:
    """A registry entry's kind, accepting either the bare-string or the object form."""
    if isinstance(value, str):
        kind = value
    elif isinstance(value, dict):
        kind = value.get("kind")
    else:
        raise ValueError(
            f"{path}: {name!r} must be a kind string or an object with a 'kind' field"
        )
    if kind not in AUTHOR_KINDS:
        raise ValueError(f"{path}: {name!r} has kind {kind!r} — must be one of {AUTHOR_KINDS}")
    return kind


def load_authors(path: Path) -> dict[str, str]:
    """The author registry: credited name → "ai" | "human".

    This is what makes "how much of this locale is AI-generated without human
    review" computable — the sidecars store names, the registry classifies them
    once, and check-provenance.py enforces that every name used is registered.

    An entry is either the bare kind string (``"ai"``) or an object carrying more
    metadata (``{"kind": "human", "url": "…"}``); both normalize to name → kind here
    so every existing caller is unaffected. See load_author_urls for the extra fields.
    """
    data = _load_authors_raw(path)
    return {name: _author_kind(name, value, path) for name, value in data.items()}


def load_author_urls(path: Path) -> dict[str, str]:
    """Credited name → optional profile URL, for the object-form registry entries only.

    Names with no object entry (or no ``url``) are simply absent. This is the source
    of the clickable translator links on the Credits page — provenance records who
    translated what, but not who they are, so identity lives here.
    """
    urls: dict[str, str] = {}
    for name, value in _load_authors_raw(path).items():
        if isinstance(value, dict) and value.get("url"):
            url = value["url"]
            if not isinstance(url, str):
                raise ValueError(f"{path}: {name!r} url must be a string, got {type(url).__name__}")
            urls[name] = url
    return urls


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


def contributed_keys(prov: dict, name: str) -> int:
    """How many of ``prov``'s keys ``name`` authored or reviewed.

    A key counts once whether the person is its author, its reviewer, or both —
    this is the "how much of the translation did they touch" measure behind the
    per-translator contribution % on the Credits page. Tolerant of shape errors
    (skips non-dict entries), like ai_counts.
    """
    if not name:
        return 0
    n = 0
    for entry in prov.values():
        if not isinstance(entry, dict):
            continue
        if entry.get("author") == name or entry.get("reviewer") == name:
            n += 1
    return n


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


def build_contributors(lang_dir: Path, prov_dir: Path, authors: dict[str, str],
                       urls: dict[str, str]) -> dict:
    """The canonical translator-credits object, derived purely from the sidecars + registry.

    For every human in ``authors`` and every non-en_us locale, counts the keys that
    human authored or reviewed (contributed_keys); a human is listed once with a
    ``languages`` array of ``{locale, contributed, total}`` for each locale they touched.
    Contributors are ordered by their single strongest share (desc, then name); a
    contributor's languages by share (desc, then locale) — so the output is fully
    deterministic and the shipped file compares equal to a fresh build.
    """
    humans = {name for name, kind in authors.items() if kind == "human"}
    per_person: dict[str, list[dict]] = {}
    for locale in locales(lang_dir):
        prov_path = prov_dir / f"{locale}.json"
        if not prov_path.is_file():
            continue
        prov = load_provenance(prov_path)
        total = len(prov)
        if total == 0:
            continue
        for name in humans:
            contributed = contributed_keys(prov, name)
            if contributed > 0:
                per_person.setdefault(name, []).append(
                    {"locale": locale, "contributed": contributed, "total": total}
                )

    def share(entry: dict) -> float:
        return entry["contributed"] / entry["total"]

    contributors = []
    for name, langs in per_person.items():
        langs = sorted(langs, key=lambda e: (-share(e), e["locale"]))
        entry: dict = {"name": name}
        if name in urls:
            entry["url"] = urls[name]
        entry["languages"] = langs
        contributors.append(entry)
    contributors.sort(key=lambda c: (-max(share(e) for e in c["languages"]), c["name"]))

    return {"_note": CONTRIBUTORS_NOTE, "contributors": contributors}


def load_contributors(path: Path) -> dict:
    """Parse the shipped translator-credits file. Raises ValueError on a non-object root."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a JSON object")
    return data


def write_contributors(path: Path, data: dict) -> None:
    """Write the translator-credits file: indent-2, raw UTF-8 (CJK names), trailing newline."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


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
