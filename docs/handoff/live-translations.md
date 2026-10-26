# Handoff: Live Translations (community reward)

Status as of 2026-10-26. Branch `dev/live-translations` (mod, this repo). Read this first if you are
picking the feature up in a new session.

## What it is

Live Eye chat lines translated on the dp-relay by Claude Haiku (`claude-haiku-4-5`) into the
languages the room is actually read in, shown in game under / instead of / above the original.
Sold as a **Community Reward `live_translation`: A$90 for 3 months** (every other reward is 1 month).
Nothing changes on the wire while the reward is off.

Approved Gate 1 plan (full detail, both repos):
`docs/handoff/live-translations-plan.md` (copy of `~/.claude/plans/lets-setup-hauki-but-dapper-hellman.md`). Part A = relay, Part B = mod.

## Where it stands

| Piece | State |
|---|---|
| Mod (Part B) | **Built, Gate 2 tested against a stub relay, NOT yet approved by Brennan.** No PR opened yet. |
| Relay (Part A) | **Not started.** Must ship and deploy before the mod is useful. |
| Reward on sale | Not open. `REWARDS_OPEN` on the box gains `live_translation` only after the mod release. |
| Changelog entry | Not written yet (Gate 3 step, via `scripts/release-notes/append-entry.py`). |

### Next steps, in order

1. Brennan approves Gate 2 for the mod (screenshots, gitignored so local only: `.claude/worktrees/blissful-benz-4307f1/test-results/gate2-live-translations-*-2026-10.png`; re-create with the stub steps below).
2. Build Part A in a dp-relay worktree off `origin/main` (the local `~/Projects/dp-relay` checkout is a
   stale detached HEAD). Wire contract below must match exactly.
3. Mod PR (Gate 3): changelog entry + tags, CI green, Brennan approves, squash-merge.
4. Relay PR + deploy; then set `LIVE_TRANSLATE_API_KEY` (Brennan creates it in its own Anthropic console
   workspace with a monthly spend limit) and `LIVE_TRANSLATE_ENABLED=true` on the box.
5. After the mod release: `REWARDS_OPEN=quicker_streams,live_translation` on the box.

## Decisions already made (do not re-ask)

- Haiku on the relay, **one call per chat line** (never batch lines: injection isolation). Batching per
  second was considered and rejected (chat averages ~24 lines/hour, peak ~178/hour).
- **Own API key, own switch.** dp-relay#937 (merged + deployed, prod 0.279.3) added
  `ANTHROPIC_INLINE_ENABLED=false` so nothing else on the relay can spend API credits; Brennan's credits
  had expired and all other AI work moved to Claude Routines. Live Translations must NOT reuse that key or
  switch: `LIVE_TRANSLATE_API_KEY` (no fallback to `ANTHROPIC_API_KEY`), `LIVE_TRANSLATE_ENABLED` default
  false, on-air only, daily + monthly character caps that fail closed, its own outbound-meter label.
- Only translate into languages viewers are reading (each poll sends `lang=`), plus the streamer's.
- In-game display default: grey line **under** the original; client setting cycles Under / Translation
  only / Translation first.
- Website Live page also prefers relay translations while the reward is on (browser engine stays as
  fallback).
- Price A$90, **3 months per gift**.
- **Lean Haiku call (2026-10-26, for margin):** ~250-token system prompt (no big glossary, no prompt
  caching) and a strict JSON schema listing **only the requested locales**; monthly cap 1.5M chars.
  At today's volume that is ≈ A$45 of Haiku per 3-month gift (3 reader languages), so ≈ A$43 profit
  on A$90. A ~2k-token cached glossary + fixed 22-locale schema was costed at a loss and rejected.
  Full figures in the plan's Risks / Cost section.
- Injection posture: nonce-tagged `<chat_line_xxxx>` data block, strict per-request JSON schema, then
  post-validate each translation (cleanContent, length ratio, liveModWords.censor +
  censorPatterns + hookGuard.inspectChat); drop on doubt, the original always shows.

## Wire contract (relay must produce exactly this)

- **Poll:** `GET /<CAP>/live/chat?since=N&lang=zh_cn` (mod) and the web read `...&lang=`. `lang` is a
  Minecraft locale code `[a-z]{2,3}_[a-z0-9]{2,4}`. Relay keeps readers per label for ~90 s.
