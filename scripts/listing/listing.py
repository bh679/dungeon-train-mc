#!/usr/bin/env python3
"""Create and edit our mods' store pages. See docs/guides/newmod.md and editmod.md.

A page lives in one folder of the mod's repo:

  listing/listing.json   slug, title, summary, categories, links, icon, screenshots
  listing/LISTING.md     the page text, exactly as it appears on the store

Commands:

  tokens                       check that each stored token works
  check  <dir> [--offline]     run the page rules; talks to no store
  pull   <slug> --out <dir>    copy a live Modrinth page into a folder
  draft  <dir>                 create a private Modrinth draft
  update <dir> [--dry-run | --confirmed]   apply edits to a live Modrinth page
  submit <slug> --confirmed    send a draft to Modrinth's reviewers
  issues-add <dir> [--confirmed]   add the mod's row to dungeon.training/issues (relay admin call)

Anything other people can see needs --confirmed, which means Brennan has approved the text,
screenshots and links. CurseForge has no API for pages, so its pages are made in the browser.

Tokens come from ~/.config/dungeontrain/publish-tokens.env (LISTING_TOKENS_FILE overrides it) and
are never printed. Stdlib only, matching the other scripts.
"""
import argparse
import difflib
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections.abc import Callable

USER_AGENT = "bh679/dungeon-train-listing (Brennan@brennanhatton.com)"
MODRINTH_API = "https://api.modrinth.com/v2"
CURSEFORGE_UPLOAD_API = "https://minecraft.curseforge.com/api"
CURSEFORGE_CORE_API = "https://api.curseforge.com/v1"
MINECRAFT_GAME_ID = 432
TOKENS_FILE = os.environ.get(
    "LISTING_TOKENS_FILE", os.path.expanduser("~/.config/dungeontrain/publish-tokens.env")
)
ISSUES_PAGE = "https://dungeon.training/issues/"
# The page's mod list lives in dp-relay (issue-projects.js): read it here, edit it under the admin cap.
ISSUES_API = "https://brennan.games/api/dp-relay/issues/projects"
RELAY_ADMIN_URL_FILE = os.environ.get(
    "LISTING_RELAY_ADMIN_URL_FILE", os.path.expanduser("~/.config/dungeontrain/relay-admin-url.txt")
)
SOURCE_REPO = re.compile(r"^https://github\.com/(bh679/[A-Za-z0-9._-]+?)/?$")

LONG_DASHES = "‒–—―"
STANDALONE_SENTENCE = (
    "This mod is designed specifically for the Dungeon Train modpack / mod, "
    "but can be used independently."
)
DEPENDENT_SENTENCE = (
    "Built for Dungeon Train and only does something when Dungeon Train is installed."
)
DT_MOD_LINK = re.compile(
    r"https://(modrinth\.com/mod/dungeon-train|www\.curseforge\.com/minecraft/mc-mods/dungeon-train)\b"
)
DT_PACK_LINK = re.compile(
    r"https://(modrinth\.com/modpack/(bEFyz3ji|dungeon-train)"
    r"|www\.curseforge\.com/(projects/1556213|minecraft/modpacks/dungeon-train))\b"
)
# No slash before "#": Modrinth's link allowlist refuses ".../issues/#anchor" but accepts this.
ISSUES_LINK = re.compile(r"^https://dungeon\.training/issues#([a-z0-9-]+)$")
SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{2,63}$")

# Modrinth's limits, from its API docs.
MAX_SUMMARY = 256
MAX_TITLE = 64
MAX_ICON_BYTES = 256 * 1024
MAX_GALLERY_BYTES = 5 * 1024 * 1024
IMAGE_TYPES = {
    "png": "image/png",
    "jpg": "image/jpeg",
    "jpeg": "image/jpeg",
    "gif": "image/gif",
    "webp": "image/webp",
}

REQUIRED_FIELDS = (
    "slug", "title", "summary", "categories",
    "license_id", "source_url", "issues_url", "icon", "screenshots",
)
LINK_FIELDS = ("issues_url", "source_url", "wiki_url", "discord_url")
EDITABLE_FIELDS = ("title", "categories", "license_id", *LINK_FIELDS)


class ListingError(Exception):
    """A problem the operator can act on; printed without a traceback."""


# ---- reading a listing folder --------------------------------------------------------------------


