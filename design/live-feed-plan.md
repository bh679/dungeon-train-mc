# Live Feed — design plan

Realtime video from one player's world shown on screens in other players' worlds, using
Vista TV for the client-side screen and decoder, Cloudflare R2 for the bytes, and the relay
only as a lease broker. Drafted 2026-10-09 from the Vista source (`MehVahdJukaar/cameramod`)
and measured account usage; nothing here is built yet.

Sizing target: **100 concurrent viewers, 24 h/day**. Expected real load: 2–10 viewers.
Segment length: **10 s** (Brennan's call; viewer delay ≈ 10–20 s).

---

## 1. Why this shape

- Vista's stock Wave Gate cannot play live video: `MediaCacheManager.getOrDownload` downloads
  the whole file before FFmpeg runs. But Vista already (a) downloads an FFmpeg + ffprobe binary
  to every client at runtime (`FFmpegManager`, sources in `vista_ffmpeg_sources.json` — all
  three builds include libx264), (b) decodes via `ffmpeg -i <path> -f image2pipe -vcodec
  rawvideo -pix_fmt rgb24 -` into a texture, and (c) exposes a seam for foreign feeds:
  a block entity implementing `IBroadcastSource` returns an `IVideoSource`, and a Hollow
  Cassette linked to that block makes any Vista TV show it. No mixin into Vista is required.
- Every viewer is in a different world from the recorder (1 game = 1 world), so the transport
  must be cross-world. HLS over object storage is the only transport the stock FFmpeg binary
  can both produce and consume with no new client dependency.
- R2 egress is free, so viewer count costs nothing in bytes. Cost is per *operation*, which
  10 s segments keep tiny. The relay box has ~2.1 TB/mo of spare transfer — enough for ~2
  always-on viewers at 720p, nowhere near 100 — so video bytes never touch Lightsail.

## 2. Architecture

```
recorder client ──PUT seg/playlist (signed URL)──▶ R2 bucket dt-live
      │                                                 │
      │ lease (1)                                       │ GET via live.dt.brennan.games
      ▼                                                 ▼  (Cloudflare cache in front)
   relay ◀──lease (N), playlist URL────────── viewer clients ──▶ Vista TV in viewer carriage
```

### 2.1 Dungeon Train mod

| Piece | What it does |
|---|---|
| **Relay Antenna block** (`block/LiveAntennaBlock` + BE) | Implements Vista `IBroadcastSource`. `getBroadcastVideo()` returns `LiveFeedSource`. Holds the channel id; registered with Vista's `BroadcastManager` via `ensureLinked` on place/load. |
| **`LiveFeedSource`** (client, implements Vista `IVideoSource`) | Launches Vista's `FFmpeg` with `-i https://live.dt.brennan.games/<channel>/live.m3u8 -live_start_index -1 -f image2pipe -vcodec rawvideo -pix_fmt rgb24 -`, feeds frames into a texture the way `FFmpegWebTexture` does. One process per viewing TV (Vista's own behaviour). Shows Vista's bars/noise VCs when offline. |
| **Recorder** (client) | Renders a 1280×720 (configurable) off-screen target from the recorder perspective, `glReadPixels` → pipes rgb24 into the same FFmpeg binary: `-c:v libx264 -preset veryfast -tune zerolatency -g 300 -f hls -hls_time 10 -hls_list_size 6 -hls_flags delete_segments+independent_segments`. A watcher thread PUTs each closed segment and the rewritten playlist to R2 with signed URLs from the relay. Perspective source (entity/mob/player) is a later decision — the capture path takes any camera entity. |
| **Viewer carriage** | New drifting-style carriage template: Vista TV wall + antenna + linked Hollow Cassette, spawned only while the world holds a viewer lease. Antenna channel is stamped from the lease. |
| **Leases** | Recorder lease (cap 1) and viewer leases (cap N, default 10, server-side cap 100) with heartbeat; expire on world close / heartbeat loss. Same relay vocabulary as shared-carriage leases. |
| **Config / fair play** | `live.enabled`, `live.segmentSeconds` (default 10), `live.recordResolution`, `live.viewerCap`. Vista + Moonlight become `(required)` deps on both platforms; neither is bundled (Supplementaries Team License §2.1 allows integration, §5 forbids bundling). |

### 2.2 Relay (dp-relay)

| Endpoint | Role |
|---|---|
| `POST /live/lease/recorder` | Grant/refresh the single recorder lease; returns channel id + S3 presigned PUT URL template (short TTL, path-scoped). |
| `POST /live/lease/viewer` | Grant/refresh a viewer lease if under cap; returns playlist URL. |
| `DELETE /live/lease/*`, heartbeat on both | Expiry sweep every 30 s. |
| `GET /live/status` | Public: is anyone live, viewer count (for Discord presence / menu). |
| Health | `live: {recorder, viewers, cap}` block. |

No media byte passes through node. Signing is HMAC over a template; ~1 request per 10 s from
the recorder, ~1 per minute per viewer.

### 2.3 Cloudflare

