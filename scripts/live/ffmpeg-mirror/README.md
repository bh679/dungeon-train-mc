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

The zip is served by Apache on the brennan.games box from a folder outside the dp-relay git tree.
That way no binary is committed and relay deploys don't touch it:

- File: `/opt/bitnami/dt-tools/ffmpeg/windows-x64.zip`
- Apache, in the brennan.games vhost, ahead of the dp-relay `ProxyPass` lines:

  ```apache
  ProxyPass /dungeontrain/tools/ !
  Alias /dungeontrain/tools/ /opt/bitnami/dt-tools/
  <Directory /opt/bitnami/dt-tools/>
      Require all granted
      Options -Indexes
  </Directory>
  ```

Check it from anywhere:

```bash
curl -sI https://brennan.games/dungeontrain/tools/ffmpeg/windows-x64.zip
```

The response should be `200` with `Content-Length` equal to the pinned size.
