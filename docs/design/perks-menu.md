# Perks menu: the in-game constellation

Status: design, 2026-10-24. Design only; no code in this PR.

Companions: `perks-tree.md` (the perk list and economy, branch `claude/dungeon-train-perks-tree-df4ac7`),
the web reference at https://brennan.games/dungeontrain/perks/ (dp-relay
`public/dungeontrain/perks/perks.js`, `perks.css`, `index.html`), and the constellation editor
(`docs/design/perks-constellation.html` on the perk-list branch). The foundation code (PerkState synced to
the client, `Perks.rank`) is being built in the sibling session "perks-tier1"; the earning flow
(advancements give unlock points, cosmic debris gives active points) in "perks-earning". Where this doc
leans on either, the assumption is marked **(assumes)**.

Mockups: `perks-menu-mockup/` (SVGs drawn by `generate.py` at GUI-scale-2 proportions).

---

## 1. What the game copies from the web page

The web page is the look. The game menu is the same picture drawn with `GuiGraphics`, so the numbers
below are lifted straight from `perks.js` and should be constants in the Screen, not re-tuned by eye.

| Thing | Web value (800 x 800 editor units, centre 400,400) |
|---|---|
| Star core radius by tier | 1: 3.8, 2: 5.5, 3: 6.5 |
| Halo radius by tier (thin ring, class colour at 35 %) | 1: 7, 2: 9.5, 3: 11 |
| The Engine (root) | core 14, halo 26, dark centre dot 5 |
| Tier-3 centre dot | dark dot radius 2 over a richer (`strong()`) class colour |
| Unlocked, no points | hollow: dark fill, class-colour stroke 1.4 |
| Locked | greyscale, 35 % opacity, links to it grey at 18 % |
| Active ring | 10 slots on radius halo + 1.5, stroke 2.6, gap 0.3 rad, starts at 12 o'clock, clockwise, one slot per point |
| Requirement link | straight line, gradient from A's colour to B's, width 1.2, 55 % |
| Chord (cross-link) | quadratic curve, control point = midpoint pushed 1.18x from centre, gold `#e4b95a` dashed 3 4, width 1, 50 % |
| One-way chord | small gold circle radius 9 on the target end |
| Link states (test mode) | locked edge grey 18 %; both ends active: width 2.8 at 95 %; otherwise dimmed 42 % |
| Perk name | 10.5 px body font under the star at halo + 8, dark outline |
| Class title | Cinzel 17 px, uppercase, spaced 0.12 em, class colour, placed on the line from the Engine through the class's Engine-linked perk, 40 units past the class's farthest star, slid outward until it clears other labels |
| Background | `#070b16` with a soft centre glow, 140 faint twinkling dots |
| Gold accent | `#e4b95a` (selection halo, chords, buttons) |

Class colours (editor seed, the published tree carries its own): Archivist `#c9a7e0`, Lover `#f08aa8`,
Warlock (TBD in editor), Knight `#d9534f`, Tank `#e8956a`, Crafter `#c7b08a`, Speedster `#7fb4f0`,
Builder `#b0b8c8`, Hoarder `#8fc97a`, Trader `#e0c070`. Costs by tier: 1, 5, 10 unlock points.

---

## 2. Layout on a Minecraft screen

### 2.1 Budgets

| Screen | GUI scale | GUI px available | Fit zoom (800 units into height minus 24 px header) |
|---|---|---|---|
| 1920 x 1080 | 2 | 960 x 540 | 0.645 |
| 1920 x 1080 | 3 | 640 x 360 | 0.42 |
| 1280 x 720 | 2 | 640 x 360 | 0.42 |
| 1280 x 720 | 3 | 426 x 240 | 0.27 |

"Zoom" is GUI px per editor unit. At fit zoom a tier-1 star core would be 2.4 GUI px on a 1080p scale-2
screen and 1 GUI px on a 720p scale-3 laptop. That is why the stars do not shrink with the sky (2.3).

### 2.2 The frame

- The sky fills the whole screen. There is no bordered panel and no vanilla dirt background; the dark
  `#070b16` fill plus the centre glow and the twinkle dots is the backdrop, with the world behind it
  blurred out (the normal Screen background blur).
