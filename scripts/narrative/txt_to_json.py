#!/usr/bin/env python3
"""
Convert legacy Unity-era narrative .txt files into structured JSON.

Source format (one .txt per story):
  Character: <name>      (optional, may appear before Story)
  Story: <title>         (optional, may be empty/missing)
  <preamble>             (discarded — editor notes)
  Letter One-            (or 'Letter <label>-' / 'Letter <label> – ')
  <body lines...>
  Alt                    (optional, starts a new variant of the SAME letter)
  <alt body lines...>
  Letter Two-
  ...

Notes:
  * The dash after the label may be ASCII '-' (U+002D) or em-dash '–' (U+2013).
  * 'Character:' / 'Story:' headers are order-independent and may have no
    space after the colon.
  * Labels can be compound ('One Point Five', 'Five Alternate'); the
    1-based index is inferred from source order, not the label text.
  * Backslash-n ('\\n') in the source is a forced wrap marker — converted
    to a real newline so the runtime never sees the escape literal.

Output schema (one .json per story):
  {
    "id":        "<filename slug>",
    "character": "<name|Anonymous>",
    "story":     "<title|Untitled>",
    "letters": [
      { "index": 1, "label": "Letter One",
        "variants": ["body...", "alt body..."] }
    ]
  }

Usage:
  python3 txt_to_json.py <dir> [--force] [--delete-source]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

LETTER_RE = re.compile(r"^Letter(?:\s+(.+?))?\s*[-–]\s*$")
# Matches:  Alt   |   Alt-   |   Alt 2   |   Alt 2-
ALT_RE = re.compile(r"^Alt(?:\s+\d+)?\s*[-–]?\s*$")
HEADER_RE = re.compile(r"^(Character|Story)\s*:\s*(.*)$")
# Editor / author notes inside narrative bodies — strip from the rendered
# text and capture into the letter's `notes` field for reference.
EDITOR_NOTE_RE = re.compile(r"\[([^\]\n]+)\]")


def slug(name: str) -> str:
    stem = name[:-4] if name.lower().endswith(".txt") else name
    s = re.sub(r"[^A-Za-z0-9]+", "_", stem).strip("_").lower()
    return s


def parse(text: str, story_id: str) -> dict:
    character = "Anonymous"
    story = "Untitled"
    # letters: each entry is (label, variants[], notes[])
    letters: list[tuple[str, list[list[str]], list[str]]] = []
    current_label: str | None = None
    current_variants: list[list[str]] | None = None
    current_notes: list[str] | None = None
    in_preamble = True
    headers_locked = False

    for raw in text.splitlines():
        line = raw.rstrip("\r\n").rstrip()

        if not headers_locked:
            m = HEADER_RE.match(line)
            if m:
                key, value = m.group(1), m.group(2).strip()
                if key == "Character" and value:
                    character = value
                elif key == "Story" and value:
                    story = value
                continue

        m = LETTER_RE.match(line)
        if m:
            raw_label = m.group(1)
            if current_label is not None:
                letters.append((current_label, current_variants or [[]], current_notes or []))
            if raw_label and raw_label.strip():
                current_label = f"Letter {raw_label.strip()}"
            else:
                current_label = f"Letter {len(letters) + 1}"
            current_variants = [[]]
            current_notes = []
            in_preamble = False
            headers_locked = True
            continue

        if current_label is not None and ALT_RE.match(line):
            assert current_variants is not None
            current_variants.append([])
            continue

        if in_preamble:
            continue

        assert current_variants is not None
        current_variants[-1].append(line)

    if current_label is not None:
        letters.append((current_label, current_variants or [[]], current_notes or []))

    out_letters = []
    for i, (label, variants, _) in enumerate(letters):
        # Strip [bracketed notes] from each variant; record each note with
        # its variant index + character offset in the cleaned text.
        cleaned_variants: list[str] = []
        notes: list[dict] = []
        for vi, v in enumerate(variants):
            cleaned, variant_notes = strip_notes_with_offsets(variant_to_text(v), vi)
            cleaned_variants.append(cleaned)
            notes.extend(variant_notes)
        out_letter: dict = {
            "index": i + 1,
            "label": label,
            "variants": cleaned_variants,
        }
        if notes:
            out_letter["notes"] = notes
        out_letters.append(out_letter)

    return {
        "id": story_id,
        "character": character,
        "story": story,
        "letters": out_letters,
    }


_TRAILING_LITERAL_NL = re.compile(r"(?:\s*\\n)+\s*$")


def strip_notes_with_offsets(body: str, variant_idx: int) -> tuple[str, list[dict]]:
    """Strip ``[bracketed notes]`` from ``body``; return (cleaned_text, notes).

    Each note dict has:
      ``variant`` — the 0-based index passed in
      ``offset`` — character index in the *cleaned* text where the note
                   appeared (i.e. the position the bracket opened, after
                   prior notes in the same variant have already been
                   removed)
      ``text``   — the note body, trimmed
    """
    notes: list[dict] = []
    out_parts: list[str] = []
    last_end = 0
    cleaned_len = 0
    for m in EDITOR_NOTE_RE.finditer(body):
        between = body[last_end:m.start()]
        out_parts.append(between)
        cleaned_len += len(between)
        notes.append({
            "variant": variant_idx,
            "offset": cleaned_len,
            "text": m.group(1).strip(),
        })
        last_end = m.end()
    out_parts.append(body[last_end:])
    cleaned = "".join(out_parts)
    # Trim trailing whitespace per line. May shift offsets slightly for
    # notes near line ends — accepted as a prototype-grade tradeoff.
    cleaned = "\n".join(ln.rstrip() for ln in cleaned.splitlines())
    return cleaned, notes


def variant_to_text(lines: list[str]) -> str:
    while lines and not lines[0].strip():
        lines.pop(0)
    while lines and not lines[-1].strip():
        lines.pop()
    cleaned = []
    for line in lines:
        # Trailing '\n' literals were forced-wrap markers in the original
        # editor — once we join source lines with real newlines, end-of-line
        # '\n' becomes redundant and would create blank lines.
        line = _TRAILING_LITERAL_NL.sub("", line).rstrip()
        # Mid-line '\n' (rare) becomes a real break inside the joined line.
        line = line.replace("\\n", "\n")
        cleaned.append(line)
    return "\n".join(cleaned)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("source_dir", type=Path,
                    help="directory containing .txt narrative files")
    ap.add_argument("--force", action="store_true",
                    help="overwrite existing .json files")
    ap.add_argument("--delete-source", action="store_true",
                    help="delete the .txt after writing the .json")
    args = ap.parse_args()

    source_dir: Path = args.source_dir
    if not source_dir.is_dir():
        print(f"error: {source_dir} is not a directory", file=sys.stderr)
        return 2

    txt_files = sorted(source_dir.glob("*.txt"))
    if not txt_files:
        print(f"no .txt files in {source_dir}", file=sys.stderr)
        return 1

    print(f"{'source':56} {'letters':>7} {'variants':>9}  output")
    print("-" * 92)
    written = 0
    for txt in txt_files:
        story_id = slug(txt.name)
        out = source_dir / f"{story_id}.json"
        if out.exists() and not args.force:
            print(f"  SKIP (exists, use --force): {out.name}", file=sys.stderr)
            continue
        text = txt.read_text(encoding="utf-8")
        parsed = parse(text, story_id)
        out.write_text(
            json.dumps(parsed, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        n_letters = len(parsed["letters"])
        n_variants = sum(len(l["variants"]) for l in parsed["letters"])
        print(f"  {txt.name[:56]:56} {n_letters:>7} {n_variants:>9}  {out.name}")
        written += 1
        if args.delete_source:
            txt.unlink()

    print("-" * 92)
    print(f"wrote {written} json file(s) to {source_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
