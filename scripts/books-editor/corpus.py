#!/usr/bin/env python3
"""Discovery, validation and format-preserving I/O for the three book corpora.

The editor writes files a human wrote by hand, so the writer's first duty is to leave everything it
did not touch byte-identical. The corpus is not uniform: books carry fields the schema never mentions
(``_translator_note``, ``unused``, ``deferred``, letter ``notes``), ``weight`` is an int in most files
and a float in five, and only 44 of the 56 files end in a newline. Rather than assume a house style,
:func:`detect_style` probes each file on load — it re-serialises the parsed data with every plausible
combination of indent / ASCII-escaping / trailing newline and keeps the one that reproduces the file
exactly. A save then re-uses that style, so an untouched book round-trips to a zero-byte diff and an
edited one changes only the strings the author actually edited.

Corpora (all under ``src/main/resources/data/dungeontrain/narratives``):

* ``starting_books/`` — welcome / lightning-strike books, partitioned into lifecycle subfolders that
  ARE the routing (see that folder's CLAUDE.md). Paginated explicitly: ``\\n\\n`` is a page break.
* ``random_books/``   — chest-loot books. Flow-paginated.
* ``stories/``        — narrative letters: one file per story, ``letters[]`` each with variants.
  Flow-paginated; title comes from ``story`` (unless "Untitled" → the letter's ``label``) and author
  from ``character``.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

# corpus key -> (directory under narratives/, pagination mode, shape)
CORPORA = {
    "starting": {"dir": "starting_books", "mode": "explicit", "shape": "book"},
    "random": {"dir": "random_books", "mode": "flow", "shape": "book"},
    "stories": {"dir": "stories", "mode": "flow", "shape": "story"},
}

#: Lifecycle / dimension context folders under ``starting_books``. Order mirrors StartingBookContext,
#: DEFAULT first (which is also the "wins on basename collision" rule in StartingBookRegistry).
STARTING_CONTEXTS = [
    "", "new_world", "joined_world", "respawn", "nether", "end",
    "cursed", "cursed_fulfilled", "cursed_defied",
    "loved", "loved_betrayed", "loved_turned",
]

#: Directories inside a corpus that hold drafts / retired content the mod does not load.
IGNORED_DIRS = {"originals", "unused"}

#: ResourceLocation path charset — StartingBookRegistry warns and SKIPS anything else.
FILENAME_RE = re.compile(r"^[a-z0-9._-]+$")

MAX_TITLE_CHARS = 32  # BookFactory.MAX_TITLE_CHARS (silently clamped in game)

LOCALIZATIONS_DIRNAME = "narrative_localizations"


class CorpusError(ValueError):
    """A request the editor must refuse — bad path, failed validation, stale write."""


# --- locating the repo -------------------------------------------------------

def repo_root(start: Path | None = None) -> Path:
    """Nearest ancestor holding ``gradle.properties`` — the mod repo (or worktree) root."""
    here = (start or Path(__file__)).resolve()
    for candidate in [here, *here.parents]:
        if (candidate / "gradle.properties").is_file():
            return candidate
    raise CorpusError("could not locate the mod repo root (no gradle.properties above %s)" % here)


def narratives_dir(root: Path) -> Path:
    return root / "src/main/resources/data/dungeontrain/narratives"


def localizations_dir(root: Path) -> Path:
    return root / "src/main/resources/data/dungeontrain" / LOCALIZATIONS_DIRNAME


# --- paths -------------------------------------------------------------------

def resolve(root: Path, rel: str) -> Path:
    """Resolve a corpus-relative path, refusing anything outside a loadable corpus directory.

    ``rel`` is always relative to ``narratives/`` and always starts with a corpus directory, e.g.
    ``starting_books/respawn/journey.json``. Traversal, absolute paths, non-JSON names and the
    drafts folders are all rejected here rather than deeper down.
    """
    if not rel or rel.startswith("/") or "\\" in rel:
        raise CorpusError(f"bad path: {rel!r}")
    base = narratives_dir(root).resolve()
    target = (base / rel).resolve()
    if target != base and base not in target.parents:
        raise CorpusError(f"path escapes the corpus: {rel!r}")
    parts = target.relative_to(base).parts
    if not parts or parts[0] not in {c["dir"] for c in CORPORA.values()}:
        raise CorpusError(f"not a book corpus: {rel!r}")
    if IGNORED_DIRS & set(parts):
        raise CorpusError(f"{rel!r} is in a drafts folder the mod does not load")
    if target.suffix != ".json":
        raise CorpusError(f"not a JSON file: {rel!r}")
    return target


def corpus_of(rel: str) -> str:
    head = rel.split("/", 1)[0]
    for key, spec in CORPORA.items():
        if spec["dir"] == head:
            return key
    raise CorpusError(f"not a book corpus: {rel!r}")


def context_of(rel: str) -> str:
    """Lifecycle context folder for a starting book — '' (DEFAULT) when it sits at the root."""
    parts = rel.split("/")
    return parts[1] if len(parts) > 2 else ""


# --- style-preserving JSON I/O ----------------------------------------------

_INDENTS = (2, 4)
_STYLE_CANDIDATES = [
    {"indent": indent, "ensure_ascii": ascii_, "trailing_newline": nl, "blank_between_items": blank}
    for indent in _INDENTS
    for ascii_ in (False, True)
    for nl in (True, False)
    for blank in (False, True)
]

#: Style used for a brand-new file, and the fallback when a file's own style can't be reproduced.
DEFAULT_STYLE = {"indent": 2, "ensure_ascii": False, "trailing_newline": True,
                 "blank_between_items": False, "exact": True}


def _blank_between_items(text: str) -> str:
    """Re-insert the blank line some authors leave between variant strings (brennan_intro.json)."""
    out, inside, indent, first = [], False, 0, True
    for line in text.split("\n"):
        stripped = line.strip()
        if not inside and stripped.startswith('"variants": ['):
            inside, first, indent = True, True, len(line) - len(line.lstrip())
            out.append(line)
            continue
        if inside:
            depth = len(line) - len(line.lstrip())
            if stripped in ("]", "],") and depth == indent:
                inside = False
                out.append(line)
                continue
            if depth == indent + 2:
                if not first and stripped.startswith('"'):
                    out.append("")
                first = False
        out.append(line)
    return "\n".join(out)


def render(data, style: dict) -> str:
    text = json.dumps(data, indent=style.get("indent", 2), ensure_ascii=style.get("ensure_ascii", False))
    if style.get("blank_between_items"):
        text = _blank_between_items(text)
    return text + "\n" if style.get("trailing_newline", True) else text


def detect_style(text: str, data) -> dict:
    """Find the serialisation style that reproduces ``text`` exactly.

    ``exact`` is False when no candidate matches (a hand-formatted file). The editor surfaces that as
    a badge: saving such a file reformats it, so the author knows the diff will be wider than their
    edit before they commit to it.
    """
    for candidate in _STYLE_CANDIDATES:
        if render(data, candidate) == text:
            return {**candidate, "exact": True}
    return {**DEFAULT_STYLE, "exact": False}


def read_book(root: Path, rel: str) -> dict:
    path = resolve(root, rel)
    if not path.is_file():
        raise CorpusError(f"no such book: {rel}")
    text = path.read_text(encoding="utf-8")
    data = json.loads(text)
    return {
        "path": rel,
        "corpus": corpus_of(rel),
        "mode": CORPORA[corpus_of(rel)]["mode"],
        "context": context_of(rel) if corpus_of(rel) == "starting" else None,
        "data": data,
        "style": detect_style(text, data),
        "mtime": mtime_of(path),
        "locales": locales_for(root, rel),
        "warnings": warnings_for(rel, data, path.stem),
    }


def mtime_of(path: Path) -> str:
    """Modification stamp as a STRING — nanoseconds exceed JavaScript's safe integer range, so a
    numeric round-trip through the browser silently rounds it and every save looks like a conflict."""
    return str(path.stat().st_mtime_ns)


def write_book(root: Path, rel: str, data, style: dict, expected_mtime=None) -> dict:
    """Validate and write, refusing a write whose file changed underneath the editor."""
    path = resolve(root, rel)
    validate(rel, data, filename=path.stem)
    if expected_mtime is not None and path.is_file():
        actual = mtime_of(path)
        if actual != str(expected_mtime):
            raise CorpusError(
                f"{rel} changed on disk since it was opened — reload before saving (stale edit)")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render(data, style), encoding="utf-8")
    return {"path": rel, "mtime": mtime_of(path)}


# --- validation --------------------------------------------------------------

def validate(rel: str, data, filename: str) -> None:
    """Reject anything the mod's loaders would skip-and-warn on, before it reaches disk."""
    if not isinstance(data, dict):
        raise CorpusError("a book file must be a JSON object")
    if not FILENAME_RE.match(f"{filename}.json"):
        raise CorpusError(
            f"'{filename}.json' is not a valid ResourceLocation path — use [a-z0-9._-] only, "
            "or the registry will skip the book")
    if corpus_of(rel) == "stories":
        _validate_story(data)
    else:
        _validate_book(data)