- A 24 px header strip at the top: title left ("Perks"), the two balances right of it (section 5), the
  close X at the far right. Transparent, text only, like the web `.hud` row.
- The details card floats beside the selected star, flipped to whichever side has room, exactly as the web
  `placeCard()` does. It is not a docked side panel: on 426 x 240 a docked panel would take a third of the
  width. Nothing is selected when the menu opens, so the whole sky is visible.
- Default view: the whole sky fitted (zoom from the table), centred on the Engine. Opened from a cosmic
  debris (section 5.3), the default view is the same, not zoomed to anything.

### 2.3 Zoom and star sizes

- Zoom range: fit zoom up to 2.0, in steps of x1.25 per wheel notch, zooming around the cursor. Keys: `+`
  and `-` zoom around the selected star, or the Engine when nothing is selected.
- Pan: left-drag on empty sky, or hold middle mouse; arrow keys pan when no star is selected; the view
  is clamped so at least a quarter of the sky stays on screen.
- Double-click empty sky: back to the fit view.
- **Stars have a floor size.** Core radius drawn = `max(web radius * zoom, floor)` with floors 2.5 / 3.5 /
  4.5 GUI px for tiers 1 / 2 / 3 and 9 for the Engine; the halo and the ring scale with the same ratio.
  Below zoom 1.0 that means every star is drawn at its floor size and the picture reads like the web page
  at a small window: sizes still say tier, positions and links are exact. Above zoom 1.0 the web numbers
  apply unchanged.
- Line widths have a floor of 1 GUI px. Chord dashes are drawn as segments (no dash support in
  `GuiGraphics`), 3 on 4 units scaled, floor 2 px on 2 px.
- Resolution-independent drawing: cores, halos and ring arcs are drawn as triangle fans with the
  `BufferBuilder` (16 to 32 segments), not as textures, so they stay round at any zoom. Links are quads.

### 2.4 Text at those sizes

Minecraft's font is 9 GUI px tall at every zoom, so names cannot shrink with the sky.

- **Class titles** always show. They are drawn at the web position in editor space (section 1) at 1.0
  font scale, uppercase, class colour, with a dark shadow. On 426 x 240 ten titles around a 216 px sky
  collide, so titles there are drawn at `pose.scale(0.75)` through the `HudText` pattern (`client/HudText`)
  and their collision pass (`labelSpot`) is rerun in GUI space with the real text widths.
- **Perk names** show when `zoom >= 1.0`, or for the hovered star, the selected star and the stars linked
  to the selected one at any zoom. One wheel notch from fit on a 1080p scale-2 screen reaches 1.0 for the
  area under the cursor, so names are one notch away; the hover name covers the browsing case.
- **Tooltips**: hovering a star always shows a vanilla tooltip with name, cost line and state line (the
  first three lines of the details card). The card itself opens on click.

### 2.5 Keyboard and controller

There is no native controller support in Minecraft; Controlify and the Steam Deck map a pad onto
keyboard and mouse. So the menu is fully keyboard operable and the pad follows from that.

| Key | Action |
|---|---|
| Arrow keys, star selected | Move selection to the linked star whose direction is nearest the arrow (requirement links and chords both count); if none, the nearest star in that direction within 160 units |
| Arrow keys, nothing selected | Pan the sky |
| Tab / Shift+Tab | Next / previous class: selects that class's Engine-linked perk and centres on it |
| Home | Select the Engine and return to the fit view |
| Enter or Space | The card's primary action (Unlock, or Add point); with no card open, open the card for the selected star |
| Backspace | Close the card, keep the selection |
| `+` / `-` | Zoom |
| Esc, or the perks key | Close the menu |

The pad mapping under Controlify: left stick = mouse, D-pad = arrows, A = Enter, B = Esc, bumpers = Tab
and Shift+Tab, triggers = zoom. Document this in the Controls wiki page; nothing to code.

The selected star always keeps a focus ring (section 3) so keyboard users can see where they are. On
open, nothing is selected; the first Tab selects the first class.