- **Late translations:** top-level `translations: [{"line": <seq>, "tr": {"zh_cn": "...", ...}}]` in both
  game and web read shapes; a new ring seq step (like `reacts`), latest per line since the cursor.
- **Inline:** a message read in the same poll as its translation carries `"tr": {...}` on the message.
- **Status:** public live status gains `rewards: [active ids]` while live (web page uses it).
- **Reward length:** relay `rewards.js` must give `live_translation` **3 x 30 days per gift** (it stacks
  `from = max(now, until)`; the mod's `CommunityRewards.nextFreeMs` mirrors it via `Reward.months()`).
  Also: `IDS`, `PRICE_AUD.live_translation = 90`, `NAMES`, support/thanks pages ("for 3 months"),
  `ui-events.js` `reward_live_translation`.
- Old mods ignore all of this; old relays never send it.

## Mod changes on this branch

- `net/relay/LiveFeedClient`: `chat(base, since, lang)`, `LineTranslations`, `parseTranslations`, `parseTr`
  (keys checked, values cleaned like content, capped at 32), `ChatLine.tr`, `ChatPage.translations`.
- `client/live/LiveChatTranslations` (new, pure): `readerLang`, `pick` (exact then same language),
  `underLine` (`↳ text` / `↳ Name: text`), `replacedLine` (original on hover), `langName`.
- `client/live/LiveChatBridge`: sends reader language, remembers printed lines, renders inline and late
  translations per mode, one-time notice per session, deletion takes the line and its `↳` part.
- `client/live/LiveChatSignatures`: `signPart` / `takeAll`. `client/chat/ChatReactionTracker.replaceLive`
  (in-place redraw keeps reaction chips; vanilla delete leaves a grey marker, so no delete+reprint).
- `config/LiveFeedClientConfig.LiveChatTranslation` + Options / Stream / Chat translations row
  (`ClientOptionsTab`, `DungeonTrainClientOptionsScreen`, `ClientOptionsIcons`).
- Rewards roster: `LiveRewards.LIVE_TRANSLATION`, `CommunityRewards.Reward.LIVE_TRANSLATION` (A$90,
  3 months, Extra group), `RewardIcons` (book).
- 16 lang keys in all 22 locales, provenance stamped; `translation_examples.json` entries.
- Tests: `LiveFeedClientTranslationsParseTest`, `LiveChatTranslationsTest`, plus additions to
  `LiveChatSignaturesTest`, `CommunityRewardsTest`, `ClientOptionsTabTest`.
- Harness: `scripts/live/stub-chat-relay.py` (scenes `inline`, `late`, `named`, `delete`).

## Gate 2 so far

- `./gradlew build` passed; full suite 8,387 tests, 0 failed (after the tab-count and placeholder fixes).
- In-game, zh_cn client wearing a TV against the stub: all three modes, the named under-line, the
  one-time notice and deletion verified; every poll carried `lang=zh_cn`. Screenshots kept locally in this worktree's `test-results/` (gitignored).
- Not covered: the real relay (doesn't exist yet); multiplayer/server (feature is client-only); Sable n/a.

### Re-running the in-game check

```bash
python3 -I scripts/live/stub-chat-relay.py --port 8788
DUNGEONTRAIN_RELAY_BASE_URL=http://127.0.0.1:8788/dev DUNGEONTRAIN_RELAY_LIVE_BASE_URL=http://127.0.0.1:8788/live ./gradlew runClient
curl -X POST 127.0.0.1:8788/scene/late
```

Set the game language to Chinese, join a world, wear a TV (`/item replace entity @s armor.head with
vista:television`). Custom env vars do not reach the client JVM; only the `DUNGEONTRAIN_RELAY_*` ones
are forwarded by `build.gradle`.

## Cost reference

Prod `live_msgs` (14 to 24 Oct 2026): 4,671 lines in ~9.8 days, about 14k lines/month, ~76% from game.
On the lean call: ≈ US$7/10/12 a month for 1/3/5 reader languages, so ≈ A$32/45/54 per 3-month gift
and ≈ A$57/43/34 profit on A$90 after Stripe. Break-even ≈ US$19/month (about twice today's volume);
the 1.5M-char monthly cap stops translating before that. Prices from memory: recheck Haiku pricing
and FX before launch.
