# FFmpeg mirror

DT's own copy of FFmpeg, for players whose network can't reach Vista's default download source.
Vista on Windows downloads from `gyan.dev`. When that fails (it has failed for a player in China),
there is no ffmpeg, every TV stays on the "needs FFmpeg" card, and Vista's install prompt comes back
on every launch.

`client/live/FfmpegInstaller` then fetches
`https://brennan.games/dungeontrain/tools/ffmpeg/windows-x64.zip`. It accepts only the exact size and
SHA-256 pinned in `client/live/FfmpegMirror`, and unpacks `ffmpeg.exe` and `ffprobe.exe` into Vista's
`vista_ffmpeg_bin/`.

## Build

```bash
scripts/live/ffmpeg-mirror/build.sh
```

This downloads Gyan's FFmpeg essentials build at the pinned version, the same build Vista installs,
and checks its pinned upstream SHA-256. It keeps only the two binaries, the licence and a
`SOURCE.txt` with the source links GPL redistribution needs. It writes
`build/ffmpeg-mirror/windows-x64.zip` with fixed timestamps, so a rebuild gives the same bytes.

**Why a GPL build:** the Live Eye encodes with `libx264`, which LGPL builds leave out.

## Update

1. Change `VERSION` and `UPSTREAM_SHA256` in `build.sh`. Run it once with `UPSTREAM_SHA256=""` to
   see the new upstream hash.
2. Copy the printed `sha256` and `size` into `FfmpegMirror.WINDOWS_X64`.
3. Deploy the zip (below) **before** the release that pins it ships. A client with the new pin and
   the old file on the server refuses the download.

## Deploy

Apache on the brennan.games box serves `/dungeontrain/` straight from htdocs (no proxy), so the zip
just goes there. dp-relay's `deploy-public-pages.sh` only adds or overwrites files and never deletes,
so relay deploys leave it alone. No Apache config change is needed.

```bash
unset SSH_AUTH_SOCK
D=/opt/bitnami/apache/htdocs/dungeontrain/tools/ffmpeg
ssh -i ~/.ssh/claude-lightsail bitnami@brennan.games "mkdir -p $D"
scp -i ~/.ssh/claude-lightsail build/ffmpeg-mirror/windows-x64.zip bitnami@brennan.games:$D/windows-x64.zip.part
ssh -i ~/.ssh/claude-lightsail bitnami@brennan.games "cd $D && sha256sum windows-x64.zip.part && mv windows-x64.zip.part windows-x64.zip"
```

Empty `index.html` files in `tools/` and `tools/ffmpeg/` hide Apache's folder listing.

Check it from anywhere:

```bash
curl -sI https://brennan.games/dungeontrain/tools/ffmpeg/windows-x64.zip
```

The response should be `200` with `Content-Length` equal to the pinned size.

First deployed 2026-10-25: FFmpeg 9.0.2, sha256 `519a4130…6fab`, 77,388,220 bytes, verified over HTTPS.
