package games.brennan.dungeontrain.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Common-scoped Forge config for gameplay tunables that must be readable both
 * on the title screen (no world loaded) and on a dedicated server.
 *
 * <p>Persists at {@code <minecraft>/config/dungeontrain-common.toml} and is
 * registered alongside {@link DungeonTrainConfig} (SERVER, per-save) and
 * {@link ClientDisplayConfig} (CLIENT) from
 * {@link games.brennan.dungeontrain.DungeonTrain}.</p>
 *
 * <p>Holds the <b>global default</b> PlayerMob group-spawn rate — tier one of a
 * two-tier system. This COMMON value is the fallback used by any world that has
 * not set a per-world override (tier two, stored in
 * {@link games.brennan.dungeontrain.world.DungeonTrainWorldData}). Because
 * COMMON configs load early on the client, the title-screen Mods→Config screen
 * can edit it with no world loaded; changing it affects future new worlds (and
 * existing worlds that never set an in-world override).</p>
 */
public final class DungeonTrainCommonConfig {

    /** 1-in-N chance that a settled carriage group spawns a PlayerMob. 0 disables, 1 = every group. */
    public static final int MIN_PLAYER_MOB_SPAWN_ONE_IN = 0;
    public static final int MAX_PLAYER_MOB_SPAWN_ONE_IN = 10_000;
    public static final int DEFAULT_PLAYER_MOB_SPAWN_ONE_IN = 10;

    /**
     * Percent chance, rolled each time a PlayerMob spawns, that an extra PlayerMob is ALSO spawned
     * one full carriage group BEHIND a riding player and set to march the player's travel direction.
     * 0 disables; 100 = always.
     */
    public static final int MIN_PLAYER_MOB_BEHIND_SPAWN_PERCENT = 0;
    public static final int MAX_PLAYER_MOB_BEHIND_SPAWN_PERCENT = 100;
    public static final int DEFAULT_PLAYER_MOB_BEHIND_SPAWN_PERCENT = 15;

    /** Global DEFAULT Compatible Terrain mode for new worlds. false = classic Dungeon Train terrain. */
    public static final boolean DEFAULT_COMPATIBLE_TERRAIN = false;

    /**
     * World disintegration band — the world breaks apart into void past a carriage
     * count, then reassembles. Always-on core mechanic with a kill switch.
     */
    public static final boolean DEFAULT_DISINTEGRATION_ENABLED = true;
    /** Blocks from spawn (world X=0) before the first band begins — the pattern is anchored here. */
    public static final int MIN_DISINTEGRATION_START_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_START_BLOCKS = 100_000_000;
    public static final int DEFAULT_DISINTEGRATION_START_BLOCKS = 0;
    /** Blocks over which terrain fades in/out of void at each band edge. */
    public static final int MIN_DISINTEGRATION_FADE_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_FADE_BLOCKS = 10_000;
    public static final int DEFAULT_DISINTEGRATION_FADE_BLOCKS = 120;
    /** Blocks of pure-void buffer between the overworld fade and the End on each side. */
    public static final int MIN_DISINTEGRATION_VOID_HOLD_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_VOID_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_DISINTEGRATION_VOID_HOLD_BLOCKS = 500;
    /** Blocks of End world-gen (floating End-stone islands) at the centre of the band. */
    public static final int MIN_DISINTEGRATION_END_HOLD_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_END_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_DISINTEGRATION_END_HOLD_BLOCKS = 500;
    /** Blocks of normal overworld between repeats of the band (the cycle repeats forever). */
    public static final int MIN_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS = 500;

