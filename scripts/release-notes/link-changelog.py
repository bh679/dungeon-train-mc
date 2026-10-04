#!/usr/bin/env python3
"""Point a release changelog at the update page on our website — or take that back out.

Every changelog `release.yml` publishes (GitHub Release, Modrinth + CurseForge mod, both
modpacks) leads players to https://brennan.games/dungeontrain/update/, which shows the newest
version on each launcher and every change since theirs:

  * the first top-level `# ` heading (the release summary's title) becomes a link to it — to that
    release on the page (`#v0.1149.0`) when `--release <tag>` is given — and
  * the footer in update-page-footer.md ("Read more") is appended as the last line.

`--strip` reverses both, for text read inside Minecraft (update.json). `--no-footer` links the
heading but leaves Read more off — the Discord ping, whose Download button already goes there.
Both make text that may already be linked (a GitHub Release body) consistent again.

Idempotent: linking already-linked notes changes nothing. Only a `# ` heading is linked, never
`##`/`###`, so GitHub's generated cascade notes ("## What's Changed") get the footer only.

Usage (stdin → stdout):
  python3 scripts/release-notes/link-changelog.py --release v0.1149.0 < notes.md > linked.md
  python3 scripts/release-notes/link-changelog.py --release v0.1149.0 --no-footer < notes.md
  python3 scripts/release-notes/link-changelog.py --strip < linked.md > plain.md
"""
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
FOOTER_FILE = os.path.join(HERE, "update-page-footer.md")


def read_footer() -> str:
    with open(FOOTER_FILE, encoding="utf-8") as f:
        return f.read().strip()


def footer_url(footer: str) -> str:
    m = re.search(r"\]\((https://[^)\s]+)\)", footer)
    if not m:
        raise ValueError(f"{FOOTER_FILE} has no markdown link")
    return m.group(1)


_H1 = re.compile(r"^#[ \t]+(.+?)[ \t]*$")
_LINKED = re.compile(r"^\[(.+)\]\((\S+)\)$")
_TAG = re.compile(r"^v\d+\.\d+\.\d+$")


def _heading_index(lines: list[str]) -> int:
    """Index of the first top-level `# ` heading, or -1."""
    for i, line in enumerate(lines):
        if _H1.match(line):
            return i
    return -1


def strip(notes: str, footer: str) -> str:
    url = footer_url(footer)
    lines = [l for l in notes.split("\n") if l.strip() != footer]
    i = _heading_index(lines)
    if i >= 0:
        title = _H1.match(lines[i]).group(1)
        linked = _LINKED.match(title)
        if linked and linked.group(2).split("#", 1)[0] == url:
            lines[i] = "# " + linked.group(1)
    return "\n".join(lines).rstrip() + "\n"


def link(notes: str, footer: str, release: str | None = None, with_footer: bool = True) -> str:
    url = footer_url(footer) + (f"#{release}" if release else "")
    lines = strip(notes, footer).rstrip("\n").split("\n")
    i = _heading_index(lines)
    if i >= 0:
        title = _H1.match(lines[i]).group(1)
        if not _LINKED.match(title):
            lines[i] = f"# [{title}]({url})"
    body = "\n".join(lines).rstrip()
    if not with_footer:
        return body + "\n"
    return (body + "\n\n" if body else "") + footer + "\n"


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    with_footer = "--no-footer" not in args
    rest = [a for a in args if a != "--no-footer"]
    release = None
    if len(rest) == 2 and rest[0] == "--release" and _TAG.match(rest[1]):
        release = rest[1]
    elif rest not in ([], ["--strip"]) or (rest == ["--strip"] and not with_footer):
        print(__doc__, file=sys.stderr)
        return 2
    footer = read_footer()
    notes = sys.stdin.read()
    out = strip(notes, footer) if rest == ["--strip"] else link(notes, footer, release, with_footer)
    sys.stdout.write(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
