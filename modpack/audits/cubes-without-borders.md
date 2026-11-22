# Audit: Cubes Without Borders 5.0.0

Audited 2026-11-22 from the Modrinth file `cwb-5.0.0+1.21.0.neoforge.jar` (89 KB), using [`../newmod.md`](../newmod.md).
Candidate for the modpacks as a client-side companion. Nothing is wired yet; this is the pre-Gate 1 review.

| | |
|---|---|
| Mod id | `cwb` (display "Cubes Without Borders"), author Kira-NT |
| Repo | https://github.com/Kira-NT/cubes-without-borders |
| Pins | Modrinth `cubes-without-borders` (ETlrkaYF), version `fmf8ZA2N` (5.0.0+1.21.0.neoforge, 2026-10-24). CurseForge 975120, file `9309036` |
| Same build on both | Yes. SHA-1 `79ac03f9a6928cfb3e4b20b43d4ae1203e73dd2a` on both files |
| Licence | MIT in the jar (`LICENSE.md`, `license = "MIT"`), on Modrinth and on GitHub. CurseForge's licence field not read (cfwidget does not return it). Modpack use is fine under MIT |
| Sides | **Client only.** All four mixins are in the `client` array; the `@Mod` class is `@OnlyIn(CLIENT)`. No network payloads, so it cannot refuse a join either way. **Crashes a dedicated server** if installed there (tested, see below) |
| NeoForge floor | `[21.0.143,)`, Minecraft `[1.21,1.21.2)`. Cleared by `neo_version` 21.1.230 |
| Dependencies | None required. Optional: YACL or Cloth Config for its own config screen; Sodium's config API for its video-settings row |
| Already whitelisted | `cwb` is in the `community_approved` group of `src/main/whitelist/approved_mods.curated.json` (from the relay's mod-request queue), and `approved_hashes.json` → `byMod/cwb` already lists this SHA-1. Fair play needs no change |

## What it does

Adds a **Fullscreen Mode** choice (Off / Exclusive / Borderless / Windowed) in place of the on/off Fullscreen
toggle. Defaults read from `CWBConfig`: fullscreen **Off**, and turning it on picks **Borderless** rather than
vanilla's exclusive mode. Delayed fullscreen on. Pause-on-lost-focus in multiplayer on (vanilla behaviour). Config
is `config/cwb.json`, written by the client.

## Counts

| Category | Count | Action |
|---|---:|---|
| Items, gear, unfair items | 0 | None |
| Mobs | 0 | None |
| Structures / world gen | 0 | None |
| Loot tables | 0 | None |
| Enchantments / effects | 0 | None |
| Workbenches / blocks | 0 | None |
| Advancements | 0 | None |
| Creative tabs | 0 | Nothing to hide |
| Commands / keybinds | 0 | A `--borderless` launch argument only (`MainMixin`) |
| Mixins | 4 | Client only. See conflicts |
| Access transformers | 6 | `Window` x/y/width/height/screenManager, `ScreenManager.monitors` |
| Data under `data/` | 0 | The jar has no data pack content at all |

<details><summary>Mixins (4)</summary>

| Mixin | Target | What it does |
|---|---|---|
| `WindowMixin` | `com.mojang.blaze3d.platform.Window` | Wraps `<init>`'s `getMonitor` and `setMode`, `updateFullscreen`, `changeFullscreenVideoMode`, `close`, and `glfwSetWindowMonitor`/`glfwGetWindowMonitor` calls. This is the borderless implementation |
| `VideoSettingsScreenMixin` | vanilla `VideoSettingsScreen.options` | Swaps the Fullscreen boolean for the four-way enum. Mostly unseen in the pack, since the Options button opens Sodium's screen instead (DT's `LagTips` still opens the vanilla one) |
| `GuiMixin` | `Minecraft.setOverlay` (delayed fullscreen) and `Minecraft.pauseGame` (`setScreen`, ordinal 1, the multiplayer branch, `require = 0`) | Can skip the multiplayer pause screen when the window is unfocused. Only when its option is turned **off**; default on |
| `MainMixin` | `net.minecraft.client.main.Main` | Adds the `--borderless` argument |

</details>

## Conflicts

