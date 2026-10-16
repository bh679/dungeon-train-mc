# Plan: stop Live Feed stream drops caused by missing segments

Written 2026-10-14 by the investigation session (branch `claude/stream-drops-investigation-1485c3`,
no code changed there). Implementer: follow the project gates (Gate 1 plan → Gate 2 test → Gate 3
merge) in a fresh worktree branch `dev/live-upload-missing-segments`.

## The bug (diagnosed, confirmed against R2)

Streamer logs from the 2026-10-14 stream (session `mv7qrn21-a086fa`) show segment PUTs to R2 and
presign POSTs to the relay failing with Java HttpClient transport errors, status 0:
`java.io.IOException: HTTP/1.1 header parser received no bytes`, occasionally
`HttpTimeoutException` and one `SSLHandshakeException (protocol_version)`. That is a pooled
keep-alive socket closed by the far end; Java HttpClient does not retry non-idempotent PUT/POST on it.
Failures come in bursts (flaky home network). Probing R2 for every segment of the session:
95 of segments 1–514 and 20 of 515–580 are 404.

Two defects in `src/main/java/games/brennan/dungeontrain/client/live/LiveUploader.java` turn a
lost PUT into a viewer crash:

1. `pass()` still PUTs `live.m3u8` when a segment PUT in the same pass failed (`allOk=false` only
   stops `lastPlaylistMtime` advancing; see the "playlist upload → 200 (retry in 3 s)" warnings).
   The public playlist therefore names `.ts` files that 404. The website player
   (`dp-relay public/dungeontrain/live-player.js`) plays the SOURCE playlist with hls.js, which
   goes fatal on a missing fragment and retries on a 5/10/20/40/60 s ladder — the "stream crashed
   5 times in 10 mins" viewers reported. TVs (`LiveHlsSession`, ffmpeg) stall and restart after 20 s.
   The class Javadoc already states the invariant ("a playlist must never name a segment that is
   not there yet") — the code breaks it.
2. A segment is retried once, 3 s later, then dropped forever (`failures < 2`). Transport
   failures of an idempotent presigned PUT deserve an immediate retry on a fresh connection.

The relay's r640 rendition (`scripts/live-renditions-worker.js`) renumbers and has no gaps; the
relay and R2 are healthy. No relay change is needed.

## Changes (all client side, mod only)

### A. Never publish a playlist that names a missing segment
In `LiveUploader.pass()`:
- Build the playlist text to upload from the local file, **dropping every segment line (and its
  preceding `#EXTINF` line) for a segment not in `uploaded`**. Add a `#EXT-X-DISCONTINUITY` tag
  where a segment was dropped so hls.js/ffmpeg resync cleanly. Put this rewrite in `LivePlaylist`
  as a pure static method, e.g. `String withoutMissing(String text, Set<String> present)`, keeping
  `#EXT-X-MEDIA-SEQUENCE` and all other tags intact.
- Upload the rewritten text with `HttpRequest.BodyPublishers.ofString` (add an overload of
  `LiveFeedClient.upload` taking bytes/String; the signed headers are unchanged — Content-Type and
  Cache-Control are what the relay signs, see `LiveFeedClient` Javadoc).
- Only skip the playlist upload when *nothing* new is on R2; otherwise publish the filtered
  playlist so viewers keep getting fresh segments even while one is being retried.
- Keep `lastPlaylistMtime` semantics: advance it only when every listed segment is uploaded
  (or permanently skipped), so retries keep happening for pending ones.

### B. Retry transport failures properly
- In `LiveFeedClient.upload`, on a `Result` with status 0 whose error is an `IOException`
  (incl. "header parser received no bytes") or `HttpTimeoutException`, retry up to 2 more times
  immediately before returning. Keep a 4xx/5xx as a single failure (a bad payload stays bad).
- Raise the give-up threshold in `LiveUploader` from 2 to 4 passes, and keep the 3 s back-off
  between passes (the memory note "a failed PUT must back off (3 s) or the uploader presigns twice
  a second" still applies).
- Build the `STORAGE` and `RELAY` clients so idle pooled sockets are not reused after a few
  seconds: set `System.setProperty("jdk.httpclient.keepalive.timeout", "5")` before the static
  clients are created (it is read once at client construction; document that in a comment), or
  create the storage client per upload pass. Prefer the property — cheaper than a TLS handshake
  per segment.

### C. Logging
- Log at INFO once per skipped segment ("live segment X skipped after N failed uploads") and keep
  the WARN per failure, so a sent log shows the lost-segment count without debug level.
- Add the skipped count to `LivePerfStats` `[live]` perf line if a slot exists (optional).

## Files
- `src/main/java/games/brennan/dungeontrain/client/live/LiveUploader.java` (A, B, C)
- `src/main/java/games/brennan/dungeontrain/client/live/LivePlaylist.java` (A: filter method)
- `src/main/java/games/brennan/dungeontrain/net/relay/LiveFeedClient.java` (B: retry, String
  upload overload, keepalive property)
- `src/test/java/games/brennan/dungeontrain/client/live/LivePlaylistTest.java` (new cases)
- new `src/test/java/games/brennan/dungeontrain/client/live/LiveUploaderTest.java` if `pass()` can
  be driven with a stubbed `LiveFeedClient` (it is static today — a small seam/interface for
  presign+upload is acceptable; keep the diff focused)

## Tests
- `LivePlaylistTest`: filtering drops the segment and its EXTINF, inserts DISCONTINUITY, keeps
  MEDIA-SEQUENCE/ENDLIST, handles all-present and all-missing.
- Uploader: a failed segment → playlist uploaded WITHOUT it; it reappears after a later success;
  transport error retried immediately; 403 still cuts off.
- `./gradlew build` and `./gradlew test` clean.
- Gate 2 manual: dev client stream (see memory `project_live_feed_vista_hls_r2` for the harness),
  then simulate failures (e.g. block the R2 host in `/etc/hosts` for ~15 s mid-stream) and verify
  the published `live.m3u8` on R2 never names a 404 segment:
  `curl -r 0-0 -o /dev/null -w '%{http_code}' $BASE/main/<session>/segNNNNN.ts`.

## Not in scope
- Encoder CPU (86–101% at 1920x1080@20 on this streamer) — separate board item if wanted.
- Relay / renditions worker / website player — unchanged.

## Mod-impact check
No new deps, mixins, registrations, packets or worldgen. Client-only networking behaviour change.

## Changelog note (player-facing, Gate 3)
"Live Feed: a shaky connection on the streamer's side no longer makes the stream crash for
viewers — a lost 10-second piece is skipped instead of taking the whole stream down."
Tags: the implementer picks from `append-entry.py --tag-guide` (likely the Live Feed topical tag).
