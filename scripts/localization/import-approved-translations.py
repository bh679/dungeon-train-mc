#!/usr/bin/env python3
"""Import the relay's approved player translations back into the repo.

The return leg for work approved on the explorer's ``#/translations`` page, and the counterpart
to ``apply-review-csv.py`` (which is the return leg for a CSV handed to a translator directly).

An approval on the relay replaces the jar's text for every player in that language immediately,
but it changes nothing here — so the shipped translation, the provenance sidecars and the
generated Credits page all stay as if the work had never happened, release after release. This
pulls that work back: the approved text lands in the lang/book files, the translator is stamped
into provenance, and ``stamp-provenance.py`` turns that into the shipped
``translation_contributors.json`` the Credits page reads — names AND per-language percentages.
The relay's own override then just agrees with the jar instead of patching it.

Usage:
  export DUNGEONTRAIN_RELAY_ADMIN_BASE='https://…/<admin cap>'   # never committed
  python3 scripts/localization/import-approved-translations.py --dry-run
  python3 scripts/localization/import-approved-translations.py
  python3 scripts/localization/import-approved-translations.py --register-new   # new translators

``--from-file`` reads the same payload from disk instead of the relay, which is how the tests
drive this and how an offline import of a saved queue works.

Three things it deliberately will NOT do:

* **Credit a name it has not accounted for.** ``localization/authors.json`` is what keeps the
  AI-vs-human counts honest. An unknown translator fails the run until someone puts them in the
  registry, unless ``--register-new`` is passed — and then they are added only once their work
  has actually landed, listed in the run's output, and written to ``--new-authors-out`` so the
  reviewer of the resulting diff is told exactly which names are new.
* **Import a stale approval.** Every submission stores the English it was translated from. When
  ``en_us`` has changed since, the approved text answers a question no longer being asked; it is
  reported and skipped rather than shipped.
* **Invent a line to write.** An approved unit whose key the locale file does not carry has
  nowhere to land. That is not always the same failure, though, and the difference decides
  whether the run may continue — see ``report`` and ``classify_absent_key``.
* **Write a value that will not render.** ``Component.translatable`` runs Minecraft's format
  parser, so an approved translation that drops a ``%s``, invents a ``%2$s``, or contains a bare
  ``%`` is broken text however well meant. It is deferred with the reason, not written — see
  ``lang_format.mismatch``.
* **Reformat anything.** Lang files carry blank-line grouping and zh_cn is CRLF; 36 books group
  their variants the same way. Every write is a text-level edit of one value (see
  ``provenance_io.set_lang_value`` / ``set_book_field_text``), so the diff is the translation and
  nothing else.
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import lang_format
import plural_forms
import provenance_io as pio

HERE = Path(__file__).resolve().parent
STAMP = HERE / "stamp-provenance.py"
STAMP_NARRATIVE = HERE / "stamp-narrative-provenance.py"

#: Holds the admin capability in its path, so it is a secret: env only, and scrubbed from every
#: message this script prints (see redact).
BASE_ENV = "DUNGEONTRAIN_RELAY_ADMIN_BASE"

#: Approvals whose submitter left the "credit me" box unticked arrive with an empty translator.
#: Their text is still a person's work, so it is attributed here rather than left to read as
#: machine translation — under one registered name that stands in for all of them.
ANONYMOUS = "Anonymous contributor"

#: Rows per request. The relay clamps its admin listing to 1000, and pages older rows through the
#: `before` keyset cursor it returns as `oldestId` — see fetch_locale.
PAGE_LIMIT = 1000

#: Hard stop on the paging loop. At PAGE_LIMIT rows a page this is far more than any real queue,
#: and it means a relay that kept answering `hasMore` with a cursor that never advanced could not
#: spin this script forever.
MAX_PAGES = 50

REQUEST_TIMEOUT = 30


# ---- fetching ----------------------------------------------------------------

def redact(text: str, base: str) -> str:
    """``text`` with the admin base URL replaced — urllib puts the failing URL in its errors."""
    return str(text).replace(base, "<relay>") if base else str(text)


def units_of(payload) -> list[dict]:
    """The unit rows out of either the endpoint's envelope or a bare list of rows."""
    if isinstance(payload, dict):
        payload = payload.get("units", [])
    if not isinstance(payload, list):
        raise ValueError("expected a JSON array of units, or an object with a 'units' array")
    return [row for row in payload if isinstance(row, dict)]