- New bucket **`dt-live`** (keeps screenshot/litestream buckets separate for analytics).
- **Custom domain `live.dt.brennan.games`** — the `dt.brennan.games` zone is already on
  Cloudflare (brennan.games itself is Route 53), so an R2 custom domain is possible with no
  DNS migration. Cache rule: `*.ts`/`*.m4s` cache 1 day; `live.m3u8` cache 2 s. This makes
  origin reads ≈ 2 per 10 s *total*, not per viewer.
- Object lifecycle: delete anything older than 1 day (belt-and-braces behind FFmpeg's
  `delete_segments`).
- R2 API token scoped to `dt-live` only, PUT/DELETE; stored in the relay `.env`.

## 3. Costs

Measured baseline (last 30 days, Cloudflare GraphQL + box counters): R2 writes 0.40 M of
1 M free, reads ~150 of 10 M free, storage 20.7 GB (already ~$0.16/mo over the 10 GB free),
Lightsail egress ~855 GB of 3 TB.

Assumptions: 720p @ 1.5 Mbit/s (675 MB per viewer-hour), 10 s segments, playlist rewritten
each segment. Prices: writes $4.50/M, reads $0.36/M, egress $0, storage $0.015/GB-mo.

| Line | Expected (1 recorder 24 h, 10 viewers 24 h) | Planned ceiling (100 viewers 24 h) |
|---|---|---|
| R2 writes | +0.52 M → 0.92 M total, under 1 M | same | 
| R2 reads, no cache | 5.2 M, under 10 M | 51.8 M → **~$15/mo** |
| R2 reads, cached domain | ≈0 | ≈0.5 M → **$0** |
| R2 egress | 4.9 TB, free | 48.6 TB, free |
| R2 storage | ~10 MB rolling, $0 | same |
| Lightsail | a few MB of JSON, $0 | same |
| Cloudflare plan | Free | Free (R2-origin video is exempt from the old CDN video restriction) |

**Expected: $0/month on top of today's bill.** Writes land at ~0.92 M, so if the screenshot
or litestream pipelines grow ~20 % the recorder tips the account into billing at pennies
(each extra 100 k writes = $0.45). **Ceiling: $0 with the cached custom domain, ~$15 without.**
Ship the custom domain in phase 1 — it is the only thing standing between $0 and $15, and it
needs no DNS work.

Not on the bill but real: the recorder spends ~1 CPU core on libx264 at 720p; each viewing TV
runs one FFmpeg process on that viewer's machine (Vista's existing model).

## 4. Phases

1. **Spike (1 day):** dev client, Vista installed, hand-written antenna BE returning a
   `LiveFeedSource` pointed at a hand-uploaded test playlist on R2. Proves the
   `IBroadcastSource` seam accepts a foreign BE and that FFmpeg reloads a live playlist from R2.
2. **Recorder (2–3 days):** off-screen capture → FFmpeg HLS → signed PUT. Debug perspective =
   the local player. Verify segments arrive on a 10 s cadence and the spike viewer plays them.
3. **Relay leases + bucket/domain (1–2 days):** endpoints, caps, heartbeat, presigning,
   `dt-live` bucket, `live.dt.brennan.games`, cache + lifecycle rules.
4. **Viewer carriage + lease plumbing in DT (2 days):** template, spawn gating, antenna stamp,
   menu/Discord "someone is live" surface.
5. **Gate 2 soak:** 1 recorder + 3 headless viewers for 24 h; read Cloudflare analytics the
   next day and confirm writes ≈ 17 k/day, origin reads ≈ 17 k/day, egress per viewer ≈ 16 GB.
6. **Recorder perspective decision** (custom mob vs player) — after the above works.

## 5. Risks

- **Seam acceptance.** `IBroadcastLocation` codec is written for Vista's own BEs; the spike
  settles whether a DT BE round-trips through save/sync. Fallback: a mixin on
  `IVideoSource.create` keyed on a DT data component (small, but a Vista-version coupling).
- **Vista version pin.** `IBroadcastSource`/`IVideoSource` live in Vista's `common` package,
  not a declared API. Pin like other siblings (`vista_version`/`vista_min_version`), add a
  compile-time check in CI.
- **FFmpeg download reliability.** Vista pulls binaries from third-party hosts at first use;
  a viewer without them sees Vista's "downloading" screen. Nothing for DT to do beyond
  surfacing it.
- **Latency.** 10 s segments ≈ 10–20 s behind. Acceptable for spectating; wrong for voice-synced
  play. If that ever matters, the earlier JPEG-snapshot design plugs into the same antenna.
- **Stuck recorder lease.** A recorder that never releases writes ~1.5 M/mo; heartbeat expiry
  (60 s) bounds it. Alert if `live.recorder` has been held > 24 h.
- **Write headroom is thin** (80 k/mo). Option if it bites: write the playlist only when the
  segment list changes (always, at 10 s) — no; instead drop the ladder's per-part overhead or
  raise litestream `sync-interval` to 120 s (halves its 0.2 M). Decide only if billed.
- **Licence.** Vista may not be bundled or jarJar'd; must come from Modrinth/CurseForge. DT
  must not "compete" with Vista — a feed source for Vista TVs is complementary.

## 6. Open decisions for Brennan

- Recorder perspective (player camera vs new mob) — deferred by design.
- Viewer cap default (10 proposed) and whether viewing needs a Support/backer gate.
- Whether "someone is live" appears in the main menu, Discord presence, or both.
