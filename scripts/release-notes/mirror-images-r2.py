#!/usr/bin/env python3
"""Mirror .github/release-notes/images/ to the website's R2 media bucket.

dungeon.training shows each update's photo (changelog.json `image` / `loop`) from
https://media.dungeon.training/release-notes/images/<file> (bucket dt-site-media, Cloudflare edge),
falling back to the raw.githubusercontent.com copy the changelog names. dp-relay's DtRelay.media() maps
one to the other. changelog.json keeps the GitHub URLs: release bodies, Modrinth, CurseForge, Discord and
the social-post builder all read them.

Only new or changed files go up: a file is skipped when the bucket already holds an object whose ETag is
the file's MD5 (each upload is a single PUT, so R2's ETag is the MD5). Nothing in the bucket is deleted.

Settings, from the environment (the same bucket-scoped key dp-relay's site-media deploy uses):
  SITE_MEDIA_BUCKET, SITE_MEDIA_R2_ENDPOINT, SITE_MEDIA_R2_ACCESS_KEY_ID, SITE_MEDIA_R2_SECRET_ACCESS_KEY

Usage:
  python3 scripts/release-notes/mirror-images-r2.py            # upload what is missing or changed
  python3 scripts/release-notes/mirror-images-r2.py --dry-run  # list it, upload nothing
  python3 scripts/release-notes/mirror-images-r2.py --env-file <KEY=VALUE file>

Needs the aws CLI (preinstalled on GitHub's ubuntu runners). Exit 0 = every file is in the bucket.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
IMAGES = REPO / ".github" / "release-notes" / "images"
KEY_PREFIX = "release-notes/images/"
# Same lifetime dp-relay's site-media sync gives the site's own media: a replaced file heals within the hour.
CACHE_CONTROL = "public, max-age=3600"
MIME = {
    "png": "image/png", "webp": "image/webp", "jpg": "image/jpeg", "jpeg": "image/jpeg",
    "gif": "image/gif", "mp4": "video/mp4", "webm": "video/webm",
}
REQUIRED = ("SITE_MEDIA_BUCKET", "SITE_MEDIA_R2_ENDPOINT", "SITE_MEDIA_R2_ACCESS_KEY_ID",
            "SITE_MEDIA_R2_SECRET_ACCESS_KEY")


def read_env_file(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip().strip("'\"")
    return out


def local_files(folder: Path = IMAGES) -> list[tuple[str, Path]]:
    """(bucket key, file) for every media file directly in `folder`, sorted. The site maps flat names only."""
    files = []
    for f in sorted(folder.iterdir()):
        ext = f.suffix.lstrip(".").lower()
        if f.is_file() and ext in MIME and not f.name.startswith("."):
            files.append((KEY_PREFIX + f.name, f))
    return files


def md5(path: Path) -> str:
    h = hashlib.md5()  # noqa: S324 - compared with R2's ETag, not used for security
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def plan(local: list[tuple[str, Path]], remote: dict[str, str]) -> list[tuple[str, Path]]:
    """The files whose key is missing from `remote` ({key: etag}) or holds different bytes."""
    return [(k, f) for k, f in local if remote.get(k) != md5(f)]


def aws(args: list[str], env: dict[str, str]) -> str:
    res = subprocess.run(["aws", *args], env=env, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"aws {args[0]} {args[1]} failed: {res.stderr.strip()[:500]}")
    return res.stdout


def remote_etags(bucket: str, endpoint: str, env: dict[str, str]) -> dict[str, str]:
    out = aws(["s3api", "list-objects-v2", "--bucket", bucket, "--prefix", KEY_PREFIX,
               "--endpoint-url", endpoint, "--output", "json"], env)
    doc = json.loads(out) if out.strip() else {}
    return {o["Key"]: o.get("ETag", "").strip('"') for o in doc.get("Contents", []) or []}


def upload(key: str, path: Path, bucket: str, endpoint: str, env: dict[str, str]) -> None:
    mime = MIME[path.suffix.lstrip(".").lower()]
    aws(["s3api", "put-object", "--bucket", bucket, "--key", key, "--body", str(path),
         "--content-type", mime, "--cache-control", CACHE_CONTROL, "--endpoint-url", endpoint], env)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Mirror release-note images to the website's R2 bucket.")
    p.add_argument("--dry-run", action="store_true", help="List what would upload; upload nothing.")
    p.add_argument("--env-file", type=Path, default=None, help="KEY=VALUE file with the SITE_MEDIA_* settings.")
    a = p.parse_args(argv)

    settings = {**(read_env_file(a.env_file) if a.env_file else {}), **os.environ}
    missing = [k for k in REQUIRED if not settings.get(k)]
    if missing:
        print(f"mirror-images-r2: missing {', '.join(missing)}", file=sys.stderr)
        return 2
    env = {
        **os.environ,
        "AWS_ACCESS_KEY_ID": settings["SITE_MEDIA_R2_ACCESS_KEY_ID"],
        "AWS_SECRET_ACCESS_KEY": settings["SITE_MEDIA_R2_SECRET_ACCESS_KEY"],
        "AWS_DEFAULT_REGION": "auto",
        # aws CLI 2.23+ adds CRC checksums R2 does not accept on every call; only send them when required.
        "AWS_REQUEST_CHECKSUM_CALCULATION": "when_required",
        "AWS_RESPONSE_CHECKSUM_VALIDATION": "when_required",
    }
    bucket, endpoint = settings["SITE_MEDIA_BUCKET"], settings["SITE_MEDIA_R2_ENDPOINT"]

    local = local_files()
    todo = plan(local, remote_etags(bucket, endpoint, env))
    print(f"mirror-images-r2: {len(local)} file(s); {len(todo)} to upload")
    failed = 0
    for key, path in todo:
        if a.dry_run:
            print(f"  would upload {key}")
            continue
        try:
            upload(key, path, bucket, endpoint, env)
            print(f"  uploaded {key}")
        except RuntimeError as err:
            failed += 1
            print(f"  FAILED {key}: {err}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