    /**
     * Nether transition band — a second, independent looping phase (parallel to the
     * disintegration/End band). Terrain swells into a world-height mountain the train
     * tunnels through, the far side is real Nether terrain, then it mirrors back. The
     * End band always wins any overlap (the nether band yields those columns).
     */
    public static final boolean DEFAULT_NETHER_TRANSITION_ENABLED = true;
    /** Length (blocks) of EACH mountain stage — stage 1 (×1), stage 2 (×s2), stage 3 (×s3). ~80 = 5 chunks. */
    public static final int MIN_NETHER_STAGE_BLOCKS = 0;
    public static final int MAX_NETHER_STAGE_BLOCKS = 100_000_000;
    public static final int DEFAULT_NETHER_STAGE_BLOCKS = 80;
    /** Comma-separated heightmap multipliers, one per mountain stage (stage 1 = the first value, 1 = natural). */
    public static final String DEFAULT_NETHER_STAGE_MULTIPLIERS = "1,2,4,8,15";
    /** Mountain relief amplitude (blocks) at ×1 — scaled by the stage multiplier for the peak height. */
    public static final int MIN_NETHER_BASE_RELIEF_BLOCKS = 0;
    public static final int MAX_NETHER_BASE_RELIEF_BLOCKS = 1000;
    public static final int DEFAULT_NETHER_BASE_RELIEF_BLOCKS = 16;
    /** Blocks of full-height mega-mountain plateau on each side of the nether core (the tunnel zone). */
    public static final int MIN_NETHER_MOUNTAIN_HOLD_BLOCKS = 0;
    public static final int MAX_NETHER_MOUNTAIN_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_NETHER_MOUNTAIN_HOLD_BLOCKS = 48;
    /** Blocks over which the mountain rock crossfades to netherrack on each side of the core. */
    public static final int MIN_NETHER_CORE_FADE_BLOCKS = 0;
    public static final int MAX_NETHER_CORE_FADE_BLOCKS = 10_000;
    public static final int DEFAULT_NETHER_CORE_FADE_BLOCKS = 120;
    /** Blocks of real Nether world-gen at the centre of the band. */
    public static final int MIN_NETHER_CORE_HOLD_BLOCKS = 0;
    public static final int MAX_NETHER_CORE_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_NETHER_CORE_HOLD_BLOCKS = 400;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DEFAULT_PLAYER_MOB_SPAWN;
    public static final ModConfigSpec.IntValue DEFAULT_PLAYER_MOB_BEHIND_SPAWN;
    public static final ModConfigSpec.BooleanValue COMPATIBLE_TERRAIN;
    public static final ModConfigSpec.BooleanValue DISINTEGRATION_ENABLED;
    public static final ModConfigSpec.IntValue DISINTEGRATION_START_BLOCKS;
    public static final ModConfigSpec.IntValue DISINTEGRATION_FADE_BLOCKS;
    public static final ModConfigSpec.IntValue DISINTEGRATION_VOID_HOLD_BLOCKS;
    public static final ModConfigSpec.IntValue DISINTEGRATION_END_HOLD_BLOCKS;
    public static final ModConfigSpec.IntValue DISINTEGRATION_OVERWORLD_HOLD_BLOCKS;
    public static final ModConfigSpec.BooleanValue NETHER_TRANSITION_ENABLED;
    public static final ModConfigSpec.IntValue NETHER_STAGE_BLOCKS;
    public static final ModConfigSpec.ConfigValue<String> NETHER_STAGE_MULTIPLIERS;
    public static final ModConfigSpec.IntValue NETHER_BASE_RELIEF_BLOCKS;
    public static final ModConfigSpec.IntValue NETHER_MOUNTAIN_HOLD_BLOCKS;
    public static final ModConfigSpec.IntValue NETHER_CORE_FADE_BLOCKS;
    public static final ModConfigSpec.IntValue NETHER_CORE_HOLD_BLOCKS;

