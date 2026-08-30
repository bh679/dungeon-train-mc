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

Those counts answer "how much of this locale is machine-translated"; the in-game
translation editor needs the finer question "WHICH lines are". That is the shipped
``localization_provenance/<locale>.json`` manifest (see build_manifest) — the only
part of this per-line system that enters the jar, and likewise generated, never
hand-edited.
"""
import json
import re
from dataclasses import dataclass
from pathlib import Path

# scripts/localization/provenance_io.py -> repo root is two levels up.
REPO_ROOT = Path(__file__).resolve().parents[2]
ASSETS_DIR = REPO_ROOT / "src" / "main" / "resources" / "assets"
DEFAULT_LANG_DIR = ASSETS_DIR / "dungeontrain" / "lang"
DEFAULT_PROVENANCE_DIR = REPO_ROOT / "localization" / "provenance"
DEFAULT_AUTHORS_FILE = REPO_ROOT / "localization" / "authors.json"
DEFAULT_CREDITS_DIR = ASSETS_DIR / "dungeontrain" / "localization_credits"
# The single generated, shipped translator-credits file (human-grouped) the Credits page reads.
DEFAULT_CONTRIBUTORS_FILE = DEFAULT_CREDITS_DIR.parent / "translation_contributors.json"
# Generated, shipped per-locale manifests of which units are AI-authored-and-unreviewed —
# what the in-game translation editor filters on. See build_manifest.
DEFAULT_MANIFEST_DIR = ASSETS_DIR / "dungeontrain" / "localization_provenance"

# Long-form narrative book translations (data/, not assets/) — one JSON per book per
# locale under a locale dir. Tracked per-book, so their sidecars are keyed by the book's
# path relative to the locale dir (see narrative_books). Repo-side only; never ships.
DEFAULT_NARRATIVE_DIR = (
    REPO_ROOT / "src" / "main" / "resources" / "data" / "dungeontrain" / "narrative_localizations"
)
DEFAULT_NARRATIVE_PROVENANCE_DIR = REPO_ROOT / "localization" / "narrative_provenance"

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

# Sibling mods whose (community-contributed) translations are committed here in DT rather
# than in their English-only source repos — so their provenance is tracked here too. Their
# lang files are the same flat-key shape as dungeontrain's, so they reuse the whole per-line
# system; they only differ in having no shipped credit files / AI-ring (bookkeeping only).
SIBLING_NAMESPACES = ("adventureitemnames", "playermob", "discordpresence")


@dataclass(frozen=True)
class Namespace:
    """One lang-file namespace tracked by the per-line provenance system.

    ``credits_dir``/``contributors_file`` are None for namespaces that carry
    author/reviewer bookkeeping only (the siblings) — no shipped AI-fraction ring or
    translator-credits file. Callers skip those steps when the field is None.
    """
    name: str
    lang_dir: Path
    prov_dir: Path
    credits_dir: Path | None
    contributors_file: Path | None


def namespaces() -> list[Namespace]:
    """Every namespace whose committed translations carry provenance, in check order.

    dungeontrain keeps its flat ``localization/provenance/<locale>.json`` layout (and its
    shipped credits); each sibling gets a ``localization/provenance/<name>/`` subdir and no
    shipped credits. The sibling subdirs are directories, so dungeontrain's ``*.json`` orphan
    glob never sees them.
    """
    out = [Namespace("dungeontrain", DEFAULT_LANG_DIR, DEFAULT_PROVENANCE_DIR,
                     DEFAULT_CREDITS_DIR, DEFAULT_CONTRIBUTORS_FILE)]
    for name in SIBLING_NAMESPACES:
        out.append(Namespace(name, ASSETS_DIR / name / "lang",
                             DEFAULT_PROVENANCE_DIR / name, None, None))
    return out


def narrative_locales(narrative_dir: Path) -> list[str]:
    """The locale codes present under ``narrative_dir`` (one subdir per locale), sorted.

    Unlike lang locales there is no en_us here — the English books live in a separate
    ``narratives/`` tree, not as a locale under narrative_localizations.
    """
    if not narrative_dir.is_dir():
        return []
    return sorted(p.name for p in narrative_dir.iterdir() if p.is_dir())


def narrative_books(locale_dir: Path) -> list[str]:
    """Provenance keys for one locale: each book's path relative to ``locale_dir``, sans
    ``.json`` suffix, POSIX-style and sorted (e.g. ``random_books/deathnote``)."""
    return sorted(
        p.relative_to(locale_dir).with_suffix("").as_posix()
        for p in locale_dir.rglob("*.json")
    )


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


# A body whose every unit is AI-authored-and-unreviewed, written instead of listing them
# all. 17 of the 19 locales are in exactly that state, so this is what keeps the shipped
# manifests to a few hundred bytes each rather than ~45 KB.
MANIFEST_ALL = "*"

MANIFEST_NOTE = (
    "Generated from localization/provenance + localization/narrative_provenance by "
    "scripts/localization/stamp-provenance.py — do not hand-edit. \"*\" = every unit in "
    "that body is AI-authored and unreviewed."
)


def ai_unreviewed_keys(prov: dict, authors: dict[str, str]) -> list[str]:
    """The sidecar's AI-authored-and-unreviewed keys, in sidecar (= lang file) order.

    The per-unit form of ai_counts' third element, and the same predicate: author
    registered "ai" AND reviewer empty. Tolerant of shape errors, like ai_counts.
    """
    out: list[str] = []
    for key, entry in prov.items():
        if not isinstance(entry, dict):
            continue
        if authors.get(entry.get("author")) == "ai" and not entry.get("reviewer"):
            out.append(key)
    return out


def compact_flags(total: int, flagged: list[str]) -> str | list[str]:
    """``flagged`` as the manifest writes it: MANIFEST_ALL when it covers the whole body.

    ``total`` is the body's unit count. An empty body is never "all" — "everything in
    nothing" would read in-game as "this whole locale is unreviewed machine output".
    """
    if total > 0 and len(flagged) == total:
        return MANIFEST_ALL
    return flagged


def expand_flags(value, units: list[str]) -> list[str]:
    """The inverse of compact_flags: a manifest value back to an explicit key list.

    ``units`` is the body's full unit list, returned as-is for MANIFEST_ALL.
    """
    if value == MANIFEST_ALL:
        return list(units)
    return list(value) if isinstance(value, list) else []


def build_manifest(locale: str, authors: dict[str, str],
                   ns_list: list[Namespace] | None = None,
                   narrative_prov_dir: Path = DEFAULT_NARRATIVE_PROVENANCE_DIR) -> dict:
    """The canonical shipped manifest for one locale, derived purely from the sidecars.

    Spans BOTH provenance bodies — every lang namespace plus the narrative books — because
    the in-game editor lists all of them in one screen and needs one lookup. That is why
    this lives here rather than in either stamp script: both of them rebuild it, and
    check-provenance.py verifies it, from this single definition.

    A body with no sidecar for this locale is omitted rather than emitted empty (e.g.
    discordpresence has no zh_cn — its Chinese lives in its own repo), so "absent" stays
    distinguishable from "nothing needs review".
    """
    manifest: dict = {"_note": MANIFEST_NOTE, "locale": locale}

    lang: dict[str, str | list[str]] = {}
    for ns in ns_list if ns_list is not None else namespaces():
        prov_path = ns.prov_dir / f"{locale}.json"
        if not prov_path.is_file():
            continue
        prov = load_provenance(prov_path)
        lang[ns.name] = compact_flags(len(prov), ai_unreviewed_keys(prov, authors))
    manifest["lang"] = lang

    books_path = narrative_prov_dir / f"{locale}.json"
    if books_path.is_file():
        books = load_provenance(books_path)
        manifest["books"] = compact_flags(len(books), ai_unreviewed_keys(books, authors))
    return manifest


def build_all_manifests(authors: dict[str, str], lang_dir: Path = DEFAULT_LANG_DIR,
                        ns_list: list[Namespace] | None = None,
                        narrative_prov_dir: Path = DEFAULT_NARRATIVE_PROVENANCE_DIR
                        ) -> dict[str, dict]:
    """locale -> manifest, for every locale that ships a lang file (the credits locale set)."""
    return {
        locale: build_manifest(locale, authors, ns_list, narrative_prov_dir)
        for locale in locales(lang_dir)
    }


def load_manifest(path: Path) -> dict:
    """Parse a shipped provenance manifest. Raises ValueError on a non-object root."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if not isinstance(data, dict):
        raise ValueError(f"{path}: expected a JSON object")
    return data


