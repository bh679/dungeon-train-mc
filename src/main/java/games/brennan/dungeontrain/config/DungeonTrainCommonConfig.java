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
    public static final int DEFAULT_DISINTEGRATION_END_HOLD_BLOCKS = 5000;
    /** Blocks of normal overworld between repeats of the band (the cycle repeats forever). */
    public static final int MIN_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS = 10000;
    /**
     * Blocks of overworld before the very FIRST void band (measured from startBlocks). Lets the
     * player spawn partway through an overworld stretch so the first leg is shorter than the
     * repeating overworldHold; when ≥ overworldHold there is no early shift (classic behaviour).
     */
    public static final int MIN_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS = 5000;

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
    public static final ModConfigSpec.IntValue DISINTEGRATION_FIRST_OVERWORLD_BLOCKS;

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
        DISINTEGRATION_FIRST_OVERWORLD_BLOCKS = pair.getLeft().disintegrationFirstOverworldBlocks;
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
                .comment("Blocks of End world-gen (floating End-stone islands) at the centre of the band. Default 5000.")
                .defineInRange("disintegrationEndHoldBlocks", DEFAULT_DISINTEGRATION_END_HOLD_BLOCKS,
                        MIN_DISINTEGRATION_END_HOLD_BLOCKS, MAX_DISINTEGRATION_END_HOLD_BLOCKS);
        ModConfigSpec.IntValue disintegrationOverworldHoldBlocks = b
                .comment("Blocks of normal overworld at the start of each cycle. The whole cycle —",
                        "overworld → void → End islands → void → (repeat) — tiles forever along +X from startBlocks.",
                        "One cycle = overworldHold + 4 × fade + 2 × voidHold + endHold. Default 10000.")
                .defineInRange("disintegrationOverworldHoldBlocks", DEFAULT_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS,
                        MIN_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS, MAX_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS);
        ModConfigSpec.IntValue disintegrationFirstOverworldBlocks = b
                .comment("Blocks of overworld before the very FIRST void band, measured from startBlocks. Lets the player",
                        "spawn partway through an overworld stretch so the first leg to the void is shorter than the",
                        "repeating overworldHold (every later overworld stretch is the full overworldHold). When this is",
                        "≥ overworldHold there is no early shift and the classic behaviour applies. Default 5000.")
                .defineInRange("disintegrationFirstOverworldBlocks", DEFAULT_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS,
                        MIN_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS, MAX_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS);
        b.pop();

        return new Holder(defaultPlayerMobSpawnOneIn, defaultPlayerMobBehindSpawnPercent, compatibleTerrain,
                disintegrationEnabled, disintegrationStartBlocks, disintegrationFadeBlocks,
                disintegrationVoidHoldBlocks, disintegrationEndHoldBlocks, disintegrationOverworldHoldBlocks,
                disintegrationFirstOverworldBlocks);
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

    /** Overworld stretch (blocks) before the first band; falls back to the hardcoded default pre-load. */
    public static int getDisintegrationFirstOverworldBlocks() {
        return isLoaded() ? DISINTEGRATION_FIRST_OVERWORLD_BLOCKS.get() : DEFAULT_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS;
    }

    /**
     * Phase shift (blocks) into the repeating cycle at {@code startBlocks}, so the first overworld
     * stretch is {@code firstOverworldBlocks} rather than the full {@code overworldHold}: the single
     * source of truth shared by the worldgen, erosion, mob-spawn, and client-sky paths. Equals
     * {@code max(0, overworldHold − firstOverworld)} — 0 (no shift) when {@code firstOverworld ≥
     * overworldHold}, so the classic "first stretch ≥ recurring" behaviour is preserved.
     */
    public static int getDisintegrationPhaseShiftBlocks() {
        return Math.max(0, getDisintegrationOverworldHoldBlocks() - getDisintegrationFirstOverworldBlocks());
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
                          ModConfigSpec.IntValue disintegrationFirstOverworldBlocks) {}
}