    static {
        Pair<Holder, ModConfigSpec> pair = new ModConfigSpec.Builder()
                .configure(DungeonTrainCommonConfig::build);
        SPEC = pair.getRight();
        DEFAULT_PLAYER_MOB_SPAWN = pair.getLeft().defaultPlayerMobSpawnOneIn;
        DEFAULT_PLAYER_MOB_BEHIND_SPAWN = pair.getLeft().defaultPlayerMobBehindSpawnPercent;
        COMPATIBLE_TERRAIN = pair.getLeft().compatibleTerrain;
        DISINTEGRATION_ENABLED = pair.getLeft().disintegrationEnabled;
        DISINTEGRATION_START_BLOCKS = pair.getLeft().disintegrationStartBlocks;
        DISINTEGRATION_FADE_BLOCKS = pair.getLeft().disintegrationFadeBlocks;
        DISINTEGRATION_VOID_HOLD_BLOCKS = pair.getLeft().disintegrationVoidHoldBlocks;
        DISINTEGRATION_END_HOLD_BLOCKS = pair.getLeft().disintegrationEndHoldBlocks;
        DISINTEGRATION_OVERWORLD_HOLD_BLOCKS = pair.getLeft().disintegrationOverworldHoldBlocks;
        NETHER_TRANSITION_ENABLED = pair.getLeft().netherTransitionEnabled;
        NETHER_STAGE_BLOCKS = pair.getLeft().netherStageBlocks;
        NETHER_STAGE_MULTIPLIERS = pair.getLeft().netherStageMultipliers;
        NETHER_BASE_RELIEF_BLOCKS = pair.getLeft().netherBaseReliefBlocks;
        NETHER_MOUNTAIN_HOLD_BLOCKS = pair.getLeft().netherMountainHoldBlocks;
        NETHER_CORE_FADE_BLOCKS = pair.getLeft().netherCoreFadeBlocks;
        NETHER_CORE_HOLD_BLOCKS = pair.getLeft().netherCoreHoldBlocks;
    }

    private DungeonTrainCommonConfig() {}

