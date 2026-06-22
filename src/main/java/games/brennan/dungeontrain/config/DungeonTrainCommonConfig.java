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
    /** Carriages of forward travel before the world starts breaking apart. */
    public static final int MIN_DISINTEGRATION_START_CARRIAGES = 0;
    public static final int MAX_DISINTEGRATION_START_CARRIAGES = 1_000_000;
    public static final int DEFAULT_DISINTEGRATION_START_CARRIAGES = 250;
    /** Blocks over which terrain fades in/out of void at each band edge. */
    public static final int MIN_DISINTEGRATION_FADE_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_FADE_BLOCKS = 10_000;
    public static final int DEFAULT_DISINTEGRATION_FADE_BLOCKS = 100;
    /** Blocks of fully-void core (only the floating track) between the two fades. */
    public static final int MIN_DISINTEGRATION_CORE_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_CORE_BLOCKS = 100_000;
    public static final int DEFAULT_DISINTEGRATION_CORE_BLOCKS = 60;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue DEFAULT_PLAYER_MOB_SPAWN;
    public static final ModConfigSpec.IntValue DEFAULT_PLAYER_MOB_BEHIND_SPAWN;
    public static final ModConfigSpec.BooleanValue COMPATIBLE_TERRAIN;
    public static final ModConfigSpec.BooleanValue DISINTEGRATION_ENABLED;
    public static final ModConfigSpec.IntValue DISINTEGRATION_START_CARRIAGES;
    public static final ModConfigSpec.IntValue DISINTEGRATION_FADE_BLOCKS;
    public static final ModConfigSpec.IntValue DISINTEGRATION_CORE_BLOCKS;

    static {
        Pair<Holder, ModConfigSpec> pair = new ModConfigSpec.Builder()
                .configure(DungeonTrainCommonConfig::build);
        SPEC = pair.getRight();
        DEFAULT_PLAYER_MOB_SPAWN = pair.getLeft().defaultPlayerMobSpawnOneIn;
        DEFAULT_PLAYER_MOB_BEHIND_SPAWN = pair.getLeft().defaultPlayerMobBehindSpawnPercent;
        COMPATIBLE_TERRAIN = pair.getLeft().compatibleTerrain;
        DISINTEGRATION_ENABLED = pair.getLeft().disintegrationEnabled;
        DISINTEGRATION_START_CARRIAGES = pair.getLeft().disintegrationStartCarriages;
        DISINTEGRATION_FADE_BLOCKS = pair.getLeft().disintegrationFadeBlocks;
        DISINTEGRATION_CORE_BLOCKS = pair.getLeft().disintegrationCoreBlocks;
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
                .comment("World disintegration band. When true (default), the world progressively breaks apart into",
                        "void past a carriage count, reaches a fully-void core where only the floating track remains,",
                        "then reassembles into normal terrain — one dramatic gap crossed once per run. The train rides",
                        "level the whole way (its bed + rails are preserved); the surrounding world and the track's",
                        "support pillars erode away. Set false to disable entirely.")
                .define("disintegrationEnabled", DEFAULT_DISINTEGRATION_ENABLED);
        ModConfigSpec.IntValue disintegrationStartCarriages = b
                .comment("Carriages of forward travel (from the X=0 spawn line) before the world begins breaking apart.",
                        "Maps to a world-X line at startCarriages × carriageLength. Lower this (e.g. 10) for testing.",
                        "Changing it only affects chunks generated afterwards — already-generated terrain keeps its state.")
                .defineInRange("disintegrationStartCarriages", DEFAULT_DISINTEGRATION_START_CARRIAGES,
                        MIN_DISINTEGRATION_START_CARRIAGES, MAX_DISINTEGRATION_START_CARRIAGES);
        ModConfigSpec.IntValue disintegrationFadeBlocks = b
                .comment("Blocks over which terrain fades from solid to fully void at each edge of the band (in and out).",
                        "Larger = a more gradual, cinematic break-apart. Default 100.")
                .defineInRange("disintegrationFadeBlocks", DEFAULT_DISINTEGRATION_FADE_BLOCKS,
                        MIN_DISINTEGRATION_FADE_BLOCKS, MAX_DISINTEGRATION_FADE_BLOCKS);
        ModConfigSpec.IntValue disintegrationCoreBlocks = b
                .comment("Blocks of fully-void core (only the floating track survives) between the fade-in and fade-out.",
                        "Total band width on X = 2 × fade + core. Default 60.")
                .defineInRange("disintegrationCoreBlocks", DEFAULT_DISINTEGRATION_CORE_BLOCKS,
                        MIN_DISINTEGRATION_CORE_BLOCKS, MAX_DISINTEGRATION_CORE_BLOCKS);
        b.pop();

        return new Holder(defaultPlayerMobSpawnOneIn, defaultPlayerMobBehindSpawnPercent, compatibleTerrain,
                disintegrationEnabled, disintegrationStartCarriages, disintegrationFadeBlocks, disintegrationCoreBlocks);
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

    /** Carriages of forward travel before the band begins; falls back to the hardcoded default pre-load. */
    public static int getDisintegrationStartCarriages() {
        return isLoaded() ? DISINTEGRATION_START_CARRIAGES.get() : DEFAULT_DISINTEGRATION_START_CARRIAGES;
    }

    /** Fade-in/out span (blocks) at each band edge; falls back to the hardcoded default pre-load. */
    public static int getDisintegrationFadeBlocks() {
        return isLoaded() ? DISINTEGRATION_FADE_BLOCKS.get() : DEFAULT_DISINTEGRATION_FADE_BLOCKS;
    }

    /** Fully-void core span (blocks); falls back to the hardcoded default pre-load. */
    public static int getDisintegrationCoreBlocks() {
        return isLoaded() ? DISINTEGRATION_CORE_BLOCKS.get() : DEFAULT_DISINTEGRATION_CORE_BLOCKS;
    }

    private record Holder(ModConfigSpec.IntValue defaultPlayerMobSpawnOneIn,
                          ModConfigSpec.IntValue defaultPlayerMobBehindSpawnPercent,
                          ModConfigSpec.BooleanValue compatibleTerrain,
                          ModConfigSpec.BooleanValue disintegrationEnabled,
                          ModConfigSpec.IntValue disintegrationStartCarriages,
                          ModConfigSpec.IntValue disintegrationFadeBlocks,
                          ModConfigSpec.IntValue disintegrationCoreBlocks) {}
}
