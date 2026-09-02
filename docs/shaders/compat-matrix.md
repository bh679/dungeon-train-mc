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

**Measured 2026-09-02**, one sweep, one build, one world (`SweepWorld`), Sodium 0.8.12 + Iris
1.8.14-beta.1 — the pair `modpack.config.json` pins. Ten packs captured, two rejected as
non-loading. Every pack returned 8/8 distinct frames and no stale-capture warnings, and DT's own ask
was identical in all ten (`Band sky drawn` at ~1.0, `room DAY t=1.000 lift=0.250`), so every
difference below is the pack's doing rather than DT's.

### Two packs do not load at all

| Pack | Outcome on Iris 1.8.14-beta.1 |
|---|---|
| FOOTAGE 1.0 | `Unable to parse scale directive for composite1: 0.67 0.67` → `ArrayIndexOutOfBoundsException` → `Failed to create shader rendering pipeline, disabling shaders!` |
| Solas 3.7b | `Failed to create shader rendering pipeline, disabling shaders!` |

Both reproduced twice, on two different worlds. These are pack-authoring bugs against this Iris, not
Dungeon Train problems — but they are worth knowing before either is listed as supported, and worth
re-testing if the pinned Iris moves.

### Skyboxes in bands — every pack replaces DT's sky

The upside-down band is the decisive site because DT paints it a flat, known colour:
`SKY_RGB = 0x84B4E8` = `(132, 180, 232)`. With shaders off the frame comes back at
**exactly (132.0, 180.0, 232.0)** — so the site is looking at nothing but DT's dome, and any
departure is the pack overwriting it.

| Pack | mean sky RGB | distance from DT's colour | what the frame shows |
|---|---|---|---|
| none (control) | (132.0, 180.0, 232.0) | 0.0 | DT's flat dome |
| complementary_unbound | (111.2, 134.5, 178.8) | 73.0 | its own daylit sky, with a sun |
| spooklementary | (77.4, 74.1, 74.5) | 197.5 | grey overcast |
| hysteria | (27.4, 49.7, 71.0) | 232.1 | dark |
| bsl | (18.7, 34.6, 44.4) | 263.0 | **night sky with stars** |
| bliss | (10.8, 25.0, 46.0) | 270.8 | dark |
| makeup_ultra_fast | (17.8, 22.8, 29.2) | 280.8 | dark |
| insanity | (24.2, 20.0, 17.2) | 288.7 | dark |
| complementary_reimagined | (10.3, 16.2, 25.1) | 290.6 | dark |
| sildurs | (4.1, 5.6, 7.2) | 312.0 | near-black |

**All nine loading packs discard the band sky dome.** Complementary Unbound is the closest numerically
only because its own sky is also blue — the image shows its atmosphere and a sun, not DT's flat fill.
BSL renders a *night sky with stars* where the control shows day, which is the clearest possible
demonstration: DT submitted the dome (`Band sky drawn: flip 1.000` in every panel) and the pack's
composite painted over it.

The same pattern holds on the two dark bands, in the other direction — packs brighten the void and
Nether 1.9-6.4x over vanilla, again because what is on screen is the pack's atmosphere rather than
DT's:

| Pack | void | nether | upside-down |
|---|---|---|---|
| none (control) | 16.7 | 23.1 | 165.5 |
| complementary_unbound | 5.90x | 6.17x | 0.91x |
| hysteria | 6.44x | 3.86x | 0.47x |
| insanity | 5.25x | 4.79x | 0.14x |
| complementary_reimagined | 1.89x | 1.91x | 0.23x |
| bsl | 1.92x | 1.51x | 0.21x |
| bliss | 1.86x | 1.08x | 0.17x |

> Caveat: world time is not pinned. Each run reaches each site at roughly the same elapsed time, so
> drift between packs is small, and DT's dome is a fixed-colour fill that does not vary with time —
> but a pack's own sky does. Pinning `/time set day` per site would remove the variable entirely.

### Dimensional carriage sky & lighting — asked for everywhere, not yet proven visible

Every pack applied the lift: `room(DAY t=1.000 lift=0.250)` with the camera verified inside the
server-named box (`roomBox=DAY[85..96, 230..236, 565..576]`). What the sweep cannot yet say is
whether the lift *survives* a pack, because a lightmap lift is a subtle change to an already-lit
scene and there is no A/B in the run. Answering it needs the same site captured twice per pack, once
with the lift forced off — a small addition, and the obvious next step.

### Dimensional carriage fog — not measurable from the plot

`fogBox=none` at every site in every run. The fog is only sent for a structure the server considers
occupied, and the editor plot is not one. Two packs produced live fog in an earlier sweep from the
`portal test` twin on a reused world; on a clean world the twin sends no fog box at all. Unresolved.

### Skybox Blocks — DT's own gate, not a measurement

`ShaderCompat.allows` returns false under every pack, so every shader row would read "off" by our own
decision. Only the control's cell could inform, and that site remained the most fragile of the eight.

### The transition — structurally unreachable

`portal test` stamps a twin with no train and nothing that swaps, so `ClientPortalCrossing` reads
`0.000` everywhere. Measuring it needs a real portal crossing in play.

## What this means for the five systems

| System | Verdict |
|---|---|
| Skyboxes in bands | **Broken under every pack.** The dome is drawn and then overwritten by the pack's own atmosphere. Needs the post-composite approach — nothing cheaper will do it. |
| Skybox blocks | Off by DT's gate; unchanged. |
| Carriage fog | Unknown — not reachable from the editor plot. |
| Carriage sky & lighting | Applied under every pack; visibility unproven pending a lift-on/lift-off A/B. |
| Carriage transition | Not measurable without a live crossing. |

The band result is the one that settles a design question: since every pack replaces the sky rather
than tinting it, per-pack overrides in `ShaderCompat.allows` cannot rescue the band skyboxes. Drawing
DT's atmosphere after the pack's composite is the only approach that survives, which is exactly the
expensive option this matrix existed to justify or rule out.

## Evidence

`test-results/gate2-shader-compat-2026-09/` — per-site contact sheets (all packs, control first) and
`upside-down-band-comparison.png`, which shows the control's flat dome against Complementary
Unbound's sun, BSL's starfield and Sildur's black.