    private static Holder build(ModConfigSpec.Builder b) {
        b.push("spawning");
        ModConfigSpec.IntValue defaultPlayerMobSpawnOneIn = b
                .comment("Global DEFAULT 1-in-N chance that each settled carriage group spawns a PlayerMob (Interactive Player Mobs). "
                        + "Used by any world that has not set a per-world override in-game (Mods -> Dungeon Train -> Config while in a world). "
                        + "Default 10 (~1-in-10); set to 1 for a PlayerMob on every group (testing); 0 disables.")
                .defineInRange("defaultPlayerMobSpawnOneIn", DEFAULT_PLAYER_MOB_SPAWN_ONE_IN,
                        MIN_PLAYER_MOB_SPAWN_ONE_IN, MAX_PLAYER_MOB_SPAWN_ONE_IN);
        ModConfigSpec.IntValue defaultPlayerMobBehindSpawnPercent = b
                .comment("Global DEFAULT percent chance, rolled each time a PlayerMob spawns, that an extra PlayerMob ALSO spawns "
                        + "one full carriage group BEHIND a riding player and marches the player's direction of travel (catching up "
                        + "from the rear). Used by any world that has not set a per-world override. Default 15; 0 disables; 100 = always.")
                .defineInRange("defaultPlayerMobBehindSpawnPercent", DEFAULT_PLAYER_MOB_BEHIND_SPAWN_PERCENT,
                        MIN_PLAYER_MOB_BEHIND_SPAWN_PERCENT, MAX_PLAYER_MOB_BEHIND_SPAWN_PERCENT);
        b.pop();

        b.push("worldgen");
        ModConfigSpec.BooleanValue compatibleTerrain = b
                .comment("Compatible Terrain mode DEFAULT for new worlds. When true, the Dungeon Train overworld",
                        "generates from vanilla minecraft:overworld noise + dimension type instead of Dungeon Train's",
                        "custom raised-floor terrain, so terrain mods (Tectonic, Terralith) and Distant Horizons take effect.",
                        "false = classic Dungeon Train terrain. Applies to NEW worlds only; existing worlds keep the terrain",
                        "they were created with. The matching terrain mod must also be installed for any visible change.")
                .define("defaultCompatibleTerrain", DEFAULT_COMPATIBLE_TERRAIN);

        ModConfigSpec.BooleanValue disintegrationEnabled = b
                .comment("World disintegration band. When true (default), past a carriage count the world breaks apart",
                        "and the run crosses a once-per-run gap: Overworld → Void → End world-gen (floating End-stone",
                        "islands under the End sky) → Void → Overworld. The train rides level the whole way (its bed +",
                        "rails are preserved); the surrounding world and the track's support pillars erode away. Set",
                        "false to disable entirely.")
                .define("disintegrationEnabled", DEFAULT_DISINTEGRATION_ENABLED);
        ModConfigSpec.IntValue disintegrationStartBlocks = b
                .comment("Blocks from spawn (world X=0) where the repeating band pattern is anchored. The cycle",
                        "starts in the overworld, so the first overworldHold blocks past this point are normal terrain.",
                        "Measured in blocks from spawn — independent of carriage size or train position.",
                        "Changing it only affects chunks generated afterwards.")
                .defineInRange("disintegrationStartBlocks", DEFAULT_DISINTEGRATION_START_BLOCKS,
                        MIN_DISINTEGRATION_START_BLOCKS, MAX_DISINTEGRATION_START_BLOCKS);
        ModConfigSpec.IntValue disintegrationFadeBlocks = b
                .comment("Blocks over which each transition fades (overworld↔void and void↔End). Larger = more gradual,",
                        "cinematic transitions. Default 120.")
                .defineInRange("disintegrationFadeBlocks", DEFAULT_DISINTEGRATION_FADE_BLOCKS,
                        MIN_DISINTEGRATION_FADE_BLOCKS, MAX_DISINTEGRATION_FADE_BLOCKS);
        ModConfigSpec.IntValue disintegrationVoidHoldBlocks = b
                .comment("Blocks of pure, empty void on each side of the End — the clear 'Void' phase between the",
                        "overworld and the End islands. Make this bigger for more void before the islands. Default 500.")
                .defineInRange("disintegrationVoidHoldBlocks", DEFAULT_DISINTEGRATION_VOID_HOLD_BLOCKS,
                        MIN_DISINTEGRATION_VOID_HOLD_BLOCKS, MAX_DISINTEGRATION_VOID_HOLD_BLOCKS);
        ModConfigSpec.IntValue disintegrationEndHoldBlocks = b
                .comment("Blocks of End world-gen (floating End-stone islands) at the centre of the band. Default 500.")
                .defineInRange("disintegrationEndHoldBlocks", DEFAULT_DISINTEGRATION_END_HOLD_BLOCKS,
                        MIN_DISINTEGRATION_END_HOLD_BLOCKS, MAX_DISINTEGRATION_END_HOLD_BLOCKS);
        ModConfigSpec.IntValue disintegrationOverworldHoldBlocks = b
                .comment("Blocks of normal overworld BEFORE each special phase (used twice per cycle: before the",
                        "Nether phase and before the End phase). The single cycle tiles forever along +X from",
                        "startBlocks: OW → Nether transition → Nether → Nether transition → OW → Void → End islands",
                        "→ Void → (repeat). Default 500.")
                .defineInRange("disintegrationOverworldHoldBlocks", DEFAULT_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS,
                        MIN_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS, MAX_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS);

        ModConfigSpec.BooleanValue netherTransitionEnabled = b
                .comment("Nether transition phase — part of the single repeating world-gen cycle (alongside the End",
                        "phase). Along +X the overworld swells into a world-height mountain the train tunnels through,",
                        "the far side is real Nether world-gen, then it mirrors back. The full cycle is:",
                        "OW → Nether transition → Nether → Nether transition → OW → Void → End islands → Void → (repeat).",
                        "The nether phase comes first, the End phase second; both share the disintegration anchor +",
                        "overworld hold. Set false to drop the nether phase from the cycle.")
                .define("netherTransitionEnabled", DEFAULT_NETHER_TRANSITION_ENABLED);
        ModConfigSpec.IntValue netherStageBlocks = b
                .comment("Length (blocks) of EACH of the 3 mountain stages. The natural overworld heightmap is",
                        "amplified progressively: stage 1 ×1 (a real-looking mountain biome), stage 2 ×stage2Multiplier,",
                        "stage 3 ×stage3Multiplier (the mega-mountain). ~80 = 5 chunks each. Default 80.")
                .defineInRange("netherStageBlocks", DEFAULT_NETHER_STAGE_BLOCKS,
                        MIN_NETHER_STAGE_BLOCKS, MAX_NETHER_STAGE_BLOCKS);
        ModConfigSpec.ConfigValue<String> netherStageMultipliers = b
                .comment("Comma-separated heightmap multipliers, one per mountain stage (each netherStageBlocks long).",
                        "Stage 1 is the first value (1 = the natural terrain). The natural height above sea level is",
                        "scaled toward each successive multiplier, so the mountains grow taller stage by stage; the",
                        "last value is then held across the mega-mountain. Default 1,2,4,8,15.")
                .define("netherStageMultipliers", DEFAULT_NETHER_STAGE_MULTIPLIERS);
        ModConfigSpec.IntValue netherBaseReliefBlocks = b
                .comment("Mountain relief amplitude (blocks) at ×1. A synthetic ridged heightmap (blended with the",
                        "natural terrain) is scaled by this × the stage multiplier, so the tallest stage reaches about",
                        "baseRelief × (largest multiplier) blocks above sea level. Keep that product under ~250 to avoid",
                        "flat tops clamped at the world ceiling. Default 16 (×15 → ~240).")
                .defineInRange("netherBaseReliefBlocks", DEFAULT_NETHER_BASE_RELIEF_BLOCKS,
                        MIN_NETHER_BASE_RELIEF_BLOCKS, MAX_NETHER_BASE_RELIEF_BLOCKS);
        ModConfigSpec.IntValue netherMountainHoldBlocks = b
                .comment("Blocks of full-strength mega-mountain (stage-3 multiplier held) on each side of the nether",
                        "core — the stretch the train tunnels through before the world turns to nether. Default 48.")
                .defineInRange("netherMountainHoldBlocks", DEFAULT_NETHER_MOUNTAIN_HOLD_BLOCKS,
                        MIN_NETHER_MOUNTAIN_HOLD_BLOCKS, MAX_NETHER_MOUNTAIN_HOLD_BLOCKS);
        ModConfigSpec.IntValue netherCoreFadeBlocks = b
                .comment("Blocks over which the mountain rock crossfades to netherrack on each side of the core (the",
                        "'netherrack' transition stage). Default 120.")
                .defineInRange("netherCoreFadeBlocks", DEFAULT_NETHER_CORE_FADE_BLOCKS,
                        MIN_NETHER_CORE_FADE_BLOCKS, MAX_NETHER_CORE_FADE_BLOCKS);
        ModConfigSpec.IntValue netherCoreHoldBlocks = b
                .comment("Blocks of real Nether world-gen (sampled from the Nether dimension) at the centre of the",
                        "band. Default 400.")
                .defineInRange("netherCoreHoldBlocks", DEFAULT_NETHER_CORE_HOLD_BLOCKS,
                        MIN_NETHER_CORE_HOLD_BLOCKS, MAX_NETHER_CORE_HOLD_BLOCKS);
        b.pop();

        return new Holder(defaultPlayerMobSpawnOneIn, defaultPlayerMobBehindSpawnPercent, compatibleTerrain,
                disintegrationEnabled, disintegrationStartBlocks, disintegrationFadeBlocks,
                disintegrationVoidHoldBlocks, disintegrationEndHoldBlocks, disintegrationOverworldHoldBlocks,
                netherTransitionEnabled, netherStageBlocks, netherStageMultipliers, netherBaseReliefBlocks,
                netherMountainHoldBlocks, netherCoreFadeBlocks, netherCoreHoldBlocks);
    }

