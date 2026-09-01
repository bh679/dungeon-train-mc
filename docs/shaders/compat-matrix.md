# Shader compatibility matrix

How Dungeon Train's five atmosphere systems behave under each supported Iris shader pack.

**Status: not yet measured.** The instrument (F3+5, see below) and the test stack are in place;
the cells are empty until the sweep is run. Do not cite an empty cell as "works".

---

## Why a matrix and not a fix

All five systems reach the screen through hooks a shader pack is free to honour or discard:

| System | Hook | What a pack can do with it |
|---|---|---|
| Skyboxes in bands | `LevelRenderer#renderSky` TAIL → `SkyDomeDraw` (`position_color` / `position_tex_color`) | Routed into `gbuffers_skybasic`/`skytextured`; a pack that rebuilds sky in composite from depth can overwrite it entirely |
| Skybox Blocks | colour-masked depth write + stencil at `AFTER_SKY` | Reaches Iris' composite with cleared albedo/normal → shades black. **Already disabled** (`ShaderCompat.allows`) |
| Dimensional carriage fog | `ViewportEvent.RenderFog` near/far planes | Surfaces as `fogStart`/`fogEnd`; packs that compute their own atmospheric fog ignore both |
| Carriage skybox & lighting | `LightTexture#updateLightTexture` lift + `ViewportEvent.ComputeFogColor` | The lightmap is bound as a sampler, but packs deriving light from raw `lmcoord` never read it |
| Carriage transition | `LightTexture` hold (`LightTexturePortalCrossingMixin`) | Same as above |

Which of those each pack actually does is a property of the pack. It cannot be read off this
codebase, so it is measured here, and the fixes in later phases are aimed at what the measurement
finds rather than at what the source suggests.

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
| `Stencil: / Gfx: / DH:` | stencil attachment present, vanilla graphics tier, Distant Horizons |
| `Band t:` | void / nether / upside-down intensity **at the camera** — the ask |
| `Band sky drawn:` | the alpha each band overlay actually submitted — 0 means it did not draw |
| `Fog colour:` | which band tinted the fog, and the colour in → out |
| `Fog dist:` | vanilla far → DT far, near, and whether the event was cancelled (uncancelled = ignored by design) |
| `Skybox blocks:` | cubes indexed near the camera, variants, stencil, whether the pass drew — or the off-reason |
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
transition needs a real portal crossing in play, and it is not in this matrix.

### Doing it by hand

If the harness is unavailable, the same sites are reachable manually. Open **F3+5** first; the
`Band t:` line is both the record and the navigation aid — `/dtp <x>` forward until it rises.
Screenshots are F2, into `run/screenshots/`.

File the results as `test-results/gate2-shader-compat-2026-09/<pack-id>-<site>.png`.

---

## Results

**Status: partial. One sweep of twelve runs completed 2026-09-02; the band column is measured, the
carriage column is not.** Read the caveats before quoting any of this.

### Pack load failures — the one unambiguous result

Two of the eleven packs **do not load at all** on Iris `1.8.14-beta.1`, the build
`modpack.config.json` pins:

| Pack | Outcome |
|---|---|
| FOOTAGE 1.0 | `Unable to parse scale directive for composite1: 0.67 0.67` → `ArrayIndexOutOfBoundsException` → `Failed to create shader rendering pipeline, disabling shaders!` |
| Solas 3.7b | Same signature: produced no captures, Iris fell back to no shaders |

Both are pack-authoring bugs against this Iris, not Dungeon Train problems. They matter here because
Iris answers a failed pack by silently disabling shaders and carrying on — so a sweep run keeps
going with no pack at all. `ShaderSweep` now refuses to capture in that state
(`pack failed to load`); before that guard existed, those runs wrote their screenshots over the
control's.

### Band atmosphere — measured

Mean luma of the sky region (top 15–65% of frame, left of the panel), against the no-shader control.
The scenes are genuinely dark — the void, the Nether's `#330808`, the underside of the upside-down
band — so these are small absolute numbers and the ratio is what carries meaning.

| Pack | void | nether | upside-down |
|---|---|---|---|
| none (control) | 11.1 | 10.7 | 12.9 |
| complementary_unbound | 19.9 (1.80×) | 19.8 (1.86×) | 19.8 (1.54×) |
| bsl | 13.0 (1.17×) | 17.1 (1.60×) | 16.6 (1.29×) |
| insanity | 12.4 (1.12×) | 10.1 (0.95×) | 13.2 (1.03×) |
| sildurs | 11.0 (1.00×) | 11.1 (1.04×) | 12.1 (0.94×) |
| complementary_reimagined | 10.4 (0.94×) | 11.5 (1.08×) | 12.0 (0.93×) |
| makeup_ultra_fast | 11.0 (0.99×) | 10.1 (0.95×) | 11.1 (0.86×) |
| hysteria | 10.7 (0.96×) | 10.3 (0.96×) | 10.9 (0.84×) |
| bliss | 10.4 (0.94×) | 10.6 (1.00×) | 10.6 (0.82×) |
| spooklementary | 10.2 (0.92×) | 10.2 (0.95×) | 10.2 (0.80×) |

In every run DT's own ask was identical and correct — `bandSky void/nether/flip` at ~1.0 with the
fog tint applied — so the differences above are the pack's doing, not DT's.

**What this supports:** Complementary Unbound and BSL materially brighten the band atmosphere
(1.5–1.9×); Spooklementary, Bliss and Hysteria slightly darken it; the rest track vanilla.

**What this does NOT support:** any claim that a pack discards DT's sky dome. The sites frame the
band *environment* from where the train puts the camera, not the dome itself — in the upside-down
band the day sky is below the train and out of shot entirely. Answering "does the dome survive"
needs a site that puts the camera in open air aimed at it. That is the first thing to fix next.

### Carriage fog / sky & lighting — NOT measured

Only `complementary_reimagined` and `complementary_unbound` ever produced a live room
(`fogDist 192→60 cancelled=true`, `room DAY t=1.000 lift=0.250`). Every pack after them read
`room(NONE t=0.000)` with byte-identical setup chat, including the three that ran a harness with a
`portal test back` sweep added on the theory that stamps were accumulating in the reused save. That
theory was wrong — the fix changed nothing — and the cause is still unknown. Two data points out of
ten is not a column.

### Skybox blocks — not informative

`ShaderCompat.allows` gates the effect off under every pack, so every shader row is "off by DT's own
decision". Only the control cell could carry information, and that site was the most fragile of the
eight.

### The transition — not reachable

Unchanged from the note above: `portal test` stamps a twin with no train and nothing that swaps, so
`crossing` reads `0.000` everywhere.

## What the next pass has to do

1. **Aim the band sites at the sky.** Spectator, absolute coordinates over a forceloaded chunk,
   above the train, camera pitched at the dome — the shape the skybox site eventually needed.
2. **Find out why the room stops engaging.** Two packs worked, eight did not, with identical setup
   chat. Suspect world state, not the packs; a fresh save per run would isolate it.
3. **Run the sweep once, from a clean save, on one build.** This sweep is not internally consistent:
   `gradle runClient` recompiles per launch, so mid-sweep source edits reached the later packs and
   three of them ran a different harness than the first seven.