def fetch_locale(base: str, cap: str, locale: str) -> list[dict]:
    """
    Every approved unit for one locale, newest first (the relay's admin listing order).

    Pages through the whole queue rather than taking the first response. The relay clamps a single
    listing to ``PAGE_LIMIT`` rows and returns ``hasMore`` plus ``oldestId``, the keyset cursor to
    pass back as ``before`` for the next (older) page — the same cursor the explorer's review page
    walks backwards on.

    This matters more than it looks: ru_ru alone is past the ceiling, and because the listing is
    ordered newest-first, a single-request import would take the same first page every run. Rows
    older than the ceiling would never be imported at all, on any run, ever.
    """
    rows: list[dict] = []
    seen_ids: set = set()
    before = None

    for _ in range(MAX_PAGES):
        params = {"flag": "approved", "cap": cap, "locale": locale, "limit": PAGE_LIMIT}
        if before is not None:
            params["before"] = before
        url = f"{base.rstrip('/')}/translations?{urllib.parse.urlencode(params)}"
        try:
            with urllib.request.urlopen(url, timeout=REQUEST_TIMEOUT) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, ValueError, json.JSONDecodeError) as exc:
            sys.exit(f"error: could not read the approved queue for {locale} — {redact(exc, base)}")

        page = units_of(payload)
        # Ignore any row already collected, so a relay that re-serves a boundary row cannot
        # duplicate it into the import.
        fresh = [r for r in page if r.get("id") not in seen_ids]
        for r in fresh:
            if r.get("id") is not None:
                seen_ids.add(r["id"])
        rows += fresh

        # A bare list carries no envelope, so there is nothing to page with.
        if not isinstance(payload, dict):
            break
        cursor = payload.get("oldestId")
        if not payload.get("hasMore") or cursor is None or cursor == before or not fresh:
            break
        before = cursor
    else:
        print(f"  WARNING: {locale} hit the {MAX_PAGES}-page ceiling — this import is INCOMPLETE "
              "for that locale.", file=sys.stderr)

    return rows


def load_rows(args) -> list[dict]:
    """Approved rows from the relay, or from ``--from-file``, filtered to ``--locale``."""
    wanted = set(args.locale or [])
    if args.from_file:
        try:
            rows = units_of(json.loads(args.from_file.read_text(encoding="utf-8")))
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            sys.exit(f"error: could not read {args.from_file} — {exc}")
        rows = [r for r in rows if (r.get("flag") or "approved") == "approved"]
    else:
        base = args.relay_base or os.environ.get(BASE_ENV, "")
        if not base:
            sys.exit(f"error: set {BASE_ENV} to the relay's admin base URL (it carries the admin "
                     "capability, so it belongs in your environment, never in the repo), or pass "
                     "--from-file to import a saved queue.")
        locales = sorted(wanted) if wanted else pio.locales(pio.DEFAULT_LANG_DIR)
        rows = []
        for locale in locales:
            rows += fetch_locale(base, args.cap, locale)
    return [r for r in rows if not wanted or r.get("locale") in wanted]


# ---- the author registry -----------------------------------------------------

def translator_of(row: dict) -> str:
    """The credited name for a row, with an unticked "credit me" box folded to ANONYMOUS."""
    return (row.get("translator") or "").strip() or ANONYMOUS


def register_authors(path: Path, names: list[str]) -> None:
    """Append ``names`` to authors.json as humans, preserving the file's hand-kept layout.

    A text-level append for the same reason the lang edits are: the registry mixes bare-string
    and object entries on one line each, which a json.dump round trip would not reproduce.
    """
    raw = path.read_text(encoding="utf-8")
    head = raw.rstrip()
    if not head.endswith("}"):
        raise ValueError(f"{path}: expected a JSON object ending in '}}'")
    head = head[:-1].rstrip()
    if not head.endswith("{"):
        head += ","
    added = ",\n".join(f'  {json.dumps(name, ensure_ascii=False)}: "human"' for name in names)
    path.write_text(f"{head}\n{added}\n}}\n", encoding="utf-8")
    pio.load_authors(path)  # a malformed append must fail here, not in CI