def load_listing(folder: str) -> dict:
    """Return the listing as a dict, with the page text under 'body'."""
    try:
        with open(os.path.join(folder, "listing.json"), encoding="utf-8") as f:
            listing = json.load(f)
        with open(os.path.join(folder, "LISTING.md"), encoding="utf-8") as f:
            body = f.read()
    except (OSError, ValueError) as e:
        raise ListingError(f"can't read the listing in {folder}: {e}") from e
    if not isinstance(listing, dict):
        raise ListingError(f"{folder}/listing.json must hold an object")
    return {**listing, "body": body}


def first_section(body: str) -> str:
    """The page text above its first heading or divider, ignoring a leading title."""
    lines = body.strip().splitlines()
    if lines and lines[0].startswith("# "):
        lines = lines[1:]
    section = []
    for line in lines:
        if line.startswith("#") or line.strip() in ("---", "***"):
            break
        section.append(line)
    return "\n".join(section)


def plain_text(markdown: str) -> str:
    """Markdown with links reduced to their words, emphasis and quote marks gone, spaces squeezed."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", markdown)
    text = re.sub(r"^\s*>\s?", "", text, flags=re.MULTILINE)
    text = text.replace("**", "").replace("__", "")
    return " ".join(text.split())


# ---- the page rules ------------------------------------------------------------------------------


def long_dash_problems(listing: dict) -> list[str]:
    shots = listing.get("screenshots") or []
    texts = {
        "title": listing.get("title", ""),
        "summary": listing.get("summary", ""),
        "LISTING.md": listing.get("body", ""),
    }
    for i, shot in enumerate(shots):
        if isinstance(shot, dict):
            texts[f"screenshot {i + 1} title"] = shot.get("title", "")
            texts[f"screenshot {i + 1} description"] = shot.get("description", "")
    problems = []
    for name, text in texts.items():
        for number, line in enumerate(str(text).splitlines(), 1):
            if any(dash in line for dash in LONG_DASHES):
                problems.append(f"long dash in {name} line {number}: {line.strip()[:70]}")
    return problems


def first_section_problems(listing: dict) -> list[str]:
    if listing.get("dungeon_train_itself"):  # DT's own mod and modpack pages
        return []
    section = first_section(listing.get("body", ""))
    words = plain_text(section)
    wanted = DEPENDENT_SENTENCE if listing.get("requires_dungeon_train") else STANDALONE_SENTENCE
    problems = []
    if wanted not in words:
        problems.append(f'first section must say: "{wanted}"')
    if not DT_MOD_LINK.search(section):
        problems.append("first section must link the Dungeon Train mod page")
    if not DT_PACK_LINK.search(section):
        problems.append("first section must link the Dungeon Train modpack page")
    return problems


def field_problems(listing: dict) -> list[str]:
    problems = [
        f"listing.json is missing {field}"
        for field in REQUIRED_FIELDS
        if not listing.get(field)
    ]
    slug = listing.get("slug", "")
    if slug and not SLUG.match(slug):
        problems.append(f"slug is not valid: {slug}")
    if len(listing.get("title", "")) > MAX_TITLE:
        problems.append(f"title is over {MAX_TITLE} characters")
    if len(listing.get("summary", "")) > MAX_SUMMARY:
        problems.append(f"summary is over {MAX_SUMMARY} characters")
    return problems


def image_problem(folder: str, name: str, limit: int) -> str | None:
    path = os.path.join(folder, name)
    if image_extension(name) not in IMAGE_TYPES:
        return f"{name} is not a png, jpg, gif or webp"
    if not os.path.isfile(path):
        return f"{name} does not exist"
    if os.path.getsize(path) > limit:
        return f"{name} is over {limit // 1024} KiB"
    return None


def image_problems(listing: dict, folder: str) -> list[str]:
    problems = []
    if listing.get("icon"):
        found = image_problem(folder, listing["icon"], MAX_ICON_BYTES)
        problems += [found] if found else []
    shots = listing.get("screenshots") or []
    for shot in shots:
        if not isinstance(shot, dict) or not shot.get("file") or not shot.get("title"):
            problems.append("each screenshot needs a file and a title")
            continue
        found = image_problem(folder, shot["file"], MAX_GALLERY_BYTES)
        problems += [found] if found else []
    if shots and sum(1 for s in shots if isinstance(s, dict) and s.get("featured")) != 1:
        problems.append("exactly one screenshot must be featured")
    return problems


def issues_link_problems(listing: dict, offline: bool, fetch_page: Callable | None = None) -> list[str]:
    match = ISSUES_LINK.match(listing.get("issues_url") or "")
    if not match:
        return ["issues_url must be https://dungeon.training/issues#<anchor>"]
    if offline:
        return []
    fetch = fetch_page or fetch_text
    anchor = match.group(1)
    # The page draws its rows from the relay, so the relay's list is the truth. A relay without the
    # route yet (or a test's HTML stub) falls back to the rows built into the page.
    try:
        ids = [p.get("id") for p in json.loads(fetch(ISSUES_API)).get("projects", [])]
        if anchor in ids:
            return []
    except (OSError, ListingError, ValueError, AttributeError):
        pass
    try:
        page = fetch(ISSUES_PAGE)
    except (OSError, ListingError) as e:
        return [f"couldn't read the issues page to check the anchor: {e}"]
    if f'id="{anchor}"' not in page:
        return [f'the issues page has no row "{anchor}" yet: run `listing.py issues-add <dir> --confirmed`']
    return []


def page_problems(
    listing: dict, folder: str, offline: bool = False, fetch_page: Callable | None = None
) -> list[str]:
    return [
        *field_problems(listing),
        *long_dash_problems(listing),
        *first_section_problems(listing),
        *issues_link_problems(listing, offline, fetch_page),
        *image_problems(listing, folder),
    ]


def require_clean(folder: str, offline: bool = False) -> dict:
    listing = load_listing(folder)
    problems = page_problems(listing, folder, offline)
    if problems:
        raise ListingError("page rules failed:\n  " + "\n  ".join(problems))
    return listing


# ---- tokens --------------------------------------------------------------------------------------


def load_tokens(path: str = TOKENS_FILE) -> dict:
    """Read KEY=value lines. Environment variables are ignored so a stale shell can't win."""
    try:
        with open(path, encoding="utf-8") as f:
            lines = f.read().splitlines()
    except OSError as e:
        raise ListingError(f"no token file at {path}: see docs/guides/newmod.md") from e
    tokens = {}
    for line in lines:
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            tokens[key.strip()] = value.strip().strip("'\"")
    return tokens


