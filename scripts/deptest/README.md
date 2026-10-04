# Dependency-contract tests

Verifies that Dungeon Train's declared mod dependencies actually behave the way `mods.toml`
claims — that a missing sibling mod produces a clean, itemised error rather than a crash, and
that the version ranges accept and reject the builds they are supposed to.

## Why `./gradlew runServer` cannot do this

`build.gradle` declares the sibling mods as `implementation`, which puts them on the dev
runtime classpath. So under `runServer` / `runClient` the siblings are **always present** — a
declared-but-absent dependency structurally cannot go missing there, and the loader never gets
the chance to reject anything.

This harness installs a real NeoForge server and feeds it real jars in `mods/`. That is the
production mod-loading path: the same FML `ModSorter` a player's client runs, with no dev
classpath. No Minecraft account and no GUI are needed, so it runs unattended.

This matters because the five sibling mods (AIN, AIS, PlayerMob, EnderChestPersistence,
TradeEverything) are
**not bundled** — they are required external downloads. Every existing player hits the
missing-dependency path exactly once, on the update that un-bundled them.

More siblings — KeepTrim, DungeonBackup, SableFenceTrapdoorFix, StreamDetect, DpiBypassDetect,
PigmanVillagers, LostCityTerrainFit and EdibleBackpacks — are **hybrid**: jarJar'd inside
the DT jar (Modrinth + manual installs) *and* declared required + shipped as Includes on
CurseForge, where the CF app installs them as their own jars. NeoForge's JarSelector drops the
nested copy when a top-level one is present; Cases A and G cover both layouts.

## Running

```bash
scripts/deptest/setup.sh     # once — installs a NeoForge server (~200 MB), fetches one fixture
./gradlew build              # the jar under test
scripts/deptest/run-all.sh
```

Everything the harness writes (`server/`, `logs/`, downloaded jars) is gitignored.

Mod versions are read from `gradle.properties` and resolved out of the Gradle cache, so the
harness tests whatever the repo currently declares — there is no second version list to drift.
The NeoForge version follows `neo_version` for the same reason.

## The cases