def warnings_for(rel: str, data, filename: str) -> list:
    """Non-fatal notes shown beside the book. These never block a save.

    The id/filename mismatch is the live example: `lighting.json` ships with `"id": "lightning"`, and
    the mod is unbothered — every registry keys on the FILENAME (RandomBookFile.basename()) — but it
    is still worth flagging to whoever opens the file next.
    """
    notes = []
    book_id = data.get("id")
    if book_id is not None and book_id != filename:
        notes.append(f"id '{book_id}' does not match the filename '{filename}' "
                     "(harmless — the mod keys on the filename — but probably a typo)")
    title = data.get("title")
    if isinstance(title, str) and len(title) > MAX_TITLE_CHARS:
        notes.append(f"title is {len(title)} chars — the game clamps it to {MAX_TITLE_CHARS}")
    return notes


def _validate_variants(variants, where: str) -> None:
    if not isinstance(variants, list) or not variants:
        raise CorpusError(f"{where}: variants[] is required and must hold at least one body")
    for i, body in enumerate(variants):
        if not isinstance(body, str) or not body.strip():
            raise CorpusError(f"{where}: variant {i + 1} is empty")


def _validate_book(data) -> None:
    _validate_variants(data.get("variants"), "book")
    weight = data.get("weight", 1)
    if not isinstance(weight, (int, float)) or isinstance(weight, bool) or weight < 0:
        raise CorpusError(f"weight must be a number ≥ 0 (got {weight!r})")
    generation = data.get("generation", 0)
    if not isinstance(generation, int) or isinstance(generation, bool) or not 0 <= generation <= 3:
        raise CorpusError(f"generation must be an integer 0–3 (got {generation!r})")
    for field in ("title", "author"):
        value = data.get(field)
        if value is not None and not isinstance(value, str):
            raise CorpusError(f"{field} must be a string")


