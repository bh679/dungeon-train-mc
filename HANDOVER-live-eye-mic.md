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

### Relay (bh679/dp-relay). All three PRs open, merge in this order, deploy in a no-stream window.

| PR | Scope |
|---|---|
| #978 | mic policy: bans, Twitch approvals, `mic:{allowed,twitch}` on claim, `micOn` heartbeat, Twitch worker maps track 0 if approved else track 1, public `streamerKey` (hash, never uuid), `!micban/!micunban/!mictwitch` on Twitch/Discord and `/micban /micunban /mictwitch` on web, explorer page Live Feed -> Voice (#/live-mic), `LIVE_MIC_ENABLED` kill switch |
| #975 | reports: `POST /<cap>/live/report`, pm2 worker `dt-live-reports` (scripts/live-report-worker.js), ffmpeg track-0 extract to 16 kHz mono mp3, transcribe.js (openai whisper-1 / local whisper.cpp / none), reports review page |
| #974 | live page: mute stream / mute streamer by `streamerKey`, Dev Feed and slider fixes, overflow fix |

Wire contract between mod and relay is in the PR #978 description. Packet changes on the mod
side mean a `PROTOCOL_VERSION` bump.

### Prod box state (checked 2026-10-29)

- ffmpeg at /usr/bin/ffmpeg. No whisper.cpp, no ggml models, no `LIVE_REPORT_*` env.
- `OPENAI_API_KEY` is set, so transcription defaults to OpenAI whisper-1 (about $0.12 USD per report).
  Confirm that key is billed; moderation moved to Kimi so it is unverified. `LIVE_REPORT_STT_PROVIDER=none` opts out.
- `ANTHROPIC_INLINE_ENABLED=false`, so the Claude flag summary stays off.
- After deploying #975 start the worker once:
  `pm2 startOrReload ecosystem.config.js --only dt-live-reports && pm2 save`.
- Box is 2 vCPU and CPU-bound with two streams; do not run local whisper there.

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