| Case | `mods/` contents | Expected |
|---|---|---|
| **A** | DT + Sable + all five siblings + Fast Paintings + Moonlight + BetterNether + BetterEnd and their three shared libraries + WWOO/BoP and their libraries + VanillaBackport + Platform + Exposure + Exposure: Polaroid + top-level KeepTrim/DungeonBackup/SableFenceTrapdoorFix (CurseForge-app layout) | Server starts cleanly; prints `JarJar: nested copy skipped, mods/ copy wins` for all three hybrid ids |
| **B** | minus AIN | Fails — `adventureitemnames … Actual version: '[MISSING]'` |
| **C** | DT + Sable only | Fails — names **all five**, with each declared range (the hybrid trio is nested, so never missing) |
| **D** | PlayerMob **above** the floor | Server starts cleanly |
| **E** | PlayerMob **below** the floor | Fails — `Expected range: '[<floor>,)', Actual version: '0.50.0'` |
| **F** | minus Sable | Fails — `Expected range: '[x,x]'` (exact pin, not a minimum); the fence fix's own `[2.0.5,)` Sable floor also fires |
| **G** | DT + Sable + five siblings, no top-level hybrid jars (Modrinth / manual layout) | Server starts cleanly on the nested copies |
| **H** | minus Fast Paintings + Moonlight | Fails — names `fastpaintings` and `moonlight` with their `[x,)` floors |
| **I** | minus BetterNether (its libraries present) | Fails — names `betternether` with its `[x,)` floor |
| **J** | minus BetterEnd (libraries present) | Fails — names `betterend` with its `[x,)` floor |
| **K** | minus WWOO + Biomes O' Plenty (their libraries present) | Fails — names `wwoo` and `biomesoplenty` with their `[x,)` floors |
| **L** | Case A minus Sable Pathfinder (CurseForge layout — it isn't listed there) | Server starts cleanly (`optional` in mods.toml). A, D and G include it, proving its mixins apply against production bytecode |
| **M** | minus VanillaBackport (Platform present) | Fails — names `vanillabackport` with its `[x,)` floor |
| **N** | minus Big Lost City (the only case without it) | Fails — names `big_lost_city` with its `[x,)` floor, twice: requested by `dungeontrain` and by the jarJar'd `lostcityterrainfit` |
| **O** | Case A + What Are They Up To + CoroUtil (a server carrying the modpack's two-sided companion) | Server starts cleanly |
| **P** | WATUT without CoroUtil | Fails — names `coroutil`, requested by `watut`, with its `[1.21.0-1.3.7,)` floor |
| **Q** | minus Exposure + its Polaroid add-on (the only case without them) | Fails — names `exposure` and `exposure_polaroid` with their `[x,)` floors |
| **R** | Case A minus TerraBlender (the only case without it) | Fails — names `terrablender` with DT's own `[x,)` floor (Biomes O' Plenty asks for it too) |

**A is the positive control.** If it fails, every other "failed" result is meaningless — fix A
before reading anything else.

**C is the upgrade path** — what a player sees the first time they launch after updating. The
point is that it is a readable list of what to install, not a stack trace.

**D and E are the range semantics.** DT declares minimums (`[x,)`) for the siblings, so a newer
build must be accepted and an older one rejected. D matters more than it looks: the auto-release
cascade bumps `playermob_version` roughly 22 times per release cycle while `playermob_min_version`
stays put, and D is the standing proof that those bumps don't strand anyone. Under an exact pin
every one of those ticks would break existing installs.

**F is the contrast case.** Sable is exact-pinned because DT is compiled against one physics
build; the siblings are additive and take minimums. Seeing `[2.0.2,2.0.2]` next to `[0.45.0,)`
in the same run is the clearest statement of that difference.

## Client-join test

Mod loading is only half of a two-sided mod's contract: the other half is whether a client and a
server that disagree about it can still connect. The cases above can't show that — it takes a
real join. Stage a case, start the server for good, and point a dev client at it:

```bash
# server.properties (gitignored): a free server-port, online-mode=false, RCON to stop it cleanly
scripts/deptest/run-case.sh "O - modpack companions WATUT + CoroUtil" <keys…>   # stages mods/
(cd scripts/deptest/server && java @libraries/net/neoforged/neoforge/<neo_version>/unix_args.txt --nogui)
./gradlew runClient -PquickJoin=127.0.0.1:<port>      # drop extra jars in run/mods/ for the client side
```

Read the outcome from the server log (`joined the game` / `lost connection`) and the client's
`run/logs/latest.log` (`ModMismatchDisconnectedScreen` names the channel that failed).

What Are They Up To, 2026-10-02 (1.21.0-1.2.7, NeoForge 21.1.230):

| Server | Client | Result |
|---|---|---|
| with WATUT | with WATUT | Joins |
| **without** WATUT | with WATUT (a modpack player) | **Refused** — `watut:nbt_server` / `watut:nbt_client` "missing on the server side, but required on the client" |
| with WATUT | **without** WATUT | **Refused** — the same two channels "missing on the client side, but required on the server" |
| without WATUT | without WATUT | Joins |

So WATUT has to match on both sides: a server for modpack players must carry WATUT + CoroUtil.

## When to run it

Before any release that changes dependency declarations: a new or removed sibling mod, a raised
`<mod>_min_version` floor, a Sable bump, or a NeoForge bump.

Not wired into CI — it downloads a server install and takes minutes, whereas `build.yml`'s
`modpack-checks` job is deliberately fast and stdlib-only. The fast structural guards
(`scripts/modpack/check-pins.py`, `check-relations.py`) run there instead; this is the slow,
on-demand confirmation that the structure is actually true at runtime.

## Post-release launcher checklist

The harness proves the **jar's** contract. It cannot prove the **platform's**: the CurseForge
and Modrinth apps auto-install required dependencies by reading the *published listing*, not the
jar's `mods.toml`. That metadata does not exist until a release publishes, so this part can only
be checked afterwards.

Run through this after the first release that un-bundles a mod:

1. **Modrinth app** — fresh profile, install Dungeon Train from the platform. All five siblings
   should arrive without being asked for; DT should reach the main menu.
2. **CurseForge app** — same, from a new instance.
3. **Both modpacks** — install each and confirm the expected mods are present *and all siblings are
   enabled*. A CurseForge `required:false` entry ships a mod switched **off**; for a hard
   dependency that breaks the pack. See `modpack/README.md` §"Enabled vs disabled by default".
4. **Manual / vanilla NeoForge** — drop in only the DT jar + Sable. Expect Case C's error,
   rendered as a screen.
5. **Check the download counters** on all eight sibling project pages (the hybrid trio only counts CurseForge installs) a day later.

Step 5 is the one that matters. Steps 1–4 verify mechanism; only the counters verify the
*purpose* — un-bundling exists so those mods get credited for the installs they were always
part of. If the counters aren't moving, the dependency declarations aren't reaching players and
the change failed silently, however green everything else looks.