---

## 3. Star states

| State | Meaning | Sky drawing |
|---|---|---|
| Locked, not reachable | No neighbour unlocked, not linked to the Engine | Greyscale core and halo at 35 %; links in grey at 18 %; name grey |
| Locked, reachable | Linked to the Engine, or a requirement neighbour is unlocked, or a two-way chord neighbour is unlocked, or the source end of a one-way chord is unlocked | Class colour at 55 %, core drawn hollow with a 1 px stroke, halo at 25 %; the link from the unlocked neighbour is drawn at the normal 55 %. Reachable stars breathe: halo opacity 25 % to 40 % over 2 s |
| Unlocked, 0 points | Bought for life | Hollow core (dark fill, class-colour stroke 1.4), halo at 35 %, full-colour name |
| Active, N points | Unlocked and holding N active points this life | Filled core, N of 10 ring slots lit in class colour; links to other active stars at 95 % and width 2.8 |
| Tier 3 | Any of the above | Core in `strong(colour)` with the dark centre dot; the dot is drawn even when hollow |
| The Engine | Always active, never buyable | Gold core 14 and halo 26, dark dot 5, no ring, no name, class title none |
| Hover | Mouse over the hit circle (radius 20 units, 30 for the Engine, floor 8 GUI px) | Halo at 100 %, name shown, cursor stays an arrow |
| Selected | Clicked or keyboard-focused | Gold halo at 100 % (web `.node.sel`), plus a second gold ring at halo + 4 at 50 % for keyboard focus; the card is open |

Affordability does not change the star. The card says whether you can pay (section 4).

**Why reachable gets its own look.** The web page has three states; the game adds "reachable" so a
player with points to spend can see where they may go without opening every card. Locked-not-reachable
stays greyscale so the shape of the whole sky is still visible.

### 3.1 Sky look or Minecraft-textured look

The web page has two styles: sky (circles) and mc (advancement frame textures, task / goal / challenge
by tier, with an item sprite inside). **Recommendation: the sky look for the game**, with the mc tile
used only as the icon inside the details card.

- The mc frames are the advancements screen's frames. Unlock points come from advancements, and a tree
  drawn in advancement frames will read as a second advancements tab; players will expect advancement
  rules (one-shot, no points) and the 10-slot ring has nowhere to sit on a square frame.
- Frames are 26 px bitmaps; drawn at the floor sizes of 2.3 they would be downscaled bitmaps at every
  fit zoom, blurry or pixel-dropped, and the tier reads only from colour. Circles stay crisp at any size.
- The page's named look is the constellation. The night sky is the thing Brennan designed; the mc style
  exists so the editor could preview "what if it looked like vanilla".
- Cost: the mc style needs the item sprites per perk (`perks-textures.json` keys like
  `item:golden_apple`); in game those are real `ItemStack`s rendered with `renderItem`, which is cheap,
  so the card icon is free and the full mc sky remains possible later behind a client option if wanted.

---

## 4. The details card

Floats beside the star (2.2), width 150 GUI px (120 on 426 x 240), dark `#F0111A30` fill, 1 px `#2a3350`
border, gold title. Text is literal and short, like the perk names. Lines, top to bottom:

```
Health                         [x]      title in class colour, 1.0 scale; X closes
TANK                                    class name, small caps, muted
Tier 1. Cost 1.                         tier and unlock cost (1 / 5 / 10)
More max health.                        desc from the tree, wrapped, max 4 lines
                                        (blank)
Locked. 3 unlock points.                state line, see table
[ Unlock ]                              primary button, 20 px, full width
Links: Heal Friends, Kill Buff          hint line, muted, same as the web card
```

State line and buttons by state:

| State | State line | Buttons |
|---|---|---|
| Locked, not reachable | `Locked. Needs <neighbour name>.` (the nearest reachable neighbour on a requirement link, else `Not reachable yet.`) | none |
| Locked, reachable, enough unlock points | `Locked. <N> unlock points.` | `Unlock` |
| Locked, reachable, not enough | `Locked. <N> of <cost> unlock points.` | `Unlock` disabled |
| Unlocked, 0 points | `Unlocked. 0 points.` plus `What a point does: <effect>` | `Add point` (disabled when the active balance is 0, tooltip `Eat cosmic debris.`) |
| Active N | `Active. <N> points.` | `Add point` as above; disabled at 10 with tooltip `Full.` |
| The Engine | `The start.` | none |
| Free Play | the normal state line plus `Free Play. Perks are off.` | all disabled |

"What a point does" comes from the tree: the perk list's `cost` strings like `1 x 3` become a per-point
line in the published JSON **(assumes** the perk list adds a `point` field, e.g. `+2 max health`; until
then the card shows `desc` only).

Unlock and Add point act at once: no confirm dialog. Spending is cheap to reverse in the economy (a
death resets active points; unlock points are plentiful by design) and the web test mode sets the
expectation of one click.

Numbers never show the hidden maximum of anything but the ring: `Active. 3 points.` not `3/10`; the
ring makes the 10 visible without text.

---

## 5. The two currencies

### 5.1 Balances

The header strip, after the title, in two small labels with icons drawn as item stacks:

```
Perks        ✦ 3 unlock points        ● 1 point
```

- **Unlock points** come from advancements, persist across deaths, and buy perks. Icon: a star shape in
  gold (a 5-point polygon from the same fan drawing), label `<N> unlock points`.
- **Active points** come from eating cosmic debris, are per life, and go into unlocked perks one at a
  time. Icon: the cosmic debris item itself (`renderItem`), label `<N> points`. Hidden entirely when the
  balance is 0 and nothing is active, so a new player sees one number, not two.
- Both are read from PerkState; the menu never computes them.

### 5.2 Earning while the menu is open

The PerkState sync packet can arrive while the Screen is up (an advancement toast behind the menu). The
Screen re-reads state every frame from the client-side PerkState holder, so balances and stars update
live; a balance that goes up flashes gold for 0.5 s.

### 5.3 Opened by eating a cosmic debris

