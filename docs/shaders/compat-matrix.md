# Shader compatibility matrix

How Dungeon Train's five atmosphere systems behave under each supported Iris shader pack — and
the mechanism that makes them behave, which is the part the first version of this document got
wrong.

**Status: mechanism verified on Complementary Unbound (2026-09-02); per-pack table below.**

---

## What a pack can and cannot be made to do

The first pass at this matrix measured that every pack overwrites Dungeon Train's sky dome and
concluded the only fix was to draw the atmosphere *after* the pack's composite. That would have
pasted an unlit flat dome over shader-lit terrain and left lighting and fog wrong. Three facts about
Iris (read off `iris-neoforge-1.8.14-beta.1` with `javap`) point somewhere better:

1. **Iris chooses a pack's world per frame from `Iris.getCurrentDimension()`.** Every pack ships
   `world0` / `world-1` / `world1` program sets, and `PipelineManager` caches one pipeline per id.
   Dungeon Train's Nether and End are bands of the overworld, so left alone a pack renders them
   with its overworld sky, fog and lighting. A mod-gated mixin (`IrisMixinPlugin`,
   `dungeontrain.iris.mixins.json`, `client/shader/ShaderWorld`) answers "the Nether" or "the End"
   while the camera is in that band or in a Nether/End-skied dimensional carriage, and **the pack
   itself renders its Nether or End** — sky, fog, lighting, everything. This is the only way a band
   can be indistinguishable from the real dimension under shaders, and it is what `Shader world:` on
   the panel reports. The pipelines are compiled at login (`Pre-warmed … in N ms` in the log) and
   two warm-up frames run behind the loading screen so their first-frame chunk rebuild is free.
2. **A pipeline swap is binary, so the fade renders twice.** Between the fade window's ends
   (`ShaderWorld.FADE_WINDOW_START..END`, 0.3–0.7 of the band ramp; the room ease for carriages)
   `GameRendererCrossfadeMixin` renders the frame with the world being left, keeps the image,
   renders again with the world being entered, and blends the first over the second. Frame cost
   roughly doubles only inside the window. `shaders.crossfade=false` in the client config gives a
   hard cut at the midpoint instead.
3. **Iris latches depth and colour writes off for any `ShaderInstance` it does not own** while the
   world is rendering (`MixinShaderInstance` + `DepthColorStorage`). A mod's own core shader can only
   draw after Iris' final pass. Two consequences:
   - Skybox blocks cannot be reopened with a custom shader. They are reopened with **stencil**
     instead: Iris attaches the main render target's depth texture — the `DEPTH32F_STENCIL8` one
     Dungeon Train already asks for — to its gbuffer framebuffers, and never touches stencil state.
     `SkyboxHoleReopen` marks the hole pixels still visible after everything opaque has drawn and
     pushes them to depth 1.0 (the cubes again under `glDepthRange(1,1)`), so the pack's deferred
     and composite passes paint its own sky there. It runs at `AFTER_BLOCK_ENTITIES`, the last
     stage before Iris' deferred pass, and under an identity model-view (vanilla has the frustum on
     the stack by then — the first version drew the cubes rotated twice and marked nothing).
   - Dimensional-carriage fog is drawn by `PostFogPass` at `AFTER_LEVEL`, after the composite, from
     a depth copy taken at `AFTER_WEATHER`, toward the frame's own fog colour. Packs that honour
     `fogStart`/`fogEnd` still get vanilla's planes as well.

What each system does under a pack, then:

| System | Mechanism under a pack |
|---|---|
| Skyboxes in bands | Pack renders its own Nether / End (world spoof + cross-fade). Upside-down: the pack's overworld sky; DT's dome is not drawn |
| Skybox Blocks | Depth punch + stencil reopen → the pack's sky for the world currently rendering (an End block in an End carriage shows the pack's End; an End block in the plain overworld shows the pack's overworld) |
| Dimensional carriage fog | Post-composite depth fog at the room's planes, in the frame's fog colour |
| Carriage sky & lighting | NETHER / END rooms: the pack's Nether / End pipeline. DAY / CYCLE rooms: the lightmap lift only (packs that read the lightmap show it; most do not) |
| Carriage transition | The cross-fade for NETHER / END rooms rides the corridor ramp; the lightmap hold as before; optional screen-space lift (`portal.shaderCrossingLift`, off) |
| Clouds | Hidden over Nether / End bands as before. Sinking in the upside-down band now happens on `getCloudHeight()` itself, so Iris' `cloudHeight` uniform sees it; packs with `clouds=off` draw no vanilla clouds at all (their own volumetrics cannot be moved) |