def check_registered(names: set[str], path: Path, register_new: bool) -> list[str]:
    """The refusals that must happen before a single byte is written; returns the unknown names.

    A name registered as ``"ai"`` always stops the run, ``--register-new`` or not: whatever the
    relay was told, a machine did not translate this, and crediting it would corrupt the very
    counts the registry exists to keep honest.

    An unknown name is a different thing — it is the ordinary case of somebody translating for
    the first time. Without ``--register-new`` it still fails the run, having changed nothing.
    With it, the names are handed back for register_landed to add after the import.
    """
    authors = pio.load_authors(path)
    machines = sorted(n for n in names if authors.get(n) == "ai")
    if machines:
        sys.exit("error: these approved submissions are credited to names registered as AI in "
                 f"{path.name}: {', '.join(machines)}. A machine cannot be a translator — fix "
                 "the registry or the submission before importing.")
    unknown = sorted(n for n in names if n not in authors)
    if unknown and not register_new:
        listed = "\n".join(f'  "{name}": "human",' for name in unknown)
        sys.exit(f"error: {len(unknown)} translator name(s) are not in {path.name}:\n{listed}\n"
                 "Nothing was changed. Add them (with a profile URL where they gave one — see "
                 "the object form in that file) and re-run, or pass --register-new to add them "
                 "as plain humans.")
    return unknown


def register_landed(unknown: list[str], landed: set[str], path: Path,
                    dry_run: bool) -> list[str]:
    """Register the new translators whose work this run actually imported. Returns their names.

    Registration deliberately waits for the import pass. A name is read off the payload long
    before anyone knows whether anything of theirs survives: an approval can be dropped as stale
    because the English moved on, or refused as unapplyable. Registering on sight would put
    people in the credit ledger with not one stamped line behind them, so only the names that
    landed something are added — the rest are named as skipped and can arrive on a later run.
    """
    if not unknown:
        return []
    new = [name for name in unknown if name in landed]
    idle = [name for name in unknown if name not in landed]
    if idle:
        print(f"  not registered — nothing of theirs could be imported: {', '.join(idle)}")
    if not new:
        return []
    if dry_run:
        print(f"  would register in {path.name}: {', '.join(new)}")
        return new
    register_authors(path, new)
    print(f"  registered in {path.name}: {', '.join(new)}")
    return new


# ---- lang units --------------------------------------------------------------

def classify_absent_key(locale: str, name: str, key: str, english: dict) -> str | None:
    """Why `key` is not in `locale`'s lang file — or None when there is no innocent explanation.

    The relay hands us keys the in-game editor offered players, and the editor offers the raw
    English key set (``TranslationCatalog.collectLangUnits``). Two entirely different things
    therefore arrive looking identical, and only one of them is anybody's mistake:

    * **drift** — English has the key and this locale has fallen behind. A real translation of a
      real string, waiting on a line to edit. It imports itself once the locale catches up.
    * **a phantom plural form** — ``<base>.other`` for a language whose grammar never selects
      ``other``. Russian was shown 27 of these, translated them, and had them approved; they can
      never be written, because ``validate-locale.py`` would then reject the file for carrying a
      key the language cannot use. The work was real, the question was not.

    Anything else — a key English does not have either — means the relay and the repo disagree
    about what exists, and that is worth stopping for.
    """
    if plural_forms.wrong_plural_form(key, locale, english):
        return (f"{locale} [{name}] {key}: not a plural form {locale} uses — the editor should "
                "not have offered it; retire this unit at the relay")
    if key in english:
        return (f"{locale} [{name}] {key}: {locale}.json has no line for this key yet (locale "
                "drift) — it imports once that locale carries the key")
    return None