    /**
     * COMMON config loads early in the client lifecycle but isn't guaranteed
     * ready before the first frame draws. Callers must guard reads through the
     * getter below; direct {@code SPEC.isLoaded()} use is fine for write paths
     * that should silently no-op pre-load.
     */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    /** Global default 1-in-N spawn rate; falls back to the hardcoded default pre-load. */
    public static int getDefaultPlayerMobSpawnOneIn() {
        return isLoaded() ? DEFAULT_PLAYER_MOB_SPAWN.get() : DEFAULT_PLAYER_MOB_SPAWN_ONE_IN;
    }

    public static void setDefaultPlayerMobSpawnOneIn(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(MIN_PLAYER_MOB_SPAWN_ONE_IN, Math.min(MAX_PLAYER_MOB_SPAWN_ONE_IN, value));
        DEFAULT_PLAYER_MOB_SPAWN.set(clamped);
        DEFAULT_PLAYER_MOB_SPAWN.save();
    }

    /** Global default behind-spawn percent chance; falls back to the hardcoded default pre-load. */
    public static int getDefaultPlayerMobBehindSpawnPercent() {
        return isLoaded() ? DEFAULT_PLAYER_MOB_BEHIND_SPAWN.get() : DEFAULT_PLAYER_MOB_BEHIND_SPAWN_PERCENT;
    }

