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

Cells: **works** / **degraded** (visible but wrong) / **absent** (DT asked, nothing appeared) /
**wrong** (actively worse than no effect) / **off** (DT declined to draw).

The `none` row is the control — what the same site looks like in the same world with shaders off.
Every other row is read as a difference from it.

| Pack | Band skybox | Skybox Blocks | Carriage fog | Carriage sky & lighting |
|---|---|---|---|---|
| none (control) | | | | |
| complementary_reimagined | | off | | |
| complementary_unbound | | off | | |
| bsl | | off | | |
| bliss | | off | | |
| solas | | off | | |
| makeup_ultra_fast | | off | | |
| spooklementary | | off | | |
| footage | | off | | |
| insanity | | off | | |
| hysteria | | off | | |
| sildurs | | off | | |

The Skybox Blocks column reads **off** for every pack because `ShaderCompat.allows` gates it off
under any pack — that is DT's own decision, not a measurement of the pack. What the control's cell
says is whether the effect works at all when nothing is in its way.

### Per-system conclusion

To be written once the rows are filled. Each system needs one of:

- **honoured** — leave the vanilla path alone;
- **per-pack override** — the mechanism works on some packs, so gate it in `ShaderCompat.allows`;
- **post-composite** — no pack honours it, so DT must draw its own atmosphere after Iris' composite
  (a fullscreen depth-aware pass and DT's first shipped GLSL — the expensive option, and the reason
  this matrix exists rather than being assumed).