def import_lang(rows: list[dict], ns_dirs: dict, dry_run: bool, problems: list[str],
                deferred: list[str]) -> tuple[dict, dict, list[str]]:
    """Apply approved lang units.

    Returns ``(revised, confirmed, stale)`` keyed by ``(namespace, locale, translator)``:
    ``revised`` are the keys whose text this changed — the translator becomes author AND
    reviewer, the documented "fixed" case — and ``confirmed`` are keys whose text already
    matched, where a person has demonstrably read the line but did not write it, so the
    original author stands and only the reviewer is stamped.
    """
    revised: dict[tuple, list[str]] = {}
    confirmed: dict[tuple, list[str]] = {}
    stale: list[str] = []
    lang_cache: dict[Path, dict] = {}

    def lang_of(path: Path) -> dict | None:
        if path not in lang_cache:
            lang_cache[path] = pio.load_lang(path) if path.is_file() else None
        return lang_cache[path]

    for row in rows:
        locale, key, value = row["locale"], row["unitId"], row["value"]
        name = row.get("namespace") or "dungeontrain"
        ns = ns_dirs.get(name)
        if ns is None:
            problems.append(f"{locale} [{name}] {key}: unknown namespace")
            continue
        lang = lang_of(ns.lang_dir / f"{locale}.json")
        if lang is None:
            problems.append(f"{locale} [{name}] {key}: no {locale}.json in {name}'s lang dir")
            continue
        english = lang_of(ns.lang_dir / "en_us.json") or {}
        if key not in lang:
            # Classified before the stale check on purpose: a key with no line to edit cannot be
            # imported whatever its English says, and "we are missing this key" is the more
            # useful thing to be told about it.
            excuse = classify_absent_key(locale, name, key, english)
            if excuse is None:
                whence = ("and not in en_us.json either" if english else
                          f"and {name} ships no en_us.json here to check it against")
                problems.append(f"{locale} [{name}] {key}: not a key in {locale}.json, {whence}")
            else:
                deferred.append(excuse)
            continue
        source = row.get("source") or ""
        if source and key in english and english[key] != source:
            stale.append(f"{locale} [{name}] {key}: en_us changed since this was translated")
            continue
        # Checked BEFORE the already-matches branch on purpose: if a malformed value is what the
        # file already holds, the shipped file is malformed too, and that is worth being told.
        broken = lang_format.mismatch(value, english.get(key, "")) if key in english else None
        if broken:
            deferred.append(f"{locale} [{name}] {key}: {broken} — the text needs fixing at the "
                            "relay before it can be imported")
            continue
        group = (name, locale, translator_of(row))
        if lang[key] == value:
            confirmed.setdefault(group, []).append(key)
            continue
        if not dry_run:
            if not pio.set_lang_value(ns.lang_dir / f"{locale}.json", key, value):
                problems.append(f"{locale} [{name}] {key}: value line not found in {locale}.json")
                continue
            lang[key] = value
        revised.setdefault(group, []).append(key)
    return revised, confirmed, stale


# ---- book units --------------------------------------------------------------

def targets_structure(field: str) -> bool:
    """True when a book unit's dotted path names a field the loaders read rather than a reader."""
    return any(seg in pio.BOOK_STRUCTURAL_KEYS or seg == pio.BOOK_UNUSED_KEY
               for seg in field.split("."))


def import_books(rows: list[dict], narrative_dir: Path, dry_run: bool, problems: list[str],
                 deferred: list[str]) -> tuple[dict, dict]:
    """Apply approved book units, one file open per book however many fields it has.

    Returns ``(rewritten, revised)`` keyed by ``(locale, translator)``: narrative provenance is
    per BOOK, not per field, so a translator who replaced every editable field of a book becomes
    its author, and one who fixed a line or two is recorded as having reviewed it — taking the
    authorship of prose they mostly did not write would be a worse record than none.
    """
    rewritten: dict[tuple, list[str]] = {}
    revised: dict[tuple, list[str]] = {}
    by_book: dict[tuple, list[dict]] = {}
    for row in rows:
        book_path, sep, field = row["unitId"].partition("#")
        if not sep or not field:
            problems.append(f"{row['locale']} {row['unitId']}: not a <book>#<field> unit id")
            continue
        by_book.setdefault((row["locale"], book_path), []).append(dict(row, field=field))

    for (locale, book_path), book_rows in sorted(by_book.items()):
        path = narrative_dir / locale / f"{book_path}.json"
        if not path.is_file():
            problems.append(f"{locale} {book_path}: no such book for this locale")
            continue
        original = pio.read_verbatim(path)
        text = original
        applied: dict[str, set[str]] = {}
        for row in book_rows:
            edited = pio.set_book_field_text(text, row["field"], row["value"])
            if edited is None:
                if targets_structure(row["field"]):
                    # `id`, `ref`, `page`, `_translator_note` are load-bearing: the loaders match
                    # on them and the translator note carries rules to the next translator. A unit
                    # aimed at one is not a translation that failed to apply, it is a unit that
                    # should never have existed — the editor never offers these. Stop.
                    problems.append(f"{locale} {book_path}#{row['field']}: targets a structural "
                                    "field, which is never translatable")
                    continue
                # Deferred, not fatal: the translation is fine and the book is fine, the two just
                # cannot be married by a text-level edit. Somebody has to open the file; nobody
                # has to abandon the other 979 units.
                deferred.append(f"{locale} {book_path}#{row['field']}: could not be applied "
                                "safely (field missing or not prose) — edit this one by hand")
                continue
            text = edited
            applied.setdefault(translator_of(row), set()).add(row["field"])
        if not applied:
            continue
        if not dry_run and text != original:
            pio.write_verbatim(path, text)
        every_field = set(pio.book_string_fields(json.loads(text)))
        for name, fields in applied.items():
            target = rewritten if every_field and fields >= every_field else revised
            target.setdefault((locale, name), []).append(book_path)
    return rewritten, revised