---
## The instrument: F3 + 5

`ShaderDiagnosticsHud` draws a panel **top-right** stating what DT asked the frame for. The rest of
the screenshot is what the pack did with that request. **Both halves are needed** — "DT never asked"
and "the pack discarded the ask" look identical in a bare screenshot, and the fix for one is wrong
for the other.

Open with **F3+5** (F3+4 is the train panel; both can be up at once). Open by default in a dev
build; gated on a debug grant otherwise.

| Panel line | Reads |
|---|---|
| `Pack:` | family id + the exact zip Iris loaded |
| `Stencil: main … level fbo …` | stencil on the main target, and on the framebuffer the level actually rendered into (under Iris: the pack's gbuffer FBO — `texture` means the reopen pass can work) |
| `Band t:` | void / nether / upside-down intensity **at the camera** — the ask |
| `Band sky drawn:` | the alpha each band overlay actually submitted — 0 means it did not draw |
| `Fog colour:` | which band tinted the fog, and the colour in → out |
| `Fog dist:` | vanilla far → DT far, near, and whether the event was cancelled (uncancelled = ignored by design) |
| `Skybox blocks:` | cubes indexed near the camera, variants, stencil, whether the pass drew |
| `Reopen:` | (packs only) centre-pixel depth before → after the skybox reopen pass, and the stencil bit the mark pass left. `→ 1.00000 … 0x80` is the hole handed to the pack as sky |
| `Shader world:` | which of the pack's worlds Dungeon Train is telling Iris to render: `overworld (pack default)`, `nether (world-1)`, `end (world1)`, or mid-fade `overworld -> nether w=0.42 (2 renders)` |
| `Post pass:` | (packs only) what the post-composite pass drew: the fog planes and the lift |
| `Room sky:` | dimensional carriage sky kind, intensity, and the lightmap lift applied |
| `Transition:` | corridor ramp and the lift applied |

A line is **green** when that system asked for something this frame, **grey** when idle, **red**
when the pack has it switched off. Grey means look at the ask, not at the pack.

---

## Test stack

Everything below lives in the gitignored `run/` of this worktree; nothing here ships.

**`run/mods/`** — the exact pair `modpack.config.json` pins, which is what players run:
- `sodium-neoforge-0.8.12+mc1.21.1.jar` (Modrinth `S3DUMfBo`)
- `iris-neoforge-1.8.14-beta.1+mc1.21.1.jar` (Modrinth `KduFYu4t`)

> **The version triangle is narrow.** Sable declares Sodium incompatible below `0.8.12-alpha.2` and
> hard-crashes FML pre-load otherwise; stable Iris 1.8.12 pins Sodium 0.6.13, exactly the version
> Sable rejects. Beta Iris 1.8.14 + stable Sodium 0.8.12 is the pair that boots. Re-check this if
> `sable_version` moves.

**`run/shaderpacks/`** — eleven packs, all from Modrinth at their latest 1.21.1 version.

**Presets.** Iris reads per-pack settings from `run/shaderpacks/<exact-zip-name>.txt`, so a preset
is applied by dropping the file in — no GUI. Three are in place from the ones supplied:
`BSL_v10.1.3.zip.txt`, `ComplementaryReimagined_r5.8.1.zip.txt`,
`Sildur's Enhanced Default v1.19 Fancy.zip.txt`. Boot confirms them:
`[Iris/]: Profile: Custom (+5 options changed by user)`.

> ⚠ **These three presets are partial** — five keys each, as supplied, not the whole file. Every
> key not listed falls back to the pack's own default, which is safe but is *not* the same
> configuration. `FOG_HEIGHT`, `FOG_DENSITY_END` and `LIGHT_SHAFT_WEATHER_FALLOFF` bear directly on
> three of the five systems, so a fog result measured against a partial preset should be re-checked
> against the complete one before it is treated as final.

> ⚠ **Version drift from the supplied builds.** Modrinth's latest differs from what was sent:
> BSL v10.1 → v10.1.3, Complementary Reimagined r5.6.1 → r5.8.1, Sildur's Enhanced Default v1.18
> Fast → v1.19 Fancy. The Complementary build sent was also bundled with the `Clrwl_1.0.3` add-on,
> which the plain Modrinth pack does not include.

---

## Running the sweep

The mod drives itself. One command per pack, or one command for all of them:

```bash
scripts/shaders/sweep-all.sh "Dev World 1788237061079"
```

Each pack gets its own client launch: the script points `run/config/iris.properties` at that pack,
launches with `-PshaderSweep=<world>`, and `client/ShaderSweep` opens the world, walks the sites,
writes `run/screenshots/sweep-<pack>-<site>.png` with the F3+5 panel up, logs
`SHADER SWEEP COMPLETE`, and quits. A single pack:

```bash
printf 'enableShaders=true\nshaderPack=%s\nmaxShadowRenderDistance=32\n' "BSL_v10.1.3.zip" > run/config/iris.properties
JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew runClient --no-daemon -PshaderSweep="Dev World 1788237061079"
```

Confirm the stack took, in `run/logs/latest.log`:
- `[Iris/]: Using shaderpack: <zip>`
- `[Iris/]: Creating pipeline for dimension minecraft:overworld`
- `[AbstractSableMixinPlugin/]: Using Sodium renderer mixins`

Alongside each screenshot the harness logs a `sweep[<site>]` line carrying every panel value, so a
result can be grepped across all eleven runs rather than only read off an image.

The same harness has a second mode, `--preview` / `-PshaderPreview`, which captures the title
screen's **Shaders** page previews instead of the diagnostic sites: one site, the train stopped, the
panel off. See [`menu-previews.md`](menu-previews.md).

> **The window must be allowed to render, but not to be focused.** The sweep sets
> `pauseOnLostFocus=false` in code and in `run/options.txt`. Without it a background window is an
> unfocused one, vanilla opens the pause screen, the integrated server halts, and every site is
> photographed in a frozen world — which is exactly how the first run failed.

### The sites

| Site | How it is reached | What it measures |
|---|---|---|
| `00-plain` | Creative, wherever the world left the player | Baseline |
| `01-band-void` | `/dtp` to the first X where the End ramp reads ≥ 0.95 | Band skybox + fog colour |
| `02-band-nether` | Same, on the Nether ramp | Band skybox + fog colour |
| `03-band-upsidedown` | Same, on the upside-down ramp | Band skybox + fog colour |
| `04-skybox-blocks` | Spectator above the train, forceloaded chunk, wall of `dungeontrain:skybox_end` | Skybox blocks |
| `05-carriage-plot` | `editor portals enter`, then force an endless mode + a `day` sky | Setup only |
| `06-carriage-corridor` | `portal test` — stamps the room as a twin, camera at the corridor mouth | Carriage fog, room sky |
| `07-carriage-room` | A step down the corridor into the room | Carriage fog, room sky |

Band stops are **located at runtime** by scanning each ramp forward from the player, not hard-coded:
the bands are a function of the world's own cycle config, so a fixed X would quietly photograph the
wrong place. A band that is not found within 400k blocks is logged and skipped rather than faked.

A site that cannot be reached is skipped, not fatal — measured cells and one honest "unreachable"
beat a run that returns nothing.

> **Why the carriage sites force the room's settings.** The fog is only sent for a mode that fogs
> (`if (!structure.mode().fogs()) return`) and the sky lift only for a room that names a sky.
> `backrooms` does neither by default, so both systems would have read as "the pack discarded it"
> when nothing had been asked of them. Forcing the settings means a zero is always the pack's doing.
> It does mutate the room's authored settings, and the in-game editor auto-writes those into
> `src/main/resources` — check and revert that path after a sweep.

### Two failure modes the harness had to be taught about

Both were caught by the control, and both would have become confident, wrong cells.

**Stale captures.** macOS stops rendering an occluded window while its ticks carry on. One run
produced eight screenshots of which seven were byte-identical, under a log that looked perfectly
healthy — the panel values moved from site to site exactly as they should. Every capture now reports
how many frames actually rendered since its site began and shouts when that is too few, and the
sweep script fronts the window so the frames exist to capture. **This is why the sweep cannot run
minimised or fully covered.** If a run reports `THE IMAGE IS PROBABLY STALE`, discard it.

**Coordinates on the train.** A rider sits on a Sable sub-level, so the server resolves `~` in far
shipyard plot space rather than the track coordinates the camera is looking at — `/fill ~4 …`
answers "that position is not loaded". Anything placed near the player needs absolute coordinates
over a forceloaded chunk.

### Not covered

**The carriage transition.** `ClientPortalCrossing` is driven by the live corridor swap, and the
`portal test` twin has no train and nothing that swaps, so `crossing` reads `0.000` at every
carriage site. This is a property of the test stamp, not of any shader pack. Measuring the
transition — and the band cross-fade — needs a real ride, and is done by hand (see Gate 2 notes).

**Why the carriage sites used to read `roomBox=none`.** Not the pack, and not the plot: the
trainless carriage tick (`PortalCarriageEvents.tickRoomTiling`) cleared every player's room fog
and sky each tick because it had no structure of its own, while `PortalTestTicker` re-sent them
each tick. The client saw a room for one tick in two and never engaged. Fixed on this branch by
leaving portal-test players to the ticker; `room fog ->` / `room sky ->` server log lines now
show every send.

### Doing it by hand

If the harness is unavailable, the same sites are reachable manually. Open **F3+5** first; the
`Band t:` line is both the record and the navigation aid — `/dtp <x>` forward until it rises.
Screenshots are F2, into `run/screenshots/`.

File the results as `test-results/gate2-shader-compat-2026-09/<pack-id>-<site>.png`.

---

---

## Results

### Mechanism check — Complementary Unbound r5.8.1 (2026-09-02, SweepWorld, Sodium 0.8.12 + Iris 1.8.14-beta.1)

| Site | Panel | Frame |
|---|---|---|
| `00-plain` | `Stencil: main yes level fbo texture` | control |
| `01-band-void` | `Shader world: end (world1)`, fog colour `#B5D1FF -> #1B1F26` | **the pack's End**: its purple starfield, where the first sweep measured its daylit overworld |
| `02-band-nether` | `Shader world: nether (world-1)`, fog colour `-> #330808` | **the pack's Nether**: its red fog and lighting |
| `03-band-upsidedown` | `Shader world: overworld (pack default)`, `clouds plane 192 -> 8.3` | the pack's day sky; the cloud plane sunk at the source |
| `04-skybox-blocks` | `Reopen: centre depth 0.98578 -> 1.00000  stencil after mark 0x80` | the hole is indistinguishable from the sky around it |
| `06/07-carriage-room` | `Fog dist: far 192 -> 60 near 15 cancelled yes`, `Post pass: fog 15.0..60.0`, `Room sky: DAY t=1.000` | room engaged; fog pass drawn |

Login pre-warm: `Pre-warmed the shader pack's Nether and End pipelines in 5130 ms` (CU, M2 Max).

### Per-pack table

_Filled from the full sweep (`scripts/shaders/sweep-all.sh SweepWorld vanilla <packs…>`); one row
per pack, cells quote the panel. See `test-results/gate2-shader-compat-2026-09/` (local, not
committed) for the frames._

Sweep of 2026-09-03 (one build, `SweepWorld`, time pinned to noon; `Shader world` and `Post pass`
quoted from the per-site log line, `pre-warm` from the login log).

| Pack | Loads | Band void/End | Band Nether | Skybox site | Carriage room fog | Pre-warm |
|---|---|---|---|---|---|---|
| vanilla (control) | – | DT dome, `void 1.000` | DT fill, `nether 1.000` | 588 cubes, stencil skies | far 264 → 60, no post pass | – |
| BSL v10.1.3 | yes | `end (world1)` | `nether (world-1)` | 588 cubes drew | far 264 → 60, post fog 15..60 | 1.4 s |
| Bliss v2.1.2 | yes | `end (world1)` | `nether (world-1)` | 588 cubes drew | far 192 → 60, post fog 15..60 | 1.3 s |
| Complementary Reimagined r5.8.1 | yes | `end (world1)` | `nether (world-1)` | 588 cubes drew | far 192 → 60, post fog 15..60 | 4.5 s |
| Complementary Unbound r5.8.1 | yes | `end (world1)` | `nether (world-1)` | 588 cubes drew, reopen 0.98578 → 1.0 | far 192 → 60, post fog 15..60 | 4.8 s |
| Hysteria v1.2.1 | yes | `end (world1)` | `nether (world-1)` | 588 cubes drew | far 192 → 60, post fog 15..60 | 2.3 s |
| Insanity v1.650 | yes | `end (world1)` | `nether (world-1)` | 588 cubes drew | far 264 → 60, post fog 15..60 | 1.1 s |
| MakeUp Ultra Fast 9.5d | yes | `end (world1)` | `nether (world-1)` | 49 cubes drew | far 192 → 60, post fog 15..60 | 1.1 s |
| Sildur's Enhanced Default v1.19 | yes | `end (world1)` | `nether (world-1)` | 588 cubes drew | far 264 → 60, post fog 15..60 | not logged |
| Spooklementary v2.0.4 | yes | `end (world1)` | `nether (world-1)` | 588 cubes drew | far 264 → 60, post fog 15..60 | 4.9 s |
| FOOTAGE 1.0 | no (`Unable to parse scale directive`) | – | – | – | – | pack bug on this Iris |
| Solas 3.7b | no (`Failed to create shader rendering pipeline`) | – | – | – | – | pack bug on this Iris |

Every loading pack took the Nether and End pipelines in the bands (its own atmosphere, verified on
the frames for Complementary Unbound and BSL), ran the skybox punch under the pack, and engaged the
carriage room with the post-composite fog pass. The `far` column differs between 192 and 264 with
whatever render distance the pack's own settings leave; the room radius (60) is what matters.

### Also on this branch (found by riding, not by the sweep)

- **Carriages lit through a Nether roof.** A pack's Nether programs still sample a shadow map, and
  the band's was rendered for a light the real Nether does not have. Fixed by clearing both shadow
  depth textures to 0.0 after Iris' pass in a spoofed band (`IrisShadowOccludeMixin`): every sample
  reads "occluded", exactly as a dimension with no shadow-casting light. Verified on BSL with the
  pack's own shadows on; cleared Sildur's strobing too.
- **Upside-down band: permanent dawn.** Its sun orbits the horizon, so the pack now sees a sky angle
  eased to just-above-horizon dawn and a shadow light swung round with the drawn sun
  (`IrisCelestialTimeMixin`). Never noon, never night.
- **Water shaded per chunk in the upside-down band** is pre-existing and shader-independent
  (`LevelGetShadeMixin` reads the camera from Sodium's meshing threads). Not fixed here.

### Known limits

- **Translucents behind a skybox block show through it** under a pack: the reopen has to run before
  the pack's deferred pass (or its sky and volumetric clouds skip the hole), which is also before
  translucents. Skybox blocks face open sky or void in practice.
- **Per-variant skies are vanilla-only.** Under a pack every variant shows the pack's sky for the
  world currently rendering.
- **DAY / CYCLE rooms are not pinned under packs** that ignore the lightmap; a pack's time of day
  is what shows through a DAY room's skybox blocks.
- **A pack's own volumetric clouds cannot be sunk** in the upside-down band; only vanilla-style
  clouds (and packs reading `cloudHeight`) follow the plane.
- **Double render during a fade**: mods with non-idempotent `RenderLevelStageEvent` handlers would
  run twice per frame while fading. None known in the modpack.
- **Distant Horizons + pipeline swap** is untested.

## Evidence

`test-results/gate2-shader-compat-2026-09/` — per-run screenshots (gitignored; kept locally).