def modrinth_token() -> str:
    token = load_tokens().get("MODRINTH_TOKEN")
    if not token:
        raise ListingError(f"MODRINTH_TOKEN is not set in {TOKENS_FILE}")
    return token


def optional_modrinth_token() -> str | None:
    """The token when there is one: public pages read without it, private drafts need it."""
    try:
        return load_tokens().get("MODRINTH_TOKEN")
    except ListingError:
        return None


# ---- HTTP ----------------------------------------------------------------------------------------


def fetch_text(url: str) -> str:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8", "replace")


def call(
    method: str, url: str, headers: dict | None = None, data: bytes | None = None
) -> tuple[int, object]:
    """Return (status, parsed JSON or raw text). HTTP errors come back as statuses, not exceptions."""
    request = urllib.request.Request(
        url, method=method, data=data, headers={"User-Agent": USER_AGENT, **(headers or {})}
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    except OSError as e:
        raise ListingError(f"{method} {url} failed: {e}") from e
    text = raw.decode("utf-8", "replace")
    try:
        return status, json.loads(text) if text else None
    except ValueError:
        return status, text


def modrinth(method: str, path: str, token: str | None = None, **kwargs: object) -> tuple[int, object]:
    headers = dict(kwargs.pop("headers", {}))
    if token:
        headers["Authorization"] = token
    return call(method, MODRINTH_API + path, headers=headers, **kwargs)


def modrinth_json(method: str, path: str, token: str, payload: dict) -> tuple[int, object]:
    return modrinth(
        method,
        path,
        token,
        headers={"Content-Type": "application/json"},
        data=json.dumps(payload).encode("utf-8"),
    )


def expect(result: tuple[int, object], *ok: int, doing: str) -> object:
    status, payload = result
    if status not in ok:
        raise ListingError(f"{doing} failed with HTTP {status}: {str(payload)[:400]}")
    return payload


def image_extension(name: str) -> str:
    return os.path.splitext(name)[1].lstrip(".").lower()


def read_bytes(folder: str, name: str) -> bytes:
    with open(os.path.join(folder, name), "rb") as f:
        return f.read()


def multipart(fields: dict, files: dict) -> tuple[str, bytes]:
    """Encode text fields and {name: (filename, content type, bytes)} as multipart/form-data."""
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n'
            f"Content-Type: application/json\r\n\r\n{value}\r\n".encode("utf-8")
        )
    for name, (filename, content_type, content) in files.items():
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; '
            f'filename="{filename}"\r\nContent-Type: {content_type}\r\n\r\n'.encode("utf-8")
            + content
            + b"\r\n"
        )
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return f"multipart/form-data; boundary={boundary}", b"".join(parts)