Eating a debris adds one active point and opens the menu **(assumes** the earning session opens the
Screen from the client on the eat packet or the item's `finishUsingItem`). In that case:

- The header shows `1 point to spend` in gold in place of the plain balance, pulsing slowly.
- Every star that can take a point (unlocked or active below 10) gets the reachable breathe (3), so the
  places the point can go are the moving things on screen.
- If no perk is unlocked yet, the header says `1 point. Unlock a perk first.` and the point stays in the
  balance; nothing is lost. The point sits there until spent or until death resets it.
- After the point is spent, the header returns to the normal balances and the menu stays open. Eating a
  second debris while the menu is open just bumps the number.
- Esc closes with the point still banked. No nag.

---

## 6. Feedback

### 6.1 Animations (client only, 0.4 s, no state involved)

- **Unlock**: the hollow core fills from the centre outward over 0.3 s; the halo flashes white then
  settles to the class colour; a short burst of 8 sky dots flies out 20 units and fades. Links to that
  star brighten from grey to colour over the same 0.3 s. Newly reachable neighbours start breathing.
- **Add point**: the new ring slot sweeps in clockwise over 0.25 s, then the whole ring pulses once.
- **Refused** (server said no, section 7): the card's state line shakes 2 px left and right over 0.2 s
  and shows the reason for 2 s.
- **Open / close**: the sky fades in from black over 0.2 s with the dots appearing first; the reverse on
  close. Minecraft has no reduced-motion option, so every animation stays under 0.4 s and none loops
  except the slow breathe and the twinkle.

### 6.2 Sounds (vanilla events, played with `SimpleSoundInstance.forUI`)

| Event | Sound |
|---|---|
| Select a star | `UI_BUTTON_CLICK` at 0.3 volume |
| Unlock | `PLAYER_LEVELUP` at 0.7 volume, pitch 1.0 |
| Add point | `AMETHYST_BLOCK_CHIME` at 0.8 volume, pitch 1.2 plus 0.05 per point already in (the ring climbs a scale) |
| Refused | `UI_BUTTON_CLICK` at 0.5 volume, pitch 0.6 |
| Open | `AMETHYST_BLOCK_CHIME` at 0.4 volume, pitch 0.8 |

`AMETHYST_BLOCK_CHIME` is already DT's "something magical happened" sound (`event/StartingBookEvents`).

### 6.3 HUD after a change

When the menu closes, or 1 s after a change while it stays open, a line shows above the hotbar for 3 s
through the existing HUD overlay path (`client/HudText` scale), in the class colour:

- Unlock: `Health unlocked`
- Add point: `Health 3` (the new count, no maximum)
- Debris eaten with the menu not open (player disabled auto-open, section 8): `1 point to spend`

No toast in the top-right: the advancement toasts already live there and this is not an advancement.

---

## 7. Multiplayer and Free Play

- The menu is **per player**. PerkState is a player attachment, synced only to its owner; the Screen
  never sees other players' perks. Nothing on the sky says what anyone else bought.
- Every action is a client-to-server packet and the server is the judge: it re-checks reachability, the
  balance, the 10-point cap and Free Play, then re-syncs PerkState. The client shows the result from the
  sync, not from its own guess, so a stale or spoofed packet changes nothing (same pattern as
  `net/BookBoostPacket`).
- **Free Play** (`FreePlayQuery` via `RunIntegrity.isCheated`): the tree is viewable, nothing can be bought
  or pointed, the card says `Free Play. Perks are off.`, the header balances are hidden, and eating a
  debris does not open the menu or add a point **(assumes** the foundation keeps perk effects inert in
  Free Play, which is the fair-play line: advancement points never accrue there either). The server
  refuses unlock and spend packets from a Free Play player silently.
- Dedicated server: the tree JSON is bundled in the mod jar on both sides (section 8.4), so no tree sync
  packet is needed, but the server must refuse perk ids it does not know, and the client must tolerate a
  state entry for an id missing from its tree (draw nothing, keep the points).
- Reloads: `/reload` refreshes the tree on the server; the client rereads its bundled copy when the Screen
  opens. Positions are presentation only, so a client and server on different tree versions still agree
  on ids and costs as long as the perk ids match. A mismatch in costs is caught server side.

---

## 8. Implementation notes

### 8.1 Screen class sketch

```java
package games.brennan.dungeontrain.client.perks;

@OnlyIn(Dist.CLIENT)
public final class PerksScreen extends Screen {
    private final Screen parent;          // pause menu or null
    private final PerkTree tree;          // PerkTrees.client(): parsed data/dungeontrain/perks/tree.json
    private final boolean fromDebris;     // header mode, section 5.3
    private SkyView view;                 // immutable record: zoom, panX, panY (editor units at screen centre)
    private @Nullable String selected;    // perk id
    private @Nullable String hovered;
    private long openedAtMs;              // open fade
    private final Map<String, Long> unlockedAt = new HashMap<>(); // id -> ms, drives the fill animation
    private final Map<String, Long> pointAt = new HashMap<>();
    private final StarField stars;        // 140 dots seeded once per screen, twinkle by time

    // read every frame
    private PerkState state() { return ClientPerkState.get(); }

    @Override protected void init()   // header widgets: close X; the card's buttons are rebuilt on select
    @Override public void render(GuiGraphics g, int mx, int my, float dt)
        // 1 backdrop + glow + stars  2 links (req then chords)  3 class titles  4 stars by state  5 header  6 card
    @Override public boolean mouseClicked / mouseDragged / mouseScrolled / keyPressed  // sections 2.3, 2.5
    @Override public boolean isPauseScreen() { return false; }   // the train keeps moving; same as other DT screens
}
```

Supporting pieces, each its own small file (coding-style rule: many small files):

- `perks/PerkTree` (record: classes, perks, links, index maps, `reachable(PerkState)`), `perks/PerkTrees`
  (loads and validates the JSON on both sides; a `SimplePreparableReloadListener` on the server, a static
  parse on the client from the jar resource).
- `client/perks/SkyView` (zoom and pan maths, `toScreen`, `toSky`, `fitFor(width, height)`).
- `client/perks/StarPainter` (fans and rings with `BufferBuilder`), `client/perks/LinkPainter` (lines,
  gradient quads, dashed chords), `client/perks/ClassTitlePainter` (label placement port of `labelSpot`).
- `client/perks/PerkCard` (the floating card: layout, buttons, state line).
- `client/perks/PerksHud` (the 3 s line, section 6.3) and `client/perks/PerksHotkeyClient` (keybind
  `key.dungeontrain.perks`, default `P`, category the existing Dungeon Train category; the Screen must
  match the binding itself on close because vanilla stops polling bindings while a Screen is up, see
  `TemplateBlocksHotkeyClient`).
- Pause menu entry: a "Perks" `DarkTintedButton` added in `ScreenEvent.Init.Post` for `PauseScreen`, the
  same hook `TitleScreenSupportButton` uses for the title screen. Hidden in Free Play? No: shown, the
  screen explains.
- Death screens never open the menu (the UI handover note's decision stands).

### 8.2 Data read from PerkState **(assumes** the foundation's shape)

| Field | Used for |
|---|---|
| `unlockPoints()` int | header balance, Unlock enabled |
| `activePoints()` int | header balance, Add point enabled, debris mode |
| `unlocked()` Set of perk id | star state, reachability |
| `active()` Map perk id to int | ring slots, state line |
| `freePlay()` boolean (or read from the existing client-side cheated flag) | section 7 |

The Screen keeps no copy of these; `ClientPerkState.get()` is the one holder the sync packet writes.
Animations key off a diff between the previous frame's snapshot and this one (unlocked set grew, a
count went up), so a change that arrives from the server with no local click still animates.

### 8.3 Packets (client to server, NeoForge `PayloadRegistrar.playToServer` in `net/DungeonTrainNet`)

```java
record PerkUnlockPacket(String perkId)   // dungeontrain:perk_unlock
record PerkSpendPacket(String perkId)    // dungeontrain:perk_spend, always one point
```

Server handling, in order: player is a `ServerPlayer`; not Free Play; id exists in the server's tree;
for unlock: not already unlocked, reachable under the server's reachability (same rules as 3), balance
at least the tier cost; for spend: unlocked, points below 10, active balance at least 1. On success,
mutate through the foundation's API (`Perks.unlock`, `Perks.addPoint`) which syncs PerkState. On refusal,
send the existing sync anyway (so a stale client corrects itself) plus a one-field
`PerkRefusedPacket(String reason)` for the card's state line; reasons are lang keys
(`perks.refused.points`, `.reach`, `.full`, `.freeplay`). Limits: `writeUtf(id, 64)`.

Rate: one action per click; the server ignores a second packet for the same id inside 100 ms.

### 8.4 How the tree ships

- File: `src/main/resources/data/dungeontrain/perks/tree.json`, committed, the editor's export verbatim
  (the same JSON the owner pastes into the site's publish box). Shape:

```json
{ "v": 4, "savedAt": 0,
  "classes": [ { "id": "c0", "name": "Archivist", "color": "#c9a7e0" } ],
  "perks":   [ { "id": "root", "cls": null, "name": "The Engine", "desc": "...", "cost": "none", "x": 400, "y": 400 },
               { "id": "c7r1k0", "cls": "c7", "name": "Health", "desc": "More max health.", "cost": "1", "tier": 1,
                 "cap": false, "x": 412.3, "y": 296.1, "icon": "item:golden_apple", "reviewed": true, "work": 0, "dev": "..." } ],
  "links":   [ { "a": "root", "b": "c7r1k0", "kind": "req", "oneWay": false },
               { "a": "c7r1k0", "b": "c2r1k0", "kind": "cross", "oneWay": false } ] }
```

- Positions are in the editor's 800 x 800 space, centre 400,400, and the menu draws them as is. Nothing
  in the mod re-lays-out the sky: moving a star is an editor change, published to the site and pulled
  into the jar, so the game and the web page always match.
- Pull task: `./gradlew pullPerksTree` copies the relay's `GET /perks/tree` into that file, like
  `pullTradeValues`. The route needs the owner's site cookie, so the task reads it from the gitignored
  secrets file (never on the command line). Never part of `build`; run by hand, review the diff, commit.
- Tier: `tier` when present, else `cap` means 3, else 1 (the web `tierOf`). Cost is derived from the
  tier (1 / 5 / 10), never parsed from the `cost` string, which is prose for the perk list.
- Ids are the join key to `Perks.rank` and the effect code. The editor's generated ids (`c7r1k0`) are
  stable once published but opaque; **recommendation**: the perk list session gives each perk a hand
  `id` (`tank_health`) before the first publish, so effect code and lang keys read well. The menu does
  not care which.
- Text: `name` and `desc` in the JSON are English. DT ships 20 locales (i18n rule: a new key needs all
  locales before PR). The menu shows `Component.translatable("perk.dungeontrain.<id>.name")` with the
  JSON string as the fallback, and a generator script writes the en_us keys from the JSON
  (`scripts/perks/lang-from-tree.py`), so translation follows the normal import path. `dev` and `work`
  are editor fields and are never shown in game.
- Validation at load (fail loud, like `data/.../trade_values.json`): every link end exists, `root`
  exists and is at 400,400, no duplicate ids, every non-root perk has a class, colours parse, tiers in
  1..3, every perk is reachable from `root` through links (the web table view's `reachable()`; a floating
  star is an editor mistake). A failed validation keeps the previous tree and logs the ids.
- Icons: `icon` keys like `item:golden_apple` or `block:bookshelf` map to `ResourceLocation`
  `minecraft:<name>`; the card renders the `ItemStack`; unknown or missing falls back to `nether_star`
  as the web does.

### 8.5 Lang keys (all 20 locales before PR)

`gui.dungeontrain.perks.title` Perks, `.unlock_points` %s unlock points, `.points` %s points,
`.point_to_spend` 1 point to spend, `.unlock_first` 1 point. Unlock a perk first., `.locked` Locked.,
`.needs` Needs %s., `.not_reachable` Not reachable yet., `.unlocked` Unlocked., `.active` Active.,
`.n_points` %s points., `.unlock` Unlock, `.add_point` Add point, `.full` Full., `.eat_debris` Eat
cosmic debris., `.free_play` Free Play. Perks are off., `.links` Links: %s, `.the_start` The start.,
`.hud_unlocked` %s unlocked, `key.dungeontrain.perks` Open perks. No long dashes anywhere.

### 8.6 Order of work for the engineer

1. `PerkTree` + `PerkTrees` loader + validation + a JUnit test on a fixture tree (reachability, tier).
2. `SkyView` + painters, drawing a static tree with every state faked (a dev command
   `/dungeontrain debug perks fake <state>` mirrors the web test mode; dev-gated, named only as
   "Dev command improvement" in notes).
3. Card, keyboard, hotkey, pause button.
4. Packets and server checks against the foundation API; refusal line.
5. Animations, sounds, HUD line. 6. Debris-open mode with the earning session. 7. Lang + screenshots at
   the four budgets in section 2.1 for Gate 2.

---

## 9. Decisions (Brennan, 2026-10-24)

1. **Sky look only.** The game copies the site's constellation; the Minecraft-textured draft is dropped
   everywhere, card included (section 3.1's card icon is not built).
2. **Hand-written perk ids.** `scripts/perks/tree-from-editor.py` gives every perk `id` = kebab of its
   name (with an override map for the registry's spellings) and rewrites the links; the bundled
   `tree.json` is what the game reads.
3. **Per-point effect line.** The editor gets a `point` field per perk; the card shows it as
   `Each point: ...` when present.
4. **Free Play buys and spends like everyone else.** No gating, no banner; the server does not check
   Free Play on unlock or spend (section 7's inert mode is withdrawn).
5. **Key `P` and a Perks button on the pause menu**, singleplayer and multiplayer.

Implementation: `client/perks/PerksScreen` and friends, `perks/PerkTree` + `PerkTreeLoader`,
`net/PerkUnlockPacket`, `PerkSpendPacket`, `PerkBalancesPacket`, `PerkRefusedPacket`.