def write_manifest(path: Path, manifest: dict) -> None:
    """Write a manifest in the shipped format: indent-2, raw UTF-8, trailing newline."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def refresh_manifests(authors: dict[str, str], lang_dir: Path = DEFAULT_LANG_DIR,
                      ns_list: list[Namespace] | None = None,
                      narrative_prov_dir: Path = DEFAULT_NARRATIVE_PROVENANCE_DIR,
                      manifest_dir: Path = DEFAULT_MANIFEST_DIR) -> list[Path]:
    """Rebuild every shipped manifest; returns the paths that changed (written or deleted).

    Global by design, and called by BOTH stamp scripts whatever their --locale/--namespace
    filter: one manifest spans every lang namespace plus the books, so a narrative-only
    stamp still has to rewrite it. Byte-identical writes are skipped so untouched locales
    never churn, and a manifest whose locale no longer ships a lang file is deleted rather
    than left behind as a stale jar asset.

    Lives here rather than in either stamp script because the two cannot import each other
    (both filenames are hyphenated) — and because a second copy of this logic is exactly how
    the two bodies would drift apart.
    """
    built = build_all_manifests(authors, lang_dir, ns_list, narrative_prov_dir)
    changed: list[Path] = []
    for locale, manifest in built.items():
        path = manifest_dir / f"{locale}.json"
        if path.is_file():
            try:
                if load_manifest(path) == manifest:
                    continue
            except (json.JSONDecodeError, ValueError):
                pass  # unparseable -> rewrite
        write_manifest(path, manifest)
        changed.append(path)
    if manifest_dir.is_dir():
        for path in sorted(manifest_dir.glob("*.json")):
            if path.stem not in built:
                path.unlink()
                changed.append(path)
    return changed


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


# ---- editing the translated content itself -----------------------------------
#
# The two scripts that write a human's translation back into the repo —
# apply-review-csv.py (the CSV return leg) and import-approved-translations.py (the
# relay return leg) — need the same edits, so they live here rather than in either one.

NS_PREFIX = re.compile(r"^\[([a-z]+)\]\s+(.*)$")


def split_namespace(key: str) -> tuple[str, str]:
    """``"[playermob] some.key"`` -> ("playermob", "some.key"); bare keys -> dungeontrain."""
    m = NS_PREFIX.match(key)
    return (m.group(1), m.group(2)) if m else ("dungeontrain", key)


def set_lang_value(path: Path, key: str, value: str) -> bool:
    """Replace one key's value in a lang file, preserving formatting and line endings.

    A text-level edit rather than a load/dump round trip: the lang files carry blank-line
    grouping that json.dump would flatten, and zh_cn.json is CRLF. Returns False when the
    key is not in the file — the caller reports it rather than inventing a line.
    """
    raw = read_verbatim(path)
    eol = "\r\n" if "\r\n" in raw else "\n"
    lines = raw.replace("\r\n", "\n").split("\n")
    needle = json.dumps(key, ensure_ascii=False) + ":"
    for i, line in enumerate(lines):
        if line.strip().startswith(needle):
            comma = "," if line.rstrip().endswith(",") else ""
            lines[i] = f"  {json.dumps(key, ensure_ascii=False)}: {json.dumps(value, ensure_ascii=False)}{comma}"
            write_verbatim(path, eol.join(lines))
            return True
    return False


def read_verbatim(path: Path) -> str:
    """A file's text with its line endings intact.

    ``newline=""`` matters on the READ as much as the write: without it Python turns CRLF into
    LF before any caller can look, so a CRLF file's endings are undetectable and it gets
    silently rewritten end to end. Spelled with ``open`` rather than ``Path.read_text(newline=)``
    because that keyword landed in 3.13 and CI runs older.
    """
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def write_verbatim(path: Path, text: str) -> None:
    """Write ``text`` with its line endings exactly as given — see read_verbatim."""
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


# Mirrors NarrativeBookFields.STRUCTURAL_KEYS / UNUSED_KEY on the client: ids and layout
# hints validate-locale.py requires to match the reference byte-for-byte, plus retired
# prose no player ever sees. The editor never offers these, so an approved unit naming one
# did not come from the editor.
BOOK_STRUCTURAL_KEYS = frozenset({"id", "ref", "page", "_translator_note"})
BOOK_UNUSED_KEY = "unused"


def set_book_field(book: dict | list, field: str, value: str) -> bool:
    """Set one dotted-path string leaf of a parsed narrative book, in place.

    ``field`` is the path the in-game editor submits (``title``, ``variants.0``,
    ``letters.2.variants.1``, ``0.narration``) — integer segments index arrays, everything
    else is an object key. Returns False, changing nothing, unless the path already resolves
    to a string leaf: this only ever REPLACES existing prose. Creating a path would invent
    structure the English book does not have, and validate-locale.py would reject it.
    """
    if not field:
        return False
    segments = field.split(".")
    node = book
    for segment in segments[:-1]:
        node = _book_child(node, segment)
        if node is None:
            return False
    last = segments[-1]
    if isinstance(node, list):
        if not last.isdigit():
            return False
        index = int(last)
        if index >= len(node) or not isinstance(node[index], str):
            return False
        node[index] = value
        return True
    if isinstance(node, dict):
        if last in BOOK_STRUCTURAL_KEYS or last == BOOK_UNUSED_KEY:
            return False
        if not isinstance(node.get(last), str):
            return False
        node[last] = value
        return True
    return False


def _book_child(node, segment: str):
    """One step down a book's tree, or None when the segment does not resolve."""
    if isinstance(node, list):
        return node[int(segment)] if segment.isdigit() and int(segment) < len(node) else None
    if isinstance(node, dict):
        if segment in BOOK_STRUCTURAL_KEYS or segment == BOOK_UNUSED_KEY:
            return None
        return node.get(segment)
    return None