# ---- commands ------------------------------------------------------------------------------------


def probe_modrinth(token: str) -> str:
    # A project that can't exist, so nothing can change. Modrinth checks the token first: a bad one
    # gets "authenticating API request", a good one gets as far as this project-level refusal.
    status, payload = modrinth_json("PATCH", "/project/zzzzzzzz", token, {})
    accepted = status == 404 or (
        status == 401 and "permission to edit this project" in str(payload)
    )
    return "ok" if accepted else f"failed (HTTP {status})"


def probe_curseforge_upload(token: str) -> str:
    status, _ = call(
        "GET", f"{CURSEFORGE_UPLOAD_API}/game/versions", headers={"X-Api-Token": token}
    )
    return "ok" if status == 200 else f"failed (HTTP {status})"


def probe_curseforge_core(key: str) -> str:
    status, _ = call(
        "GET", f"{CURSEFORGE_CORE_API}/games/{MINECRAFT_GAME_ID}", headers={"x-api-key": key}
    )
    return "ok" if status == 200 else f"failed (HTTP {status})"


PROBES = {
    "MODRINTH_TOKEN": probe_modrinth,
    "CURSEFORGE_TOKEN": probe_curseforge_upload,
    "CURSEFORGE_API_KEY": probe_curseforge_core,
}


def cmd_tokens(_args: argparse.Namespace) -> int:
    tokens = load_tokens()
    failed = False
    for name, probe in PROBES.items():
        value = tokens.get(name)
        outcome = probe(value) if value else "missing"
        failed = failed or outcome != "ok"
        print(f"{name}: {outcome}")
    return 1 if failed else 0


def cmd_check(args: argparse.Namespace) -> int:
    listing = load_listing(args.dir)
    problems = page_problems(listing, args.dir, args.offline)
    for problem in problems:
        print(f"FAIL {problem}")
    if problems:
        print(f"{len(problems)} problem(s) in {args.dir}")
        return 1
    print(f"OK {listing['slug']}: {len(listing['screenshots'])} screenshot(s)")
    print("Still yours to judge: are these the right screenshots? Is the text true?")
    return 0


def draft_data(listing: dict) -> dict:
    return {
        "project_type": "mod",
        "is_draft": True,
        "slug": listing["slug"],
        "title": listing["title"],
        "description": listing["summary"],
        "body": listing["body"],
        "categories": listing["categories"],
        "additional_categories": listing.get("additional_categories", []),
        # Modrinth ignores these on the page: a project's sides come from its versions, which the
        # mod's release.yml sets. These values are simply known to be accepted on create.
        "client_side": "optional",
        "server_side": "optional",
        "license_id": listing["license_id"],
        # A LicenseRef- id is refused on create without the licence's URL.
        **({"license_url": listing["license_url"]} if listing.get("license_url") else {}),
        "initial_versions": [],
        **{field: listing.get(field) for field in LINK_FIELDS},
    }


def description_field(shot: dict) -> dict:
    # Modrinth rejects an empty description, so a screenshot without one leaves the field out.
    return {"description": shot["description"]} if shot.get("description") else {}


def upload_screenshot(token: str, slug: str, folder: str, shot: dict, ordering: int) -> None:
    ext = image_extension(shot["file"])
    query = urllib.parse.urlencode(
        {
            "ext": ext,
            "featured": "true" if shot.get("featured") else "false",
            "title": shot["title"],
            "ordering": ordering,
            **description_field(shot),
        }
    )
    expect(
        modrinth(
            "POST",
            f"/project/{slug}/gallery?{query}",
            token,
            headers={"Content-Type": IMAGE_TYPES[ext]},
            data=read_bytes(folder, shot["file"]),
        ),
        200,
        204,
        doing=f"uploading {shot['file']}",
    )