    public static void setDefaultPlayerMobBehindSpawnPercent(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(MIN_PLAYER_MOB_BEHIND_SPAWN_PERCENT, Math.min(MAX_PLAYER_MOB_BEHIND_SPAWN_PERCENT, value));
        DEFAULT_PLAYER_MOB_BEHIND_SPAWN.set(clamped);
        DEFAULT_PLAYER_MOB_BEHIND_SPAWN.save();
    }

    /** Global default Compatible Terrain mode for new worlds; falls back to the hardcoded default pre-load. */
    public static boolean getDefaultCompatibleTerrain() {
        return isLoaded() ? COMPATIBLE_TERRAIN.get() : DEFAULT_COMPATIBLE_TERRAIN;
    }

    public static void setDefaultCompatibleTerrain(boolean value) {
        if (!isLoaded()) return;
        COMPATIBLE_TERRAIN.set(value);
        COMPATIBLE_TERRAIN.save();
    }

    /** Whether the world disintegration band is active; falls back to the hardcoded default pre-load. */
    public static boolean isDisintegrationEnabled() {
        return isLoaded() ? DISINTEGRATION_ENABLED.get() : DEFAULT_DISINTEGRATION_ENABLED;
    }

    /** Blocks from spawn where the band pattern is anchored; falls back to the hardcoded default pre-load. */
    public static int getDisintegrationStartBlocks() {
        return isLoaded() ? DISINTEGRATION_START_BLOCKS.get() : DEFAULT_DISINTEGRATION_START_BLOCKS;
    }

    /** Fade-in/out span (blocks) at each band edge; falls back to the hardcoded default pre-load. */
    public static int getDisintegrationFadeBlocks() {
        return isLoaded() ? DISINTEGRATION_FADE_BLOCKS.get() : DEFAULT_DISINTEGRATION_FADE_BLOCKS;
    }

    /** Pure-void buffer span (blocks) on each side of the End; falls back to the hardcoded default pre-load. */
    public static int getDisintegrationVoidHoldBlocks() {
        return isLoaded() ? DISINTEGRATION_VOID_HOLD_BLOCKS.get() : DEFAULT_DISINTEGRATION_VOID_HOLD_BLOCKS;
    }

    /** End world-gen core span (blocks); falls back to the hardcoded default pre-load. */
    public static int getDisintegrationEndHoldBlocks() {
        return isLoaded() ? DISINTEGRATION_END_HOLD_BLOCKS.get() : DEFAULT_DISINTEGRATION_END_HOLD_BLOCKS;
    }

    /** Overworld stretch (blocks) between band repeats; falls back to the hardcoded default pre-load. */
    public static int getDisintegrationOverworldHoldBlocks() {
        return isLoaded() ? DISINTEGRATION_OVERWORLD_HOLD_BLOCKS.get() : DEFAULT_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS;
    }