def _validate_story(data) -> None:
    letters = data.get("letters")
    if not isinstance(letters, list) or not letters:
        raise CorpusError("a story needs at least one letter")
    seen = set()
    for letter in letters:
        if not isinstance(letter, dict):
            raise CorpusError("each letter must be a JSON object")
        index = letter.get("index")
        if not isinstance(index, int) or isinstance(index, bool) or index < 1:
            raise CorpusError(f"letter index must be a positive integer (got {index!r})")
        if index in seen:
            raise CorpusError(f"duplicate letter index {index} — reading progress keys on it")
        seen.add(index)
        _validate_variants(letter.get("variants"), f"letter {index}")


# --- listing -----------------------------------------------------------------

def _display(data, corpus: str) -> dict:
    """Title / author as the game derives them, so the list reads like the bookshelf does."""
    if corpus == "stories":
        story = data.get("story")
        title = story if story and story != "Untitled" else None
        character = data.get("character")
        return {"title": title, "author": character or "Anonymous",
                "count": sum(len(l.get("variants") or []) for l in data.get("letters") or []),
                "letters": len(data.get("letters") or [])}
    title = data.get("title")
    author = data.get("author")
    return {"title": title if title and title != "Untitled" else None,
            "author": author or "Anonymous",
            "count": len(data.get("variants") or []), "letters": None}


def locales_for(root: Path, rel: str) -> list:
    """Locales carrying a translation of this book — editing the English prose leaves them stale."""
    base = localizations_dir(root)
    if not base.is_dir():
        return []
    return sorted(d.name for d in base.iterdir() if d.is_dir() and (d / rel).is_file())


def read_localized(root: Path, rel: str, locale: str) -> dict:
    """The translated mirror of a book, for the read-only locale view. Never written by the editor."""
    if not re.fullmatch(r"[a-z]{2,3}_[a-z]{2,3}", locale or ""):
        raise CorpusError(f"bad locale: {locale!r}")
    resolve(root, rel)  # same confinement rules as the English source
    path = localizations_dir(root) / locale / rel
    if not path.is_file():
        raise CorpusError(f"no {locale} translation of {rel}")
    return json.loads(path.read_text(encoding="utf-8"))


def list_books(root: Path) -> list:
    """Every loadable book across the three corpora, sorted for a stable sidebar."""
    out = []
    base = narratives_dir(root)
    for corpus, spec in CORPORA.items():
        corpus_dir = base / spec["dir"]
        if not corpus_dir.is_dir():
            continue
        for path in sorted(corpus_dir.rglob("*.json")):
            rel = path.relative_to(base).as_posix()
            if IGNORED_DIRS & set(rel.split("/")):
                continue
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                out.append({"path": rel, "corpus": corpus, "mode": spec["mode"],
                            "id": path.stem, "error": str(exc)})
                continue
            entry = {
                "path": rel,
                "corpus": corpus,
                "mode": spec["mode"],
                "shape": spec["shape"],
                "id": path.stem,
                "context": context_of(rel) if corpus == "starting" else None,
                "locales": len(locales_for(root, rel)),
                "valid": _is_valid(rel, data, path.stem),
                "warnings": warnings_for(rel, data, path.stem),
            }
            entry.update(_display(data, corpus))
            out.append(entry)
    return out


def _is_valid(rel: str, data, filename: str) -> bool:
    try:
        validate(rel, data, filename)
        return True
    except CorpusError:
        return False