def record_live_urls(folder: str, slug: str, token: str) -> None:
    """Note each uploaded screenshot's live URL in listing.json, so a later caption edit finds it."""
    gallery = live_project(slug, token).get("gallery") or []
    urls = {g.get("title"): g.get("url") for g in gallery}
    path = os.path.join(folder, "listing.json")
    with open(path, encoding="utf-8") as f:
        listing = json.load(f)
    shots = [
        {**s, "live_url": urls[s["title"]]} if not s.get("live_url") and s["title"] in urls else s
        for s in listing["screenshots"]
    ]
    with open(path, "w", encoding="utf-8") as f:
        json.dump({**listing, "screenshots": shots}, f, indent=2, ensure_ascii=False)
        f.write("\n")


def cmd_draft(args: argparse.Namespace) -> int:
    listing = require_clean(args.dir, args.offline)
    token = modrinth_token()
    icon = listing["icon"]
    ext = image_extension(icon)
    content_type, body = multipart(
        {"data": json.dumps(draft_data(listing))},
        {"icon": (f"icon.{ext}", IMAGE_TYPES[ext], read_bytes(args.dir, icon))},
    )
    expect(
        modrinth("POST", "/project", token, headers={"Content-Type": content_type}, data=body),
        200,
        201,
        doing="creating the draft",
    )
    for ordering, shot in enumerate(listing["screenshots"]):
        upload_screenshot(token, listing["slug"], args.dir, shot, ordering)
    record_live_urls(args.dir, listing["slug"], token)
    print(f"Private draft: https://modrinth.com/mod/{listing['slug']}")
    print("Nothing is public. Next: confirm text, screenshots and links with Brennan.")
    return 0


def live_project(slug: str, token: str | None = None) -> dict:
    return expect(modrinth("GET", f"/project/{slug}", token), 200, doing=f"reading {slug}")


def live_fields(project: dict) -> dict:
    license_info = project.get("license") or {}
    return {
        "title": project.get("title"),
        "summary": project.get("description"),
        "categories": project.get("categories") or [],
        "license_id": license_info.get("id"),
        "body": project.get("body") or "",
        **{field: project.get(field) for field in LINK_FIELDS},
    }


def field_changes(listing: dict, live: dict) -> dict:
    """The fields in the folder that differ from the live page, named as Modrinth names them."""
    changes = {}
    for field in EDITABLE_FIELDS:
        if field in listing and listing[field] != live.get(field):
            changes[field] = listing[field]
    if listing["summary"] != live.get("summary"):
        changes["description"] = listing["summary"]
    if listing["body"] != live.get("body"):
        changes["body"] = listing["body"]
    return changes


def new_screenshots(listing: dict, project: dict) -> list[dict]:
    gallery = project.get("gallery") or []
    live_titles = {g.get("title") for g in gallery}
    live_urls = {g.get("url") for g in gallery}
    return [
        s for s in listing["screenshots"]
        if s["title"] not in live_titles and s.get("live_url") not in live_urls
    ]


def caption_changes(listing: dict, project: dict) -> list[dict]:
    """Live screenshots whose title, description or featured flag differ from the folder."""
    live = {g.get("url"): g for g in project.get("gallery") or []}
    changed = []
    for shot in listing["screenshots"]:
        image = live.get(shot.get("live_url"))
        if image and (
            (image.get("title") or "") != shot["title"]
            or (image.get("description") or "") != shot.get("description", "")
            or bool(image.get("featured")) != bool(shot.get("featured"))
        ):
            changed.append(shot)
    return changed


def update_caption(token: str, slug: str, shot: dict) -> None:
    query = urllib.parse.urlencode(
        {
            "url": shot["live_url"],
            "featured": "true" if shot.get("featured") else "false",
            "title": shot["title"],
            **description_field(shot),
        }
    )
    expect(
        modrinth("PATCH", f"/project/{slug}/gallery?{query}", token),
        200,
        204,
        doing=f"updating the caption of {shot['file']}",
    )


