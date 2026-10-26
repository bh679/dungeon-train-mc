# Handover: Live Eye on TikTok LIVE

Status as of 2026-10-27. Nothing is merged or deployed yet.

## Goal

Mirror the Live Eye feed onto the official TikTok account **@dungeontrain**, alongside the existing
Twitch mirror. Official account only: no per-player TikTok, no TikTok chat bridge.

## Why it works the way it does

TikTok gives an RTMP stream key only to accounts with LIVE access, normally 1000+ followers.
@dungeontrain does not have that yet, so the relay cannot push to TikTok the way the `dt-twitch`
worker pushes to Twitch. The interim route:

1. **Streamlabs Desktop** on Brennan's Mac holds the TikTok login. It can go live on TikTok without a
   key once the account has LIVE access, and small accounts can apply for that inside Streamlabs.
2. A **bridge** on the Mac (a launchd agent) polls the relay's public live status every 15 s and
   drives Streamlabs over its local Remote Control API (WebSocket `127.0.0.1:59650`, token). Feed on:
   launch Streamlabs if closed, select scene `Live Feed`, point media source `Live Feed` at the
   playlist, Go Live. Takeover: re-point the source only. Feed dark or stopped for 30 s: stop.
3. The bridge reports its state to the relay every poll. The relay shows the TikTok link only while
   the last report is `live`, under 45 s old, and about the playlist that is on air. A closed or
   sleeping laptop simply stops reporting and the link disappears by itself.

When the account later gets a real stream key, a relay-side push (a `TikTokPush` follower shaped like
`TwitchRestream` in the relay's `twitch-push.js`) can replace the laptop and report the same way.
Nothing in the game or on the site would need to change.

## Where the work is

| Part | Repo / branch | State |
|---|---|---|
| Relay: mirror reports, `tiktokUrl`, Discord link, the Mac bridge | `bh679/dp-relay` `dev/live-eye-tiktok-mirror`, PR [bh679/dp-relay#925](https://github.com/bh679/dp-relay/pull/925) | CI green, but the PR **conflicts with main** again. Gate 2 report written, not yet approved. |
| Game: "Also live on TikTok" chat line | `bh679/dungeon-train-mc` `dev/live-eye-tiktok-link` (pushed, no PR yet) | Two commits on top of main 0.1484.0. Built in a separate session; its Gate 2 report is in that session. |
| This branch | `claude/live-eye-tiktok-live-cfa93b` | Only this handover document. |

### Relay pieces (PR 925)

- `live-mirrors.js` (new): `POST /<ADMIN_CAP>/live/mirror {platform: 'tiktok', label?, state, playlistUrl?, detail?}`,
  stored in the `settings` table. `liveUrlFor()` is the single reader. `/health.liveMirrors`.
- `live.js`: `tiktokUrl` on `/<CAP>/live/status`, `/live/public-status` and the claim response.
- `live-announce.js`: the Discord go-live post links TikTok in its `Watch:` line under the same rule.
- `LIVE_TIKTOK_URL` in `config-schema.js` and `.env.example`, default `https://www.tiktok.com/@dungeontrain/live`.
- `scripts/tiktok-laptop-bridge/`: `bridge.js`, `streamlabs-client.js`, `run.js`, `install.sh`,
  `uninstall.sh`, `e2e-local.js`, `README.md` (the one-time Streamlabs setup). Node 22+, no dependencies.

### Game piece (`dev/live-eye-tiktok-link`)

- `LiveFeedClient` parses an optional `tiktokUrl` from status and claim; only `https://www.tiktok.com/` URLs.
- `LiveStreamController` says "Also live on TikTok" once per streaming session, the first time a
  status poll carries the URL. `LiveWatchLinks.alsoOnLine`. New lang keys in all locales.

## Verified so far

- Relay unit and route tests for every new piece, all green; full suite green in CI.
- `node scripts/tiktok-laptop-bridge/e2e-local.js`: real relay, real bridge process and a fake
  Streamlabs over a real WebSocket. Walks idle, live, takeover, stop; checks `tiktokUrl` appears and
  goes away; checks no secret reaches the bridge log. Passes.

## Not verified

- **Real Streamlabs Desktop.** It is not installed on this Mac. The client follows Streamlabs' published
  Remote Control API and also unwraps SockJS framing in case the app answers that way.
- **Real TikTok.** Needs LIVE access on @dungeontrain. The open question is whether
  `StreamingService.toggleStreaming()` starts the broadcast directly, or whether Streamlabs opens its
  Go Live window for TikTok every time. If it does, the bridge reports `error` with
  "streamlabs offline after Go Live" and that path needs another look.
- **TikTok is portrait-first.** The 1280x720 feed will show letterboxed. The canvas choice is open.

## Next steps

1. Merge `main` into the relay branch and fix the conflict (last time it was only `package.json`'s
   version: keep the higher number). Approve or adjust the relay Gate 2 report.
2. Review the game branch's Gate 2 report, open its PR, write the changelog entry at Gate 3.
3. Merge the relay PR first, deploy the relay, then merge the game PR. With no bridge reporting,
   the deploy changes nothing visible.
4. Brennan, on the Mac (see the bridge README for detail):
   - install Streamlabs Desktop and sign in;
   - Settings, Stream, add TikTok, and **apply for TikTok LIVE access**;
   - scene `Live Feed` with a network Media Source `Live Feed`;
   - Settings, Remote Control, enable, copy the token;
   - run `scripts/tiktok-laptop-bridge/install.sh` and fill `~/.config/dt-tiktok-bridge/env`.
5. First real run on the dev channel (`CHANNEL=dev`) with a local RTMP sink as the destination, then
   on TikTok once access is granted.

## Traps found along the way

- dp-relay CI runs on a self-hosted runner on this same Mac. Running the full relay suite locally while
  a PR run is in flight starves it, and `deaths-skins.test.js` "a cold carriage resolves in the
  background" then fails. Re-run it; do not change the test.
- `gh run view --log` truncates. The full job log is at
  `gh api repos/bh679/dp-relay/actions/jobs/<jobId>/logs`.
- A stopped stream stays `live: true` with `ended: true` while its tail plays. The bridge treats
  `ended` as dark, otherwise TikTok would sit on a frozen frame until the relay's TTL.