# ---- stamping ----------------------------------------------------------------

def run(cmd: list[str], dry_run: bool) -> None:
    """One stamp call. Printed either way, so a dry run shows exactly what a real one would do."""
    shown = " ".join(str(c) for c in cmd[1:])
    if dry_run:
        print(f"  would run: {shown}")
        return
    print(f"  {shown}")
    subprocess.run([str(c) for c in cmd], check=True)


def lang_dirs(args) -> list[str]:
    """The dir overrides to forward to stamp-provenance.py, empty on a normal repo run.

    ``--lang-dir`` is stamp-provenance.py's own single-namespace escape hatch (what its tests
    use). Passing it through means a redirected import stamps the redirected workspace instead
    of quietly writing into the real assets tree.
    """
    if not args.lang_dir:
        return []
    out = ["--lang-dir", str(args.lang_dir), "--provenance-dir", str(args.provenance_dir)]
    for flag, value in (("--credits-dir", args.credits_dir),
                        ("--contributors-file", args.contributors_file),
                        ("--manifest-dir", args.manifest_dir)):
        if value:
            out += [flag, str(value)]
    return out


def stamp_lang(revised: dict, confirmed: dict, args) -> None:
    for group, keys, author in (
        *[(g, k, True) for g, k in sorted(revised.items())],
        *[(g, k, False) for g, k in sorted(confirmed.items())],
    ):
        namespace, locale, name = group
        cmd = [sys.executable, str(STAMP), "--authors-file", str(args.authors_file)]
        cmd += lang_dirs(args) or ["--namespace", namespace]
        cmd += ["--locale", locale]
        if author:
            cmd += ["--author", name]
        run(cmd + ["--reviewer", name, "--keys", *sorted(keys)], args.dry_run)


def stamp_books(rewritten: dict, revised: dict, args) -> None:
    for group, books, author in (
        *[(g, b, True) for g, b in sorted(rewritten.items())],
        *[(g, b, False) for g, b in sorted(revised.items())],
    ):
        locale, name = group
        cmd = [sys.executable, str(STAMP_NARRATIVE), "--authors-file", str(args.authors_file),
               "--narrative-dir", str(args.narrative_dir)]
        if args.narrative_provenance_dir:
            cmd += ["--provenance-dir", str(args.narrative_provenance_dir)]
        if args.manifest_dir:
            cmd += ["--manifest-dir", str(args.manifest_dir)]
        cmd += ["--locale", locale]
        if author:
            cmd += ["--author", name]
        run(cmd + ["--reviewer", name, "--files", *sorted(books)], args.dry_run)


# ---- entry point -------------------------------------------------------------