def extra_screenshots(listing: dict, project: dict) -> list[str]:
    wanted = {s["title"] for s in listing["screenshots"]}
    urls = {s.get("live_url") for s in listing["screenshots"]}
    return [
        g.get("title") or g.get("url", "")
        for g in project.get("gallery") or []
        if g.get("title") not in wanted and g.get("url") not in urls
    ]


def print_changes(changes: dict, live: dict, additions: list, extras: list) -> None:
    if not changes and not additions and not extras:
        print("No text, link or new-screenshot differences from the live page.")
    for field, value in changes.items():
        if field == "body":
            print("".join(
                difflib.unified_diff(
                    (live.get("body") or "").splitlines(True),
                    str(value).splitlines(True),
                    "live page",
                    "LISTING.md",
                )
            ))
        else:
            before = live.get("summary" if field == "description" else field)
            print(f"{field}:\n  live: {before}\n  new:  {value}")
    for shot in additions:
        print(f"add screenshot: {shot['title']} ({shot['file']})")
    for title in extras:
        print(f"on the live page but not in the folder (left alone): {title}")


def cmd_update(args: argparse.Namespace) -> int:
    if not args.dry_run and not args.confirmed:
        raise ListingError("update changes a page: use --dry-run, or --confirmed once approved")
    if args.dry_run:
        # Show the diff even for a page that breaks the rules: that is how an old page gets fixed.
        listing = load_listing(args.dir)
        for problem in page_problems(listing, args.dir, args.offline):
            print(f"FAIL {problem}")
        token = optional_modrinth_token()
    else:
        listing = require_clean(args.dir, args.offline)
        token = modrinth_token()
    project = live_project(listing["slug"], token)
    live = live_fields(project)
    changes = field_changes(listing, live)
    additions = new_screenshots(listing, project)
    captions = caption_changes(listing, project)
    print_changes(changes, live, additions, extra_screenshots(listing, project))
    for shot in captions:
        print(f"new caption: {shot['title']} ({shot['file']})")
    if args.dry_run:
        print("Dry run: nothing changed.")
        return 0
    if changes:
        expect(
            modrinth_json("PATCH", f"/project/{listing['slug']}", token, changes),
            200,
            204,
            doing="updating the page",
        )
    for shot in captions:
        update_caption(token, listing["slug"], shot)
    next_order = len(project.get("gallery") or [])
    for offset, shot in enumerate(additions):
        upload_screenshot(token, listing["slug"], args.dir, shot, next_order + offset)
    if additions:
        record_live_urls(args.dir, listing["slug"], token)
    print(f"Updated https://modrinth.com/mod/{listing['slug']}")
    return 0


def cmd_pull(args: argparse.Namespace) -> int:
    project = live_project(args.slug, optional_modrinth_token())
    live = live_fields(project)
    os.makedirs(os.path.join(args.out, "screenshots"), exist_ok=True)
    gallery = sorted(project.get("gallery") or [], key=lambda g: g.get("ordering", 0))
    shots = []
    for number, image in enumerate(gallery, 1):
        ext = image_extension(urllib.parse.urlparse(image["url"]).path)
        name = f"screenshots/{number:02d}.{ext}"
        save_url(image["url"], os.path.join(args.out, name))
        shots.append(
            {
                "file": name,
                "title": image.get("title") or "",
                "description": image.get("description") or "",
                "featured": bool(image.get("featured")),
                "live_url": image["url"],
            }
        )
    icon = ""
    if project.get("icon_url"):
        icon = "icon." + image_extension(urllib.parse.urlparse(project["icon_url"]).path)
        save_url(project["icon_url"], os.path.join(args.out, icon))
    listing = {
        "slug": project["slug"],
        **{key: live[key] for key in ("title", "summary", "categories", "license_id")},
        **{field: live[field] for field in LINK_FIELDS if live[field]},
        "icon": icon,
        "screenshots": shots,
    }
    with open(os.path.join(args.out, "listing.json"), "w", encoding="utf-8") as f:
        json.dump(listing, f, indent=2, ensure_ascii=False)
        f.write("\n")
    with open(os.path.join(args.out, "LISTING.md"), "w", encoding="utf-8") as f:
        f.write(live["body"])
    print(f"Copied {project['slug']} to {args.out}: {len(shots)} screenshot(s)")
    return 0


def save_url(url: str, path: str) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=60) as response, open(path, "wb") as f:
            f.write(response.read())
    except OSError as e:
        raise ListingError(f"couldn't download {url}: {e}") from e


