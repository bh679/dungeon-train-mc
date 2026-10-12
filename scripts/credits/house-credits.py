#!/usr/bin/env python3
"""Generate the Credits page's "house" rows — the team's own builds and books.

The Builders and Writers cards thank players for what they built and wrote; the shipped game's own
content was made by the team, and nothing on the relay knows that. This counts it straight from the
bundled sources and writes ``src/main/resources/assets/dungeontrain/credits/house_credits.json``:

  * builders — every entry in every bundled ``weights.json`` with no ``builder`` credit is the
    default writer's (Brennan's) build.
  * writers  — one book per story letter (a letter's alternate takes are one book), per random-book
    variant and per starting-book variant. Who wrote what comes from ``writer_credits.json`` beside
    this script; anything not listed there is the default writer's.

The mod reads the JSON (client/credits/HouseCredits) and the website's Credits page reads the same
committed file off GitHub, so the two cannot disagree. ``--check`` (CI) fails when the committed
file is stale, or when a variant credited to someone other than the default no longer matches the
original it was credited from. Stdlib only.

Usage:
    python3 scripts/credits/house-credits.py           # rewrite the JSON
    python3 scripts/credits/house-credits.py --check   # verify, write nothing
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DATA = REPO / "src/main/resources/data/dungeontrain"
NARRATIVES = DATA / "narratives"
MANIFEST = Path(__file__).resolve().parent / "writer_credits.json"
OUT = REPO / "src/main/resources/assets/dungeontrain/credits/house_credits.json"

#: Share of a credited variant's six-word runs that must appear in its original. Wilson's untouched
#: Faulthurst variants score 1.0; the lightly edited ones 0.6–0.9; later additions score 0.
MATCH_FLOOR = 0.5
SHINGLE = 6


def _load(path: Path):
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def count_uncredited_builds() -> int:
    """Bundled template entries (every kind's weights.json) that credit no builder."""
    total = 0
    for path in sorted(DATA.rglob("weights.json")):
        entries = _load(path)
        if not isinstance(entries, dict):
            continue
        for entry in entries.values():
            credited = isinstance(entry, dict) and isinstance(entry.get("builder"), dict)
            if not credited:
                total += 1
    return total


def _books_by_id(folder: str) -> dict[str, dict]:
    books = {}
    for path in sorted((NARRATIVES / folder).rglob("*.json")):
        book = _load(path)
        if isinstance(book, dict) and "id" in book:
            books[book["id"]] = book
    return books


def count_books(manifest: dict) -> dict[str, int]:
    """Findable books per writer name, the default writer included."""
    default = manifest["default"]
    stories = _books_by_id("stories")
    random_books = _books_by_id("random_books")
    starting = _books_by_id("starting_books")

    story_owner = {}
    variant_owner = {}
    for writer in manifest["writers"]:
        for sid in writer.get("stories", []):
            if sid not in stories:
                raise SystemExit(f"writer_credits.json: unknown story '{sid}'")
            story_owner[sid] = writer["name"]
        for bid, indices in writer.get("random_book_variants", {}).items():
            if bid not in random_books:
                raise SystemExit(f"writer_credits.json: unknown random book '{bid}'")
            size = len(random_books[bid].get("variants", []))
            for i in indices:
                if not 0 <= i < size:
                    raise SystemExit(f"writer_credits.json: {bid} has no variant {i} (it has {size})")
                variant_owner[(bid, i)] = writer["name"]

    counts = {default: 0}
    for w in manifest["writers"]:
        counts.setdefault(w["name"], 0)
    for sid, story in stories.items():
        counts[story_owner.get(sid, default)] += len(story.get("letters", []))
    for bid, book in random_books.items():
        for i, _ in enumerate(book.get("variants", [])):
            counts[variant_owner.get((bid, i), default)] += 1
    for book in starting.values():
        counts[default] += len(book.get("variants", []))
    return counts


def _norm(text: str) -> str:
    return re.sub(r"\s+", " ", text.replace("\\n", " ")).strip().lower()


def _match_score(variant: str, original: str) -> float:
    words = _norm(variant).split()
    runs = [" ".join(words[j:j + SHINGLE]) for j in range(max(1, len(words) - SHINGLE + 1))]
    return sum(1 for r in runs if r in original) / len(runs)


def credit_drift(manifest: dict) -> list[str]:
    """Credited variants that no longer read like the original they were credited from."""
    problems = []
    random_books = _books_by_id("random_books")
    for writer in manifest["writers"]:
        for bid, source in writer.get("originals", {}).items():
            original = _norm((NARRATIVES / "originals" / source).read_text(encoding="utf-8"))
            variants = random_books[bid].get("variants", [])
            for i in writer.get("random_book_variants", {}).get(bid, []):
                text = variants[i] if isinstance(variants[i], str) else json.dumps(variants[i])
                score = _match_score(text, original)
                if score < MATCH_FLOOR:
                    problems.append(f"{bid} variant {i} is credited to {writer['name']} but only "
                                    f"{score:.0%} of it matches originals/{source} — re-index writer_credits.json")
    return problems


def build() -> dict:
    manifest = _load(MANIFEST)
    books = count_books(manifest)
    writers = sorted(({"name": n, "books": c} for n, c in books.items() if c > 0),
                     key=lambda r: (-r["books"], r["name"].lower()))
    builds = count_uncredited_builds()
    builders = [{"name": manifest["default"], "builds": builds}] if builds > 0 else []
    return {
        "_comment": "GENERATED by scripts/credits/house-credits.py — do not edit by hand. The team's "
                    "own builds and books on the Credits page (mod + website).",
        "builders": builders,
        "writers": writers,
    }


def render(body: dict) -> str:
    return json.dumps(body, indent=2, ensure_ascii=False) + "\n"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--check", action="store_true", help="fail if the committed file is stale; write nothing")
    args = ap.parse_args(argv)

    drift = credit_drift(_load(MANIFEST))
    for line in drift:
        print(f"house-credits: {line}", file=sys.stderr)

    text = render(build())
    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.exists() else ""
        if current != text:
            print(f"house-credits: {OUT.relative_to(REPO)} is stale — run "
                  "python3 scripts/credits/house-credits.py and commit it", file=sys.stderr)
            return 1
        return 1 if drift else 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"house-credits: wrote {OUT.relative_to(REPO)}")
    print(text, end="")
    return 1 if drift else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