def valid(row: dict, problems: list[str]) -> bool:
    """A row this script can act on at all — the relay validates on the way in, not on the way out."""
    for field in ("locale", "unitId", "value"):
        if not isinstance(row.get(field), str) or not row[field]:
            problems.append(f"unit {row.get('id', '?')}: missing or empty {field}")
            return False
    if row.get("unitType") not in ("lang", "book"):
        problems.append(f"unit {row.get('id', '?')}: unknown unitType {row.get('unitType')!r}")
        return False
    return True


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from-file", type=Path,
                       help="read the approved units from a saved JSON payload instead of the relay")
    parser.add_argument("--relay-base", help=f"admin base URL (default: ${BASE_ENV})")
    parser.add_argument("--cap", default="live", help="relay cap label (default: live)")
    parser.add_argument("--locale", action="append", help="restrict to this locale (repeatable)")
    parser.add_argument("--authors-file", type=Path, default=pio.DEFAULT_AUTHORS_FILE)
    parser.add_argument("--narrative-dir", type=Path, default=pio.DEFAULT_NARRATIVE_DIR)
    parser.add_argument("--register-new", action="store_true",
                       help='add unregistered translators to authors.json as "human"')
    parser.add_argument("--new-authors-out", type=Path,
                       help="write the names registered this run to this path as a JSON array, "
                            "so the PR that carries them can say who is new (a --dry-run writes "
                            "no file, like every other write here)")
    parser.add_argument("--deferred-out", type=Path,
                       help="write the units that could not be imported to this JSON file, so "
                            "the PR body can name them")
    parser.add_argument("--dry-run", action="store_true",
                       help="report what would be imported and stamped, writing nothing")
    # stamp-provenance.py's single-namespace escape hatch, mirrored so a redirected run
    # (the tests) never reaches the real assets tree. Normal runs pass none of these.
    parser.add_argument("--lang-dir", type=Path, help="operate on one explicit lang dir")
    parser.add_argument("--provenance-dir", type=Path, help="sidecars for --lang-dir")
    parser.add_argument("--credits-dir", type=Path)
    parser.add_argument("--contributors-file", type=Path)
    parser.add_argument("--manifest-dir", type=Path)
    parser.add_argument("--narrative-provenance-dir", type=Path)
    args = parser.parse_args(argv)

    if bool(args.lang_dir) != bool(args.provenance_dir):
        parser.error("--lang-dir and --provenance-dir go together")

    problems: list[str] = []
    deferred: list[str] = []
    rows = [row for row in load_rows(args) if valid(row, problems)]
    if not rows:
        print("no approved units to import" + (" (see problems below)" if problems else ""))
        return report(problems, [], deferred, args)

    unknown = check_registered({translator_of(r) for r in rows}, args.authors_file,
                               args.register_new)

    if args.lang_dir:
        ns_dirs = {"dungeontrain": pio.Namespace("dungeontrain", args.lang_dir,
                                                 args.provenance_dir, None, None)}
    else:
        ns_dirs = {ns.name: ns for ns in pio.namespaces()}
    revised, confirmed, stale = import_lang(
        [r for r in rows if r["unitType"] == "lang"], ns_dirs, args.dry_run, problems, deferred)
    rewritten, reviewed = import_books(
        [r for r in rows if r["unitType"] == "book"], args.narrative_dir, args.dry_run, problems,
        deferred)

    counts = (sum(map(len, revised.values())), sum(map(len, confirmed.values())),
              sum(map(len, rewritten.values())) + sum(map(len, reviewed.values())))
    print(f"{len(rows)} approved unit(s): {counts[0]} string(s) revised, {counts[1]} confirmed "
          f"as already matching, {counts[2]} book(s) touched, {len(stale)} stale")

    # The translator is the last element of every group key, lang (namespace, locale, name) and
    # book (locale, name) alike — so this is everyone who ended up with something to be stamped.
    landed = {group[-1] for group in (*revised, *confirmed, *rewritten, *reviewed)}
    registered = register_landed(unknown, landed, args.authors_file, args.dry_run)
    if args.new_authors_out and not args.dry_run:
        args.new_authors_out.write_text(json.dumps(registered, ensure_ascii=False, indent=2)
                                        + "\n", encoding="utf-8")

    stamp_lang(revised, confirmed, args)
    stamp_books(rewritten, reviewed, args)
    return report(problems, stale, deferred, args)


def report(problems: list[str], stale: list[str], deferred: list[str], args) -> int:
    """Print what was skipped, and decide whether any of it should stop the run.

    Three outcomes, and the distinction is the whole point. STALE and DEFERRED units are expected:
    players translate faster than the repo moves, and a queue of 1790 approvals will always carry
    some that no longer have anywhere to go. PROBLEMS mean the importer or the repo is wrong.

    Only problems fail the run. Until 2026-08-30 every category failed it, so 198 units the repo
    could not accept — 171 of them a single key set English had and Russian did not — took 979
    good translations down with them, seven dispatches in a row, and the job never once opened a
    PR. Nothing is silently dropped either way: what could not land is printed here, and written
    to --deferred-out for the PR body to name.
    """
    if stale:
        print("\nskipped — the English has changed since these were translated:")
        for line in stale:
            print(f"  - {line}")
    if deferred:
        print(f"\n{len(deferred)} unit(s) had nowhere to land — kept for a later run:")
        for line in deferred:
            print(f"  - {line}")
    if args is not None and args.deferred_out and not args.dry_run:
        args.deferred_out.write_text(json.dumps(deferred, ensure_ascii=False, indent=2) + "\n",
                                     encoding="utf-8")
    if problems:
        print("\nunits that could not be imported:", file=sys.stderr)
        for line in problems:
            print(f"  - {line}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