| Check | Result |
|---|---|
| Existing mods | No duplicate id. Its `incompatible` list is three other borderless mods (`borderlessmining`, `borderlesswindow`, `concentration`); none is in the pack. No keybinds |
| Mixin overlap, DT | DT has no mixin into `Window`, `Main`, `VideoSettingsScreen` or `Minecraft.pauseGame`/`setOverlay`. DT's `MinecraftFramerateThrottleMixin` (`getFramerateLimit`) and `MinecraftInitialScreensMixin` hit other methods |
| Mixin overlap, pack | Read from the pinned jars. Sodium's two `WindowMixin`s and Iris's `MixinWindow` inject at `glfwCreateWindow`, `glfwDefaultWindowHints`, `flipFrame` and `ImmediateWindowHandler`, not at CWB's points. Iris's `MixinVideoSettingsScreen` hits `addOptions`, not `options`. ModernFix touches none of the targets. A client boot with the real pack has **not** been run |
| Sodium 0.8.12 | *Static read, not booted.* Sodium finds CWB by its `@ConfigEntryPointForge("cwb")` annotation (Sodium's NeoForge `ConfigLoaderForge` scans for it). CWB replaces `sodium:general.fullscreen`, an id 0.8.12's `SodiumConfigBuilder` does define, with its four-way option, so the picker should land on Sodium's video page. CWB first calls `registerOptionReplacement(id, option, priority)`, which 0.8.12 lacks, then catches the `Throwable` and retries with the two-argument form 0.8.12 has. Two of its replacement ids (`fullscreen_mode`, `mac_fullscreen_menu_visibility`) don't exist in 0.8.12. Whether Sodium skips or rejects those is **unconfirmed**. If the picker doesn't appear, players have no in-game way to choose a mode (fullscreen would go borderless with no way back to exclusive), so Gate 2 must check it |
| Config screen | Opens only with YACL or Cloth Config. YACL is in the Modrinth pack only (`modrinth_only`), so on CurseForge the Mods-list config button is absent. The main setting still sits on Sodium's video page on both platforms |
| World gen, loot, vanilla data, economy | Nothing |

## DT systems it reaches

| System | Result |
|---|---|
| AFK auto-pause | `AfkAutoPause` calls `mc.pauseGame(false)`. In multiplayer with the window unfocused, CWB skips the pause screen **only if** a player turns off "Pause on Lost Focus (Multiplayer)". DT would then report the player paused (`PlayerPausedPacket`) with no pause screen up. Off the default path; low |
| Tribute TV "fullscreen" chip | Unrelated: it lifts a screen onto the window and doesn't touch the window mode |
| `LagTips` | Builds vanilla `new VideoSettingsScreen(...)` directly. Sodium's `OptionsScreenMixin` only swaps the button in the Options screen, so this path shows the vanilla screen, where `VideoSettingsScreenMixin` gives the four-way row. Consistent either way |
| Sable, mob systems, spawns | Not reached |
| Fair play | Already approved (curated list plus hash record) |
| Commands, HUD, network | No commands, no overlay, no payloads. A jar-wide grep finds no `java/net`, URL or `KeyMapping` reference, so no outgoing calls, updater, telemetry or keybinds |
| Text | 6 locales (`en_us`, `ru_ru`, `uk_ua`, `zh_cn`, `zh_hk`, `zh_tw`) against DT's 20. Its tooltips are English in the other 14 |
| Saves | Nothing in the world. Removing it only leaves `config/cwb.json` behind |

## Performance

| Cost | Result |
|---|---|
| Download size | 89 KB, no libraries. Negligible against the pack |
| Boot / world load / tick | **Not measured.** Read from the jar instead: no tick handlers, no events beyond the config-screen registration, no world-side code. Its only runtime work is at window creation and fullscreen toggles |
| Rendering | Per its own tooltips, Borderless on Windows and macOS can add compositor V-Sync latency compared with exclusive fullscreen, and Windows "windowed" can lose frames. This is the player's choice and only applies when they go fullscreen |

## Dedicated server

**It crashes a dedicated server.** The `@Mod` class has no `dist` and is `@OnlyIn(CLIENT)`. A bare NeoForge
21.1.230 server with only this jar refuses to start:

```
RuntimeDistCleaner: Attempted to load class dev/kirant/cwb/CWB$NeoForge for invalid dist DEDICATED_SERVER
ModLoadingException: Cubes Without Borders (cwb) has class loading errors
```

Where that matters:

| Pack | Server-side handling | Risk |
|---|---|---|
| Modrinth `.mrpack` | Modrinth lists the project `server_side: unsupported`, so `build-mrpack.py` writes `env.server = unsupported` and server installs skip it | None |
| CurseForge | File 9309036 is tagged **both `Client` and `Server`**. The pack's existing client-only mods (Sodium, Iris, Crash Assistant) are tagged `Client` only. Any tool that builds a server's mod list from CurseForge's side tags would include CWB and skip those | Server fails to boot, if such a tool is used. No DT server pack is published, and how hosts assemble DT servers wasn't checked |

**Not a new failure for a copied `mods/` folder.** The same bare server booted with the pack's Sodium, Iris, Crash
Assistant and Punchy, and **no** CWB, also refuses to start: Sodium's pre-launch check throws
`NoClassDefFoundError: org/lwjgl/Version`. A server made by copying a client's `mods/` is already broken by Sodium.
CWB only adds a failure where server mods are picked by CurseForge's side tags.

## Recommendation

On the client (static read, not booted) it touches no gameplay, leaves the default window mode unchanged (fullscreen
stays off), and only changes what turning fullscreen on does. Options:

1. **Both packs, enabled** (`required: true`), and report the missing `dist` upstream: the fix is one line,
   `@Mod(value = "cwb", dist = Dist.CLIENT)`, at https://github.com/Kira-NT/cubes-without-borders/issues. The server
   hazard only exists where CurseForge side tags pick server mods.
2. **Modrinth pack only** (`modrinth_only: true`) until upstream fixes the `dist`. Zero server risk. CurseForge
   players go without it.

Whatever ships: keep the fair-play whitelist as it is, since `cwb` is already approved. Gate 2 then boots a client
with the real pack (Sodium, Iris, ModernFix) and tries each mode on macOS, plus Windows if available. Check that
Sodium's video page shows the four-way row and that the log has no Sodium option-replacement warning.

### Questions for Gate 1

- Both packs now, or Modrinth only until upstream fixes the `dist`?
- Enabled by default, or opt-in?
- On CurseForge (if shipped there), is a missing config button acceptable, or should Cloth Config ship too? Sodium's
  video page already carries the main setting.
