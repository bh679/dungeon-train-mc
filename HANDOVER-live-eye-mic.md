# Handover: Live Eye mic support

Orchestration branch: `claude/live-eye-mic-support-3f9c17` (no code here; this note only).
Written 2026-10-31.

## The feature

Mic for the Live Eye. Off by default. Icon on the stream preview toggles it and fills green
with the input level. Mic settings live in a titled "Mic" section of the stream settings page,
with one game-vs-mic balance slider. Anyone can send voice to the TV and the live site. Only
approved accounts reach Twitch. TV watchers can mute the whole stream or one streamer; the
stream page lists muted players. Accounts can be banned from mic. Players can report a
streamer, which transcribes the last 10 minutes and the next 10 of that streamer's audio for
review. One open report per reporter per streamer; a second must abandon the first.

## Work and where it sits

### Mod (bh679/dungeon-train-mc). No PRs yet, Gate 2 in-game test pending on each.

| Branch | Scope | HEAD |
|---|---|---|
| `dev/live-eye-mic-capture` | mic capture, 4-ch encode split into two AAC tracks (0 game+mic, 1 game), preview icon with green level fill, settings section, balance slider, claim/heartbeat `mic` fields | b21f77818 (v0.1555.4) |
| `dev/live-eye-tv-audio` | TV playback of the stream audio, mute stream / mute one streamer, muted list on stream page | ab81ea373 |
| `dev/live-report-streamer` | `/livereport` command, 400/409 handling, "Nobody is live" fix, streamer_not_found message in all 22 locales | 3d6244971 (v0.1555.3) |

Worktrees: `.claude/worktrees/nervous-chatterjee-4681c3`, `xenodochial-lamport-75adea`,
`jolly-williams-c5904c`. Gate 2 screenshots: `nervous-chatterjee-4681c3/test-results/gate2-live-eye-mic-v3-*.png`.

### Relay (bh679/dp-relay). MERGED 2026-11-04 (Gate 3 approved by Brennan): #978 cabd5d87,
### #975 938ec6a4, #974 3b3824f3, in that order. DEPLOYED 2026-11-04 10:52 UTC as release
### 0.289.6-1cdaf1b7 (rollback target 0.289.5-b85e83f0); dt-live-reports worker started and
### pm2-saved, transcribing with OpenAI whisper-1; the seven changed public/ live page files
### were scp'd to htdocs and sha-verified (deploy-relay.sh does not ship public/).
### Post-deploy checks on a real stream (xiamo_maomao, old client, 11:05 UTC): Live page mute
### stream / mute streamer / muted list / Clear all work; mic badge hidden as expected (mic off);
### public-status carries streamerKey and no uuid; Twitch mirror pushing to brennanhattongames
### with -map 0:a:0 -c:a copy, 0 restarts. Web chat commands tested live as Brennan (Discord
### login in Claude's browser pane): /micban AshSeven 1, /micunban, /mictwitch on, off all
### answered "Only you" privately, nothing in the public feed, and the ban + approval rows show
### lifted/revoked on the admin routes. Name lookup is leaderboard player_names (case-insensitive);
### Brennan's own account is not in it. Discord !micban/!micunban/!mictwitch tested by Brennan in
### #live-eye at 12:00-12:03 UTC (1-day ban, permanent ban, Twitch on/off): rows recorded and all
### lifted/revoked. Discord commands only work in the configured Live chat channels (main + second);
### no dev channel on prod. Still untested: the explorer Voice page.

| PR | Scope |
|---|---|
| #978 | mic policy: bans, Twitch approvals, `mic:{allowed,twitch}` on claim, `micOn` heartbeat, Twitch worker maps track 0 if approved else track 1, public `streamerKey` (hash, never uuid), `!micban/!micunban/!mictwitch` on Twitch/Discord and `/micban /micunban /mictwitch` on web, explorer page Live Feed -> Voice (#/live-mic), `LIVE_MIC_ENABLED` kill switch |
| #975 | reports: `POST /<cap>/live/report`, pm2 worker `dt-live-reports` (scripts/live-report-worker.js), ffmpeg track-0 extract to 16 kHz mono mp3, transcribe.js (openai whisper-1 / local whisper.cpp / none), reports review page |
| #974 | live page: mute stream / mute streamer by `streamerKey`, Dev Feed and slider fixes, overflow fix |

Wire contract between mod and relay is in the PR #978 description. Packet changes on the mod
side mean a `PROTOCOL_VERSION` bump.

Order across repos: deploy the relay first. `dev/live-eye-mic-capture` sends `mic` on claim and
`micOn` on heartbeat, which only #978 reads; `dev/live-report-streamer` posts to the endpoint
#975 adds. Shipping either mod branch before its relay PR is live means the feature silently
does nothing.