def set_book_field_text(text: str, field: str, value: str) -> str | None:
    """One book's raw text with ``field`` replaced, or None when it cannot be done safely.

    Books are edited at the text level for the same reason lang files are: 36 of them group
    array entries with blank lines and 34 are CRLF, both of which a json.dumps round trip
    would silently rewrite end to end. So this swaps the ENCODED old string for the encoded
    new one.

    When that encoding occurs more than once the target is ambiguous by inspection, and this
    used to give up — which cost real work: 11 approved Russian death_lore translations were
    refused on 2026-08-30 because prose like "Всё это хорошие вопросы" is deliberately reused
    across four entries. Ambiguity is not the same as danger, though. Each occurrence in turn is
    replaced and the result parsed, and the one whose tree matches what the edit was supposed to
    produce is the right one. Where two identical strings sit in different fields, only the
    intended one yields the expected tree; where they sit in the SAME field (an entry repeated
    verbatim), either replacement produces it, and they are interchangeable by construction.

    Every candidate is verified this way, so a replacement that landed anywhere unintended is
    never returned — the check that made the single-occurrence case safe is what makes the
    ambiguous case safe too.
    """
    try:
        book = json.loads(text)
    except json.JSONDecodeError:
        return None
    old = book_field_value(book, field)
    if old is None:
        return None
    if old == value:
        return text  # already the approved text — nothing to write, and not an error
    if not set_book_field(book, field, value):
        return None
    encoded_old = json.dumps(old, ensure_ascii=False)
    encoded_new = json.dumps(value, ensure_ascii=False)
    at = text.find(encoded_old)
    while at != -1:
        edited = text[:at] + encoded_new + text[at + len(encoded_old):]
        try:
            if json.loads(edited) == book:
                return edited
        except json.JSONDecodeError:
            pass  # this occurrence was inside some other string; keep looking
        at = text.find(encoded_old, at + 1)
    return None


def book_field_value(book: dict | list, field: str) -> str | None:
    """The string at ``field``'s dotted path, or None when it is not an editable string leaf."""
    if not field:
        return None
    node = book
    for segment in field.split("."):
        node = _book_child(node, segment)
        if node is None:
            return None
    return node if isinstance(node, str) else None


def book_string_fields(node, path: str = "", depth: int = 0) -> list[str]:
    """Every editable dotted field path in a parsed book, in document order.

    The Python side of NarrativeBookFields.flatten — same walk, same exclusions, same depth
    guard. Used to tell a book whose every field an import replaced (the translator becomes
    its author) from one where it fixed a line or two (the author stands, they reviewed it).
    """
    if depth > 8:
        return []
    out: list[str] = []
    if isinstance(node, dict):
        for key, child in node.items():
            if key in BOOK_STRUCTURAL_KEYS or key == BOOK_UNUSED_KEY:
                continue
            out += book_string_fields(child, f"{path}.{key}" if path else key, depth + 1)
    elif isinstance(node, list):
        for i, child in enumerate(node):
            out += book_string_fields(child, f"{path}.{i}" if path else str(i), depth + 1)
    elif isinstance(node, str) and path:
        out.append(path)
    return out
