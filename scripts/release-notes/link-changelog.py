#!/usr/bin/env python3
"""Point a release changelog at the update page on our website — or take that back out.

Every changelog `release.yml` publishes (GitHub Release, Modrinth + CurseForge mod, both
modpacks) leads players to https://brennan.games/dungeontrain/update/, which shows the newest
version on each launcher and every change since theirs:

  * the first top-level `# ` heading (the release summary's title) becomes a link to it — to that
    release on the page (`#v0.1149.0`) when `--release <tag>` is given — and
  * the footer in update-page-footer.md ("Read more") is appended as the last line.

`--strip` reverses both, for places that must not carry the link: the Discord ping (whose
title and Download button already go there) — and it is how text that may already be linked
(a GitHub Release body reused by the modpack catch-up) is made plain again.

Idempotent: linking already-linked notes changes nothing. Only a `# ` heading is linked, never
`##`/`###`, so GitHub's generated cascade notes ("## What's Changed") get the footer only.

Usage (stdin → stdout):
  python3 scripts/release-notes/link-changelog.py --release v0.1149.0 < notes.md > linked.md
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


def link(notes: str, footer: str, release: str | None = None) -> str:
    url = footer_url(footer) + (f"#{release}" if release else "")
    lines = strip(notes, footer).rstrip("\n").split("\n")
    i = _heading_index(lines)
    if i >= 0:
        title = _H1.match(lines[i]).group(1)
        if not _LINKED.match(title):
            lines[i] = f"# [{title}]({url})"
    body = "\n".join(lines).rstrip()
    return (body + "\n\n" if body else "") + footer + "\n"


def main(argv: list[str] | None = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    release = None
    if len(args) == 2 and args[0] == "--release" and _TAG.match(args[1]):
        release = args[1]
    elif args not in ([], ["--strip"]):
        print(__doc__, file=sys.stderr)
        return 2
    footer = read_footer()
    notes = sys.stdin.read()
    sys.stdout.write(strip(notes, footer) if args == ["--strip"] else link(notes, footer, release))
    return 0


if __name__ == "__main__":
    sys.exit(main())