def cmd_submit(args: argparse.Namespace) -> int:
    if not args.confirmed:
        raise ListingError("submit sends the page to reviewers: use --confirmed once approved")
    token = modrinth_token()
    versions = expect(
        modrinth("GET", f"/project/{args.slug}/version", token), 200, doing="reading versions"
    )
    if not versions:
        raise ListingError("the draft has no version yet: run the mod's release first")
    expect(
        modrinth_json("PATCH", f"/project/{args.slug}", token, {"status": "processing"}),
        200,
        204,
        doing="submitting for review",
    )
    print(f"Sent for review: https://modrinth.com/mod/{args.slug}")
    return 0


def issues_row(listing: dict) -> dict:
    """The issues page row for a listing: its anchor, its title, and the GitHub repo its source link names."""
    match = ISSUES_LINK.match(listing.get("issues_url") or "")
    if not match:
        raise ListingError("issues_url must be https://dungeon.training/issues#<anchor>")
    repo = SOURCE_REPO.match(listing.get("source_url") or "")
    if not repo:
        raise ListingError("source_url must be https://github.com/bh679/<repo>: the page links its issues")
    return {"id": match.group(1), "name": listing["title"], "repo": repo.group(1)}


def relay_admin_url() -> str:
    """The relay's admin base URL. It holds the admin secret, so it is never printed or put in an error."""
    try:
        with open(RELAY_ADMIN_URL_FILE, encoding="utf-8") as f:
            url = f.read().strip()
    except OSError as e:
        raise ListingError(f"no relay admin URL at {RELAY_ADMIN_URL_FILE}: see its README.md") from e
    if not url.startswith("https://"):
        raise ListingError(f"{RELAY_ADMIN_URL_FILE} doesn't hold an https URL")
    return url.rstrip("/")


def cmd_issues_add(args: argparse.Namespace) -> int:
    row = issues_row(load_listing(args.dir))
    print(f"Row: {row['name']} (#{row['id']}) -> https://github.com/{row['repo']}/issues")
    if not args.confirmed:
        print("The issues page is public: re-run with --confirmed to add it.")
        return 0
    try:
        status, payload = call(
            "PUT",
            relay_admin_url() + "/issues/projects",
            headers={"Content-Type": "application/json"},
            data=json.dumps(row).encode("utf-8"),
        )
    except ListingError:
        raise ListingError("couldn't reach the relay to add the row") from None
    if status != 200:
        error = payload.get("error") if isinstance(payload, dict) else None
        raise ListingError(f"the relay refused the row (HTTP {status}{', ' + error if error else ''})")
    print(f"Added: https://dungeon.training/issues#{row['id']} (shows within a minute)")
    return 0


def parser() -> argparse.ArgumentParser:
    top = argparse.ArgumentParser(description="Create and edit our mods' store pages.")
    commands = top.add_subparsers(dest="command", required=True)
    commands.add_parser("tokens").set_defaults(run=cmd_tokens)
    check = commands.add_parser("check")
    check.add_argument("dir")
    check.add_argument("--offline", action="store_true", help="don't look up the issues page")
    check.set_defaults(run=cmd_check)
    pull = commands.add_parser("pull")
    pull.add_argument("slug")
    pull.add_argument("--out", required=True)
    pull.set_defaults(run=cmd_pull)
    draft = commands.add_parser("draft")
    draft.add_argument("dir")
    draft.add_argument("--offline", action="store_true", help="don't look up the issues page")
    draft.set_defaults(run=cmd_draft)
    update = commands.add_parser("update")
    update.add_argument("dir")
    update.add_argument("--offline", action="store_true", help="don't look up the issues page")
    update.add_argument("--dry-run", action="store_true")
    update.add_argument("--confirmed", action="store_true")
    update.set_defaults(run=cmd_update)
    submit = commands.add_parser("submit")
    submit.add_argument("slug")
    submit.add_argument("--confirmed", action="store_true")
    submit.set_defaults(run=cmd_submit)
    issues_add = commands.add_parser("issues-add")
    issues_add.add_argument("dir")
    issues_add.add_argument("--confirmed", action="store_true")
    issues_add.set_defaults(run=cmd_issues_add)
    return top


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        return args.run(args)
    except ListingError as e:
        print(f"listing: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