2026-11-04: all six branches re-merged with main (relay main 0.289.4, DT main v0.1645.0).
Relay merge commits 468dc18c (#978), 43e9d3ca (#975), 612f20ad (#974); full suite green on
each, all three MERGEABLE. Mod merge commits 0e77c226 (capture), 053ff7e2 (tv-audio),
e60d32e2 (report); each builds to dungeontrain-0.1645.0.jar and passes ./gradlew test.
The mod branches now sit in scratchpad worktrees of the orchestrator session, not in the
original session worktrees (those are detached). None of the mod branches has a changelog
entry yet; write one as unreleased/<id>.json at Gate 3 (per #2444). Relay test flakes seen
under load: deploy-relay.test.js timing and community.test.js fetchMee6; rerun rather than
read as failures.

### Prod box state (checked 2026-10-29)

- ffmpeg at /usr/bin/ffmpeg. No whisper.cpp, no ggml models, no `LIVE_REPORT_*` env.
- `OPENAI_API_KEY` is set, so transcription defaults to OpenAI whisper-1 (about $0.12 USD per report).
  Confirm that key is billed; moderation moved to Kimi so it is unverified. `LIVE_REPORT_STT_PROVIDER=none` opts out.
- `ANTHROPIC_INLINE_ENABLED=false`, so the Claude flag summary stays off.
- After deploying #975 start the worker once:
  `pm2 startOrReload ecosystem.config.js --only dt-live-reports && pm2 save`.
- Box is 2 vCPU and CPU-bound with two streams; do not run local whisper there.

## Mod branch state after Gate 2 (2026-11-04 evening)

Gate 2 reports: `<scratchpad>/dt-{tv,capture,report}/test-results/GATE2-*.md` (scratchpad of the
orchestrator session; copy them into the repo's test-results/ at PR time).

- `dev/live-eye-tv-audio` @ 940de747c: Gate 2 7/7 pass incl. Sable; then the TV sound stutter
  fixed (ring cushion 40 ms -> 500 ms, MAX_LAG 6 s, 60 ms OpenAL chunks, no speaker restart on
  segment waits; 0 underruns after vs 640/min before; sound now ~0.75 s behind picture). Also
  on this branch today: HUD mic bar, "TVs on the train" toggle, placed TVs silent on own
  stream, speaker range 9..18 blocks by TV grid size.
- `dev/live-eye-mic-capture` @ b4502ca5a: Gate 2 found and fixed: mid-stream ban honoured,
  push-to-talk micOn, Microphone default off, device label cut. Brennan's real-voice test
  (local relay, MacBook mic) proved the two-track chain but voice was 10-15 dB too quiet and
  the inventory-only switch was unfindable. Fixed: AGC (target -20 dBFS, +24 dB max), ONE mic
  switch (inventory button == Microphone option, saved; stream starts in that state, default
  off; turning on mid-stream without a mic track = next stream), balance default 75%, toggle
  key K, 8 s on-screen hint, toggle/device logging. Re-tested with Brennan's voice 22:36
  local time: stream started with the mic open, switch worked from inventory and from the
  options both ways (logged), voice at -13..-24 dBFS mic-only against game -17..-19, i.e. at
  parity or above. Voice test PASSED. Wavs in dt-capture/test-results/voice-*-take3.wav.
  Local-harness note: the dev config's segmentSeconds=2 without the quicker_streams claim makes
  the relay 429 most heartbeats; harmless, prod shows 0.
- `dev/live-report-streamer` @ e60d32e2: Gate 2 9/9 pass.
- Still none has a changelog entry or PR. The tv-audio and capture branches both touch
  LiveFeedClient.Status (mic field); merge capture first.

## Tested

End-to-end report: `/Users/brennanhatton/Projects/dp-relay/.claude/worktrees/server-route-modules-49e3c2/TEST-REPORT-live-mic-e2e.md`.
Relay-side steps A to G passed, including Twitch track gating against the dungeontrainmc
channel (approve to voice about 4 s, revoke about 2 s). Five bugs found there are all fixed on
the branches above.

Not yet tested: in-game flows on the three mod branches, a real microphone (step H), and a
real transcription run. In-game driving failed because the Claude app lacks macOS
Accessibility and Screen Recording access.

## Open decisions for Brennan

1. Gate 3 on #978, #975, #974 and a deploy window with no stream live.
2. Gate 2 in-game runs, then PRs, for the three mod branches.
3. Which accounts get Twitch voice approval (`/mictwitch <name> approve` on the web chat).
4. Transcription provider: keep OpenAI default, or set none.

## Cost

Mic adds 96 kbps (about 43 MB/h) per streaming client. Relay CPU cost is negligible (audio
is copied, not re-encoded). Transcription is the only paid piece, per report.

## Shipped (2026-11-05)

- The three mod branches were merged into one integration branch `dev/live-eye-mic` (one speaker at a
  time, placed-TV range 4.5 + 1.5 per TV of grid width, grid off-state fix, HUD mic bar, one unified mic
  switch, AGC, balance 75%). Brennan play-tested it. PR #2490 squash-merged as acf685f34; main bumped
  to 0.1665.0. Changelog entry `live-eye-voice` (major, shot 1 photo, AshSeven Everything Burrito
  call-out with a Discord `<t:>` token).
- Kid mode: a streamer's voice reaches a Kid mode client only when approved for kids. Relay PR #1020
  (ddb5f428, 0.289.13): `live_mic_kids_approvals`, `/mickids <name> on|off` (web) and `!mickids`
  (Twitch/Discord), status `mic.kids`, admin route `/live/mic/kids-approvals`, explorer Voice page
  section, renditions copy both audio tracks. Mod reads `mic.kids` and plays the game-only track
  otherwise (`client/live/LiveSoundTrack`), failing closed on a relay that says nothing.
- Relay PR #1021 (847f5c90): Discord `<t:UNIX:STYLE>` tokens render as local dates on the update page
  and UTC dates in the RSS feed.
- Relay 0.289.13 (release 0.289.13-847f5c90) deployed 2026-11-05 04:56Z mid-stream on Brennan's say-so
  (rollback 0.289.12-5488503c); update-page and live chat-commands public files scp'd to htdocs. Kids
  approve/revoke verified on prod against AshSeven.
- Release: v0.1665.0 was dispatched then cancelled before the tag (main had moved to 0.1666.0 with
  #2491 fair play tweak); v0.1666.0 dispatched with the rendered notes, run 41971147318.
- Deploy order lesson: a release must follow the relay deploy; Kid mode hears no voice until the relay
  carries `mic.kids`.