    /** Whether the nether transition band is active; falls back to the hardcoded default pre-load. */
    public static boolean isNetherTransitionEnabled() {
        return isLoaded() ? NETHER_TRANSITION_ENABLED.get() : DEFAULT_NETHER_TRANSITION_ENABLED;
    }

    /** Length (blocks) of each mountain stage; falls back to the hardcoded default pre-load. */
    public static int getNetherStageBlocks() {
        return isLoaded() ? NETHER_STAGE_BLOCKS.get() : DEFAULT_NETHER_STAGE_BLOCKS;
    }

    /** Mountain relief amplitude (blocks) at ×1; falls back to the hardcoded default pre-load. */
    public static int getNetherBaseReliefBlocks() {
        return isLoaded() ? NETHER_BASE_RELIEF_BLOCKS.get() : DEFAULT_NETHER_BASE_RELIEF_BLOCKS;
    }

    /** Per-stage heightmap multipliers (parsed, each ≥ 1); falls back to the default on empty/garbage. */
    public static int[] getNetherStageMultipliers() {
        String raw = isLoaded() ? NETHER_STAGE_MULTIPLIERS.get() : DEFAULT_NETHER_STAGE_MULTIPLIERS;
        int[] parsed = parseMultipliers(raw);
        return parsed != null ? parsed : parseMultipliers(DEFAULT_NETHER_STAGE_MULTIPLIERS);
    }

    private static int[] parseMultipliers(String raw) {
        if (raw == null) return null;
        java.util.List<Integer> vals = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                vals.add(Math.max(1, Integer.parseInt(part.trim())));
            } catch (NumberFormatException ignored) {
                // skip non-numeric entries
            }
        }
        if (vals.isEmpty()) return null;
        int[] arr = new int[vals.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = vals.get(i);
        return arr;
    }

    /** Full-strength mega-mountain plateau span (blocks) on each side of the core; falls back pre-load. */
    public static int getNetherMountainHoldBlocks() {
        return isLoaded() ? NETHER_MOUNTAIN_HOLD_BLOCKS.get() : DEFAULT_NETHER_MOUNTAIN_HOLD_BLOCKS;
    }

    /** Mountain→netherrack crossfade span (blocks) on each side of the core; falls back pre-load. */
    public static int getNetherCoreFadeBlocks() {
        return isLoaded() ? NETHER_CORE_FADE_BLOCKS.get() : DEFAULT_NETHER_CORE_FADE_BLOCKS;
    }

    /** Real-Nether core span (blocks); falls back to the hardcoded default pre-load. */
    public static int getNetherCoreHoldBlocks() {
        return isLoaded() ? NETHER_CORE_HOLD_BLOCKS.get() : DEFAULT_NETHER_CORE_HOLD_BLOCKS;
    }

    private record Holder(ModConfigSpec.IntValue defaultPlayerMobSpawnOneIn,
                          ModConfigSpec.IntValue defaultPlayerMobBehindSpawnPercent,
                          ModConfigSpec.BooleanValue compatibleTerrain,
                          ModConfigSpec.BooleanValue disintegrationEnabled,
                          ModConfigSpec.IntValue disintegrationStartBlocks,
                          ModConfigSpec.IntValue disintegrationFadeBlocks,
                          ModConfigSpec.IntValue disintegrationVoidHoldBlocks,
                          ModConfigSpec.IntValue disintegrationEndHoldBlocks,
                          ModConfigSpec.IntValue disintegrationOverworldHoldBlocks,
                          ModConfigSpec.BooleanValue netherTransitionEnabled,
                          ModConfigSpec.IntValue netherStageBlocks,
                          ModConfigSpec.ConfigValue<String> netherStageMultipliers,
                          ModConfigSpec.IntValue netherBaseReliefBlocks,
                          ModConfigSpec.IntValue netherMountainHoldBlocks,
                          ModConfigSpec.IntValue netherCoreFadeBlocks,
                          ModConfigSpec.IntValue netherCoreHoldBlocks) {}
}
