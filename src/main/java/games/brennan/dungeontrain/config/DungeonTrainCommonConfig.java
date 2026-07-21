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
    /**
     * Blocks the End sky/fog fade lags the terrain on each edge (sky-only; pushed toward
     * the void core). Delays the fade-in on entry and advances the fade-out on exit, so the
     * sky stays overworld while the ground first crumbles and returns to overworld before
     * the terrain has fully reformed. 0 = synced with terrain erosion.
     */
    public static final int MIN_DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS = 0;
    public static final int MAX_DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS = 10_000;
    public static final int DEFAULT_DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS = 120;

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
    /** Leading sand-beach stage length (blocks) — only rendered (as sand) when the band entrance is over ocean. */
    public static final int MIN_NETHER_BEACH_BLOCKS = 0;
    public static final int MAX_NETHER_BEACH_BLOCKS = 100_000_000;
    public static final int DEFAULT_NETHER_BEACH_BLOCKS = 64;
    /** Blocks of full-height mega-mountain plateau on each side of the nether core (the tunnel zone). */
    public static final int MIN_NETHER_MOUNTAIN_HOLD_BLOCKS = 0;
    public static final int MAX_NETHER_MOUNTAIN_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_NETHER_MOUNTAIN_HOLD_BLOCKS = 0;
    /** Blocks over which the mountain rock crossfades to netherrack on each side of the core. */
    public static final int MIN_NETHER_CORE_FADE_BLOCKS = 0;
    public static final int MAX_NETHER_CORE_FADE_BLOCKS = 10_000;
    public static final int DEFAULT_NETHER_CORE_FADE_BLOCKS = 600;
    /** Blocks of real Nether world-gen at the centre of the band. */
    public static final int MIN_NETHER_CORE_HOLD_BLOCKS = 0;
    public static final int MAX_NETHER_CORE_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_NETHER_CORE_HOLD_BLOCKS = 5000;

    /**
     * Upside-down band — a third looping phase (parallel to the disintegration/End and nether bands),
     * appended after the End phase in the cycle. The overworld terrain is mirrored vertically around
     * the carriage height, so the ground becomes a ceiling overhead and the train rides through open
     * air below it; the sun/sky/fog rotate horizontally around the vertical axis (light from the side).
     * The three special bands occupy disjoint cycle sub-ranges, so they never overlap.
     */
    public static final boolean DEFAULT_UPSIDE_DOWN_ENABLED = true;
    /** Blocks over which the upside-down atmosphere (sky/light) fades in/out at each band edge. */
    public static final int MIN_UPSIDE_DOWN_FADE_BLOCKS = 0;
    public static final int MAX_UPSIDE_DOWN_FADE_BLOCKS = 10_000;
    public static final int DEFAULT_UPSIDE_DOWN_FADE_BLOCKS = 600;
    /** Blocks of mirrored upside-down world-gen at the centre of the band. */
    public static final int MIN_UPSIDE_DOWN_HOLD_BLOCKS = 0;
    public static final int MAX_UPSIDE_DOWN_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_UPSIDE_DOWN_HOLD_BLOCKS = 5000;
    /**
     * Blocks of plain overworld inserted after the upside-down band, before the cycle's leading
     * {@code owGap} (to the next Nether band) resumes. The band no longer has a leading gap of its
     * own — it flows directly out of the End band — so this is the only breathing room between the
     * mirrored world and the next cycle.
     */
    public static final int MIN_UPSIDE_DOWN_EXIT_GAP_BLOCKS = 0;
    public static final int MAX_UPSIDE_DOWN_EXIT_GAP_BLOCKS = 10_000;
    public static final int DEFAULT_UPSIDE_DOWN_EXIT_GAP_BLOCKS = 600;
    /**
     * Blocks over which the upside-down band's EXIT crossfades back to overworld: the mirrored world
     * disperses into shrinking, spreading floating islands while the normal overworld fades in as
     * islands over the void, until solid overworld resumes. Inserted between the band's trailing
     * atmosphere fade and the plain-overworld exit gap. 0 disables the crossfade (hard edge, the
     * pre-existing behaviour), keeping the cycle period byte-identical.
     */
    public static final int MIN_UPSIDE_DOWN_EXIT_FADE_BLOCKS = 0;
    public static final int MAX_UPSIDE_DOWN_EXIT_FADE_BLOCKS = 40_000;
    public static final int DEFAULT_UPSIDE_DOWN_EXIT_FADE_BLOCKS = 10_000;
    /** OW-reveal fraction at/above which the solid minY floor returns across the exit crossfade. */
    public static final double UPSIDE_DOWN_EXIT_FLOOR_RETURN = 0.9;
    /** Mirror-disperse fraction at/above which the inverted bedrock roof is still stamped across the exit. */
    public static final double UPSIDE_DOWN_EXIT_ROOF_RECEDE = 0.5;
    /**
     * Fidelity/perf tradeoff for the exit crossfade's per-column noise skip. Near the saturated ends of
     * the disperse/reveal ramps the {@link games.brennan.dungeontrain.worldgen.Disintegration#coherentNoise}
     * gate outcome is effectively constant, so the sample can be skipped. {@code 0.0} skips only where the
     * outcome is <em>provably</em> constant (output-identical); a small epsilon (e.g. {@code 0.05}) also
     * skips columns within epsilon of saturation — imperceptible near the fade ends but cutting more noise.
     * Capped below 0.5 so the keep/drop skip bands cannot overlap. See
     * {@link games.brennan.dungeontrain.worldgen.UpsideDownBand#exitMirrorKeepsAll}.
     */
    public static final double MIN_UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON = 0.0;
    public static final double MAX_UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON = 0.49;
    public static final double DEFAULT_UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON = 0.1;
    /** Mirror plane offset (blocks) from the train Y: reflection plane M = trainY + offset. Signed. */
    public static final int MIN_UPSIDE_DOWN_MIRROR_PLANE_OFFSET = -256;
    public static final int MAX_UPSIDE_DOWN_MIRROR_PLANE_OFFSET = 256;
    public static final int DEFAULT_UPSIDE_DOWN_MIRROR_PLANE_OFFSET = 0;
    /** Clearance (blocks) inserted above the mirror plane before the reflected ceiling begins. */
    public static final int MIN_UPSIDE_DOWN_CEILING_GAP = 0;
    public static final int MAX_UPSIDE_DOWN_CEILING_GAP = 256;
    public static final int DEFAULT_UPSIDE_DOWN_CEILING_GAP = 0;
    /**
     * Caps the reflected ceiling to at most this many blocks above the ceiling start
     * ({@code mirror + ceilingGap}); the inverted bedrock lid drops to sit flush on the cap. {@code 0} =
     * no cap (full mirror — the pre-existing behaviour, byte-identical). A finite cap turns tall terrain's
     * near-full-height ceiling into a fixed-thickness slab, cutting the dominant per-chunk block-write
     * cost — but it flattens the flipped world's ceiling, so it changes the band's look. Applies to the
     * mirror ceiling only (the exit crossfade's returning overworld is never capped).
     */
    public static final int MIN_UPSIDE_DOWN_MAX_CEILING_HEIGHT = 0;
    public static final int MAX_UPSIDE_DOWN_MAX_CEILING_HEIGHT = 384;
    public static final int DEFAULT_UPSIDE_DOWN_MAX_CEILING_HEIGHT = 32;
    /** Clearance (blocks) inserted below the mirror plane before the reflected hanging terrain begins. */
    public static final int MIN_UPSIDE_DOWN_FLOOR_GAP = 0;
    public static final int MAX_UPSIDE_DOWN_FLOOR_GAP = 256;
    public static final int DEFAULT_UPSIDE_DOWN_FLOOR_GAP = 0;
    /**
     * When true the upside-down band inverts the world's bedrock caps: the normal bedrock floor at
     * {@code minY} is removed in-band (the underside opens to the void) and a bedrock lid is stamped
     * directly above the reflected terrain ceiling — the old floor, now the highest layer. When false
     * the band keeps the ordinary bedrock floor and no roof.
     */
    public static final boolean DEFAULT_UPSIDE_DOWN_BEDROCK_ROOF = true;
    /**
     * In-band cloud plane world-Y — clouds render at this height (below the train, in the open void)
     * instead of the vanilla 192, so the flipped world's sky sits beneath you. Default 0 (just under
     * the base world floor at {@code minY=32}).
     */
    public static final int MIN_UPSIDE_DOWN_CLOUD_Y = -256;
    public static final int MAX_UPSIDE_DOWN_CLOUD_Y = 256;
    public static final int DEFAULT_UPSIDE_DOWN_CLOUD_Y = 0;
    /**
     * Precompute the in-band vertical mirror on the worldgen worker thread at the SPAWN generation
     * step, then only apply the writes at chunk load — instead of doing the whole snapshot + reflection
     * + writes on the main thread at load. Moves the expensive snapshot/compute half off the main
     * thread (halving the mirror's per-chunk main-thread cost) with byte-identical terrain. false =
     * compute and apply everything at load (the original behaviour). Default true.
     */
    public static final boolean DEFAULT_UPSIDE_DOWN_MIRROR_PRECOMPUTE = true;

    /**
     * Chuncks band — a fourth looping phase, appended after the upside-down band's trailing overworld
     * gap. Along +X it is mostly void, sprinkled with occasional real overworld chunks: some vertically
     * complete, some a top-down slice (natural surface kept, flat cut-off bottom — a floating island of
     * the chunk's upper terrain). The train crosses it on the floating track bed. The four special bands
     * occupy disjoint cycle sub-ranges, so they never overlap.
     */
    public static final boolean DEFAULT_CHUNCKS_ENABLED = true;
    /** Blocks of chuncks-band world-gen (the whole mostly-void stretch). 0 drops the band from the cycle. */
    public static final int MIN_CHUNCKS_HOLD_BLOCKS = 0;
    public static final int MAX_CHUNCKS_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_CHUNCKS_HOLD_BLOCKS = 5000;
    /** Entry fade before the band: void chunks ramp in (keep-density 1 → keepDensity) across this span. */
    public static final int MIN_CHUNCKS_FADE_BLOCKS = 0;
    public static final int MAX_CHUNCKS_FADE_BLOCKS = 100_000_000;
    public static final int DEFAULT_CHUNCKS_FADE_BLOCKS = 1500;
    /** Plain-overworld gap before the chuncks band (after the upside-down exit gap), before the entry fade. */
    public static final int MIN_CHUNCKS_LEAD_GAP_BLOCKS = 0;
    public static final int MAX_CHUNCKS_LEAD_GAP_BLOCKS = 100_000_000;
    public static final int DEFAULT_CHUNCKS_LEAD_GAP_BLOCKS = 5000;
    /** Fraction 0..1 of chunks in the band that keep real terrain (the rest are void). */
    public static final double MIN_CHUNCKS_KEEP_DENSITY = 0.0;
    public static final double MAX_CHUNCKS_KEEP_DENSITY = 1.0;
    public static final double DEFAULT_CHUNCKS_KEEP_DENSITY = 0.12;
    /** Fraction 0..1 of the KEPT chunks that are a top-down slice rather than vertically complete. */
    public static final double MIN_CHUNCKS_SLICE_RATIO = 0.0;
    public static final double MAX_CHUNCKS_SLICE_RATIO = 1.0;
    public static final double DEFAULT_CHUNCKS_SLICE_RATIO = 0.5;

    /**
     * Ocean band — a looping phase of ONLY ocean + island biomes, inserted in the overworld stretch
     * AFTER the upside-down band's trailing gap and BEFORE the chuncks band. Its water surface is raised
     * to the train track bed height ({@code TrackGeometry.bedY()}, not vanilla sea level 63), so the train
     * skims an open sea; the carved corridor stays dry. Islands are sparse. The special bands occupy
     * disjoint cycle sub-ranges, so they never overlap.
     */
    public static final boolean DEFAULT_OCEAN_ENABLED = true;
    /** Blocks of ocean-band world-gen (the whole raised-sea stretch). 0 drops the band from the cycle. */
    public static final int MIN_OCEAN_HOLD_BLOCKS = 0;
    public static final int MAX_OCEAN_HOLD_BLOCKS = 100_000_000;
    public static final int DEFAULT_OCEAN_HOLD_BLOCKS = 2000;
    /** Plain-overworld gap before the ocean band (after the upside-down exit gap), before the band core. */
    public static final int MIN_OCEAN_LEAD_GAP_BLOCKS = 0;
    public static final int MAX_OCEAN_LEAD_GAP_BLOCKS = 100_000_000;
    public static final int DEFAULT_OCEAN_LEAD_GAP_BLOCKS = 5000;
    /** Fraction 0..1 of in-band chunks that carry a small island (the rest are open water). */
    public static final double MIN_OCEAN_ISLAND_DENSITY = 0.0;
    public static final double MAX_OCEAN_ISLAND_DENSITY = 1.0;
    public static final double DEFAULT_OCEAN_ISLAND_DENSITY = 0.08;

    /**
     * Whether a moving carriage breaks the world blocks its footprint passes through (drops included).
     * The corridor is normally pre-cleared at worldgen and re-swept at chunk load, so this only bites on
     * blocks that appear afterwards — player-placed walls, grown trees, structures outside the swept Z
     * corridor. false = the train phases through them silently, as it did before. Default true.
     */
    public static final boolean DEFAULT_BREAK_BLOCKS_ON_CONTACT = true;

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
    public static final ModConfigSpec.IntValue NETHER_BEACH_BLOCKS;
    public static final ModConfigSpec.IntValue NETHER_MOUNTAIN_HOLD_BLOCKS;
    public static final ModConfigSpec.IntValue NETHER_CORE_FADE_BLOCKS;
    public static final ModConfigSpec.IntValue NETHER_CORE_HOLD_BLOCKS;
    public static final ModConfigSpec.IntValue DISINTEGRATION_FIRST_OVERWORLD_BLOCKS;
    public static final ModConfigSpec.IntValue DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS;
    public static final ModConfigSpec.BooleanValue UPSIDE_DOWN_ENABLED;
    public static final ModConfigSpec.IntValue UPSIDE_DOWN_FADE_BLOCKS;
    public static final ModConfigSpec.IntValue UPSIDE_DOWN_HOLD_BLOCKS;
    public static final ModConfigSpec.IntValue UPSIDE_DOWN_EXIT_GAP_BLOCKS;
    public static final ModConfigSpec.IntValue UPSIDE_DOWN_EXIT_FADE_BLOCKS;
    public static final ModConfigSpec.IntValue UPSIDE_DOWN_MIRROR_PLANE_OFFSET;
    public static final ModConfigSpec.IntValue UPSIDE_DOWN_CEILING_GAP;
    public static final ModConfigSpec.IntValue UPSIDE_DOWN_FLOOR_GAP;
    public static final ModConfigSpec.BooleanValue UPSIDE_DOWN_BEDROCK_ROOF;
    public static final ModConfigSpec.IntValue UPSIDE_DOWN_CLOUD_Y;
    public static final ModConfigSpec.DoubleValue UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON;
    public static final ModConfigSpec.IntValue UPSIDE_DOWN_MAX_CEILING_HEIGHT;
    public static final ModConfigSpec.BooleanValue UPSIDE_DOWN_MIRROR_PRECOMPUTE;
    public static final ModConfigSpec.BooleanValue CHUNCKS_ENABLED;
    public static final ModConfigSpec.IntValue CHUNCKS_HOLD_BLOCKS;
    public static final ModConfigSpec.IntValue CHUNCKS_FADE_BLOCKS;
    public static final ModConfigSpec.IntValue CHUNCKS_LEAD_GAP_BLOCKS;
    public static final ModConfigSpec.DoubleValue CHUNCKS_KEEP_DENSITY;
    public static final ModConfigSpec.DoubleValue CHUNCKS_SLICE_RATIO;
    public static final ModConfigSpec.BooleanValue OCEAN_ENABLED;
    public static final ModConfigSpec.IntValue OCEAN_HOLD_BLOCKS;
    public static final ModConfigSpec.IntValue OCEAN_LEAD_GAP_BLOCKS;
    public static final ModConfigSpec.DoubleValue OCEAN_ISLAND_DENSITY;
    public static final ModConfigSpec.BooleanValue BREAK_BLOCKS_ON_CONTACT;

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
        NETHER_BEACH_BLOCKS = pair.getLeft().netherBeachBlocks;
        NETHER_MOUNTAIN_HOLD_BLOCKS = pair.getLeft().netherMountainHoldBlocks;
        NETHER_CORE_FADE_BLOCKS = pair.getLeft().netherCoreFadeBlocks;
        NETHER_CORE_HOLD_BLOCKS = pair.getLeft().netherCoreHoldBlocks;
        DISINTEGRATION_FIRST_OVERWORLD_BLOCKS = pair.getLeft().disintegrationFirstOverworldBlocks;
        DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS = pair.getLeft().disintegrationSkyFadeOffsetBlocks;
        UPSIDE_DOWN_ENABLED = pair.getLeft().upsideDownEnabled;
        UPSIDE_DOWN_FADE_BLOCKS = pair.getLeft().upsideDownFadeBlocks;
        UPSIDE_DOWN_HOLD_BLOCKS = pair.getLeft().upsideDownHoldBlocks;
        UPSIDE_DOWN_EXIT_GAP_BLOCKS = pair.getLeft().upsideDownExitGapBlocks;
        UPSIDE_DOWN_EXIT_FADE_BLOCKS = pair.getLeft().upsideDownExitFadeBlocks;
        UPSIDE_DOWN_MIRROR_PLANE_OFFSET = pair.getLeft().upsideDownMirrorPlaneOffset;
        UPSIDE_DOWN_CEILING_GAP = pair.getLeft().upsideDownCeilingGap;
        UPSIDE_DOWN_FLOOR_GAP = pair.getLeft().upsideDownFloorGap;
        UPSIDE_DOWN_BEDROCK_ROOF = pair.getLeft().upsideDownBedrockRoof;
        UPSIDE_DOWN_CLOUD_Y = pair.getLeft().upsideDownCloudY;
        UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON = pair.getLeft().upsideDownExitNoiseSkipEpsilon;
        UPSIDE_DOWN_MAX_CEILING_HEIGHT = pair.getLeft().upsideDownMaxCeilingHeight;
        UPSIDE_DOWN_MIRROR_PRECOMPUTE = pair.getLeft().upsideDownMirrorPrecompute;
        CHUNCKS_ENABLED = pair.getLeft().chuncksEnabled;
        CHUNCKS_HOLD_BLOCKS = pair.getLeft().chuncksHoldBlocks;
        CHUNCKS_FADE_BLOCKS = pair.getLeft().chuncksFadeBlocks;
        CHUNCKS_LEAD_GAP_BLOCKS = pair.getLeft().chuncksLeadGapBlocks;
        CHUNCKS_KEEP_DENSITY = pair.getLeft().chuncksKeepDensity;
        CHUNCKS_SLICE_RATIO = pair.getLeft().chuncksSliceRatio;
        OCEAN_ENABLED = pair.getLeft().oceanEnabled;
        OCEAN_HOLD_BLOCKS = pair.getLeft().oceanHoldBlocks;
        OCEAN_LEAD_GAP_BLOCKS = pair.getLeft().oceanLeadGapBlocks;
        OCEAN_ISLAND_DENSITY = pair.getLeft().oceanIslandDensity;
        BREAK_BLOCKS_ON_CONTACT = pair.getLeft().breakBlocksOnContact;
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

        b.push("train");
        ModConfigSpec.BooleanValue breakBlocksOnContact = b
                .comment("Global DEFAULT for whether a moving carriage BREAKS the world blocks its footprint passes",
                        "through, dropping their items (containers spill their contents). The track corridor is already",
                        "cleared at worldgen and re-swept at chunk load, so this only applies to blocks that appear",
                        "afterwards — a player-built wall across the tracks, a grown tree, a structure outside the swept",
                        "corridor. false = the train phases through them silently. Used by any world that has not set a",
                        "per-world override in-game (Mods -> Dungeon Train -> Config while in a world). Default true.")
                .define("defaultBreakBlocksOnContact", DEFAULT_BREAK_BLOCKS_ON_CONTACT);
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
                .comment("Blocks of normal overworld BEFORE each special phase (used twice per cycle: before the",
                        "Nether phase and before the End phase). The single cycle tiles forever along +X from",
                        "startBlocks: OW → Nether transition → Nether → Nether transition → OW → Void → End islands",
                        "→ Void → (repeat). Default 10000.")
                .defineInRange("disintegrationOverworldHoldBlocks", DEFAULT_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS,
                        MIN_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS, MAX_DISINTEGRATION_OVERWORLD_HOLD_BLOCKS);
        ModConfigSpec.IntValue disintegrationFirstOverworldBlocks = b
                .comment("Blocks of overworld before the very FIRST void band, measured from startBlocks. Lets the player",
                        "spawn partway through an overworld stretch so the first leg to the void is shorter than the",
                        "repeating overworldHold (every later overworld stretch is the full overworldHold). When this is",
                        "≥ overworldHold there is no early shift and the classic behaviour applies. Default 5000.")
                .defineInRange("disintegrationFirstOverworldBlocks", DEFAULT_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS,
                        MIN_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS, MAX_DISINTEGRATION_FIRST_OVERWORLD_BLOCKS);
        ModConfigSpec.IntValue disintegrationSkyFadeOffsetBlocks = b
                .comment("Blocks the End sky/fog fade lags the terrain on each edge (sky-only; toward the void core).",
                        "Delays the sky fade-in on entry and advances the fade-out on exit, so the sky stays overworld",
                        "while the ground first crumbles and returns to overworld before the terrain has fully reformed.",
                        "Does not affect terrain erosion. 0 = synced with terrain. Default 120.")
                .defineInRange("disintegrationSkyFadeOffsetBlocks", DEFAULT_DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS,
                        MIN_DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS, MAX_DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS);

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
        ModConfigSpec.IntValue netherBeachBlocks = b
                .comment("Length (blocks) of a leading sand-beach stage rendered ONLY when the band entrance is over",
                        "ocean — natural-height sand (NOT amplified) before the mountains start rising, so the coast",
                        "reads as a beach rather than a stone shelf. Over non-ocean entrances this span is just a",
                        "gentle natural lead-in. Default 64.")
                .defineInRange("netherBeachBlocks", DEFAULT_NETHER_BEACH_BLOCKS,
                        MIN_NETHER_BEACH_BLOCKS, MAX_NETHER_BEACH_BLOCKS);
        ModConfigSpec.IntValue netherMountainHoldBlocks = b
                .comment("Blocks of full-height mega-mountain plateau held on each side of the core before the",
                        "netherrack crossfade. Default 0 — the rise goes straight from the tallest stage into the",
                        "crossfade with no plateau.")
                .defineInRange("netherMountainHoldBlocks", DEFAULT_NETHER_MOUNTAIN_HOLD_BLOCKS,
                        MIN_NETHER_MOUNTAIN_HOLD_BLOCKS, MAX_NETHER_MOUNTAIN_HOLD_BLOCKS);
        ModConfigSpec.IntValue netherCoreFadeBlocks = b
                .comment("Blocks over which the mountain rock crossfades to netherrack on each side of the core (the",
                        "'netherrack' transition stage). Also the span the tunnel/track block-level fade dithers",
                        "across. Default 600.")
                .defineInRange("netherCoreFadeBlocks", DEFAULT_NETHER_CORE_FADE_BLOCKS,
                        MIN_NETHER_CORE_FADE_BLOCKS, MAX_NETHER_CORE_FADE_BLOCKS);
        ModConfigSpec.IntValue netherCoreHoldBlocks = b
                .comment("Blocks of real Nether world-gen (sampled from the Nether dimension) at the centre of the",
                        "band. Default 5000.")
                .defineInRange("netherCoreHoldBlocks", DEFAULT_NETHER_CORE_HOLD_BLOCKS,
                        MIN_NETHER_CORE_HOLD_BLOCKS, MAX_NETHER_CORE_HOLD_BLOCKS);

        ModConfigSpec.BooleanValue upsideDownEnabled = b
                .comment("Upside-down phase — part of the single repeating world-gen cycle, appended after the End",
                        "phase. Along +X the overworld terrain is mirrored vertically around the carriage height: the",
                        "ground becomes a ceiling overhead and the train rides through open air below it. The sun, sky",
                        "and fog rotate horizontally around the vertical axis, so the light source is always at the side.",
                        "The full cycle is: OW → Nether → OW → Void → End → Void → Upside-down → Void → OW → (repeat).",
                        "Set false to drop the upside-down phase from the cycle.")
                .define("upsideDownEnabled", DEFAULT_UPSIDE_DOWN_ENABLED);
        ModConfigSpec.IntValue upsideDownFadeBlocks = b
                .comment("Blocks over which the upside-down sky/light atmosphere fades in/out at each band edge",
                        "(the terrain flip itself is per-column and abrupt; the train tunnels through). Default 600.")
                .defineInRange("upsideDownFadeBlocks", DEFAULT_UPSIDE_DOWN_FADE_BLOCKS,
                        MIN_UPSIDE_DOWN_FADE_BLOCKS, MAX_UPSIDE_DOWN_FADE_BLOCKS);
        ModConfigSpec.IntValue upsideDownHoldBlocks = b
                .comment("Blocks of mirrored upside-down world-gen at the centre of the band. Default 5000.")
                .defineInRange("upsideDownHoldBlocks", DEFAULT_UPSIDE_DOWN_HOLD_BLOCKS,
                        MIN_UPSIDE_DOWN_HOLD_BLOCKS, MAX_UPSIDE_DOWN_HOLD_BLOCKS);
        ModConfigSpec.IntValue upsideDownExitGapBlocks = b
                .comment("Blocks of plain overworld inserted AFTER the upside-down band, before the cycle's leading",
                        "owGap (to the next Nether band) resumes. The band flows directly out of the End band with no",
                        "leading gap of its own, so this is the only breathing room before the cycle repeats. Default 600.")
                .defineInRange("upsideDownExitGapBlocks", DEFAULT_UPSIDE_DOWN_EXIT_GAP_BLOCKS,
                        MIN_UPSIDE_DOWN_EXIT_GAP_BLOCKS, MAX_UPSIDE_DOWN_EXIT_GAP_BLOCKS);
        ModConfigSpec.IntValue upsideDownExitFadeBlocks = b
                .comment("Blocks over which the upside-down band's EXIT crossfades back to overworld: the mirrored",
                        "world disperses into shrinking, spreading floating islands while the normal overworld fades",
                        "in as islands over the void, until solid overworld resumes. Sits between the band's trailing",
                        "atmosphere fade and the exitGap. 0 disables the crossfade (hard edge). Default 10000.")
                .defineInRange("upsideDownExitFadeBlocks", DEFAULT_UPSIDE_DOWN_EXIT_FADE_BLOCKS,
                        MIN_UPSIDE_DOWN_EXIT_FADE_BLOCKS, MAX_UPSIDE_DOWN_EXIT_FADE_BLOCKS);
        ModConfigSpec.IntValue upsideDownMirrorPlaneOffset = b
                .comment("Mirror plane offset (blocks) from the train Y: the world is reflected around",
                        "M = trainY + this. Positive raises the flip plane above the train, negative lowers it.",
                        "Default 0 (mirror exactly at the carriage height).")
                .defineInRange("upsideDownMirrorPlaneOffset", DEFAULT_UPSIDE_DOWN_MIRROR_PLANE_OFFSET,
                        MIN_UPSIDE_DOWN_MIRROR_PLANE_OFFSET, MAX_UPSIDE_DOWN_MIRROR_PLANE_OFFSET);
        ModConfigSpec.IntValue upsideDownCeilingGap = b
                .comment("Clearance (blocks) inserted ABOVE the mirror plane before the reflected ceiling begins —",
                        "raises the ceiling away from the train. Default 0.")
                .defineInRange("upsideDownCeilingGap", DEFAULT_UPSIDE_DOWN_CEILING_GAP,
                        MIN_UPSIDE_DOWN_CEILING_GAP, MAX_UPSIDE_DOWN_CEILING_GAP);
        ModConfigSpec.IntValue upsideDownFloorGap = b
                .comment("Clearance (blocks) inserted BELOW the mirror plane before the reflected hanging terrain",
                        "begins — drops the hanging terrain away from the train. Default 0.")
                .defineInRange("upsideDownFloorGap", DEFAULT_UPSIDE_DOWN_FLOOR_GAP,
                        MIN_UPSIDE_DOWN_FLOOR_GAP, MAX_UPSIDE_DOWN_FLOOR_GAP);
        ModConfigSpec.BooleanValue upsideDownBedrockRoof = b
                .comment("Invert the world's bedrock caps inside the upside-down band: remove the bedrock floor at",
                        "minY (the underside opens to the void) and stamp a bedrock lid directly above the reflected",
                        "terrain ceiling — the old floor, now the highest layer. Set false to keep the ordinary",
                        "bedrock floor and no roof. Default true.")
                .define("upsideDownBedrockRoof", DEFAULT_UPSIDE_DOWN_BEDROCK_ROOF);
        ModConfigSpec.IntValue upsideDownCloudY = b
                .comment("In-band cloud plane world-Y — clouds render here (below the train, in the open void) instead",
                        "of the vanilla 192, so the flipped world's sky sits beneath you. Default 0 (just under the",
                        "base world floor at minY=32).")
                .defineInRange("upsideDownCloudY", DEFAULT_UPSIDE_DOWN_CLOUD_Y,
                        MIN_UPSIDE_DOWN_CLOUD_Y, MAX_UPSIDE_DOWN_CLOUD_Y);
        ModConfigSpec.DoubleValue upsideDownExitNoiseSkipEpsilon = b
                .comment("Fidelity/perf tradeoff for the exit-crossfade per-column noise skip. Near the saturated ends",
                        "of the disperse/reveal ramps the coherentNoise gate outcome is effectively constant, so the",
                        "sample is skipped. 0.0 = skip only where the outcome is provably constant (output-identical);",
                        "a small value (e.g. 0.05) also skips columns within epsilon of saturation — imperceptible near",
                        "the fade ends but cutting more noise. Default 0.0.")
                .defineInRange("upsideDownExitNoiseSkipEpsilon", DEFAULT_UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON,
                        MIN_UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON, MAX_UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON);
        ModConfigSpec.IntValue upsideDownMaxCeilingHeight = b
                .comment("Caps the reflected ceiling to at most this many blocks above the ceiling start",
                        "(mirror + ceilingGap); the inverted bedrock lid drops to sit flush on the cap. 0 = no cap",
                        "(full mirror, byte-identical to before). A finite cap turns tall terrain's near-full-height",
                        "ceiling into a fixed-thickness slab, cutting the dominant per-chunk block-write cost, but it",
                        "flattens the flipped world's ceiling (changes the band's look). Default 0.")
                .defineInRange("upsideDownMaxCeilingHeight", DEFAULT_UPSIDE_DOWN_MAX_CEILING_HEIGHT,
                        MIN_UPSIDE_DOWN_MAX_CEILING_HEIGHT, MAX_UPSIDE_DOWN_MAX_CEILING_HEIGHT);
        ModConfigSpec.BooleanValue upsideDownMirrorPrecompute = b
                .comment("Precompute the in-band vertical mirror on the worldgen worker thread (SPAWN step) and only",
                        "apply the block writes at chunk load, instead of doing the whole snapshot + reflection + writes",
                        "on the main thread at load. Moves the expensive snapshot/compute half off the main thread with",
                        "byte-identical terrain. Set false to compute and apply everything at load (original behaviour).",
                        "Default true.")
                .define("upsideDownMirrorPrecompute", DEFAULT_UPSIDE_DOWN_MIRROR_PRECOMPUTE);

        ModConfigSpec.BooleanValue chuncksEnabled = b
                .comment("Chuncks phase — part of the single repeating world-gen cycle, appended after the upside-down",
                        "band's trailing overworld gap. Along +X it is mostly void, sprinkled with occasional real",
                        "overworld chunks: some vertically complete, some a top-down slice (natural surface kept, flat",
                        "cut-off bottom). The train crosses on the floating track bed. The full cycle is:",
                        "OW → Nether → OW → Void → End → Void → Upside-down → OW → Chuncks → (repeat).",
                        "Set false to drop the chuncks phase from the cycle.")
                .define("chuncksEnabled", DEFAULT_CHUNCKS_ENABLED);
        ModConfigSpec.IntValue chuncksHoldBlocks = b
                .comment("Blocks of chuncks-band world-gen (the whole mostly-void stretch). Default 5000.")
                .defineInRange("chuncksHoldBlocks", DEFAULT_CHUNCKS_HOLD_BLOCKS,
                        MIN_CHUNCKS_HOLD_BLOCKS, MAX_CHUNCKS_HOLD_BLOCKS);
        ModConfigSpec.IntValue chuncksFadeBlocks = b
                .comment("Entry fade before the chuncks band: void chunks ramp in across this span (the keep-density",
                        "eases from 1 = all real terrain down to chuncksKeepDensity at the band edge), so the void",
                        "arrives gradually instead of at a hard wall. 0 = hard edge. Default 1500.")
                .defineInRange("chuncksFadeBlocks", DEFAULT_CHUNCKS_FADE_BLOCKS,
                        MIN_CHUNCKS_FADE_BLOCKS, MAX_CHUNCKS_FADE_BLOCKS);
        ModConfigSpec.IntValue chuncksLeadGapBlocks = b
                .comment("Plain-overworld gap inserted before the chuncks band — between the upside-down band's exit",
                        "(its exit fade + its own exit gap) and the chuncks entry fade. Breathing room so the two",
                        "special zones don't run together. Default 5000.")
                .defineInRange("chuncksLeadGapBlocks", DEFAULT_CHUNCKS_LEAD_GAP_BLOCKS,
                        MIN_CHUNCKS_LEAD_GAP_BLOCKS, MAX_CHUNCKS_LEAD_GAP_BLOCKS);
        ModConfigSpec.DoubleValue chuncksKeepDensity = b
                .comment("Fraction 0..1 of chunks in the band that keep real terrain (the rest are void). A per-chunk,",
                        "seed-stable noise gate. Default 0.12 (~12% of chunks are kept).")
                .defineInRange("chuncksKeepDensity", DEFAULT_CHUNCKS_KEEP_DENSITY,
                        MIN_CHUNCKS_KEEP_DENSITY, MAX_CHUNCKS_KEEP_DENSITY);
        ModConfigSpec.DoubleValue chuncksSliceRatio = b
                .comment("Fraction 0..1 of the KEPT chunks that are a top-down slice (natural surface kept, flat",
                        "cut-off bottom) rather than vertically complete. Default 0.5.")
                .defineInRange("chuncksSliceRatio", DEFAULT_CHUNCKS_SLICE_RATIO,
                        MIN_CHUNCKS_SLICE_RATIO, MAX_CHUNCKS_SLICE_RATIO);

        ModConfigSpec.BooleanValue oceanEnabled = b
                .comment("Ocean phase — part of the single repeating world-gen cycle, inserted in the overworld stretch",
                        "AFTER the upside-down band's trailing gap and BEFORE the chuncks band. Along +X it is an open",
                        "sea of ONLY ocean + island biomes whose water surface is raised to the train track bed height",
                        "(not vanilla sea level 63); the train skims the surface and the carved corridor stays dry. The",
                        "full cycle is: OW → Nether → OW → Void → End → Void → Upside-down → OW → Ocean → OW → Chuncks →",
                        "(repeat). Set false to drop the ocean phase from the cycle.")
                .define("oceanEnabled", DEFAULT_OCEAN_ENABLED);
        ModConfigSpec.IntValue oceanHoldBlocks = b
                .comment("Blocks of ocean-band world-gen (the whole raised-sea stretch). Default 2000.")
                .defineInRange("oceanHoldBlocks", DEFAULT_OCEAN_HOLD_BLOCKS,
                        MIN_OCEAN_HOLD_BLOCKS, MAX_OCEAN_HOLD_BLOCKS);
        ModConfigSpec.IntValue oceanLeadGapBlocks = b
                .comment("Plain-overworld gap inserted before the ocean band — between the upside-down band's exit",
                        "(its exit fade + its own exit gap) and the ocean band. Breathing room so the two special zones",
                        "don't run together. Default 5000.")
                .defineInRange("oceanLeadGapBlocks", DEFAULT_OCEAN_LEAD_GAP_BLOCKS,
                        MIN_OCEAN_LEAD_GAP_BLOCKS, MAX_OCEAN_LEAD_GAP_BLOCKS);
        ModConfigSpec.DoubleValue oceanIslandDensity = b
                .comment("Fraction 0..1 of in-band chunks that carry a small island (the rest are open water). A",
                        "per-chunk, seed-stable noise gate. Default 0.08 (~8% of chunks) — sparse, so the band reads",
                        "as predominantly open ocean.")
                .defineInRange("oceanIslandDensity", DEFAULT_OCEAN_ISLAND_DENSITY,
                        MIN_OCEAN_ISLAND_DENSITY, MAX_OCEAN_ISLAND_DENSITY);
        b.pop();

        return new Holder(defaultPlayerMobSpawnOneIn, defaultPlayerMobBehindSpawnPercent, compatibleTerrain,
                disintegrationEnabled, disintegrationStartBlocks, disintegrationFadeBlocks,
                disintegrationVoidHoldBlocks, disintegrationEndHoldBlocks, disintegrationOverworldHoldBlocks,
                netherTransitionEnabled, netherStageBlocks, netherStageMultipliers, netherBaseReliefBlocks,
                netherBeachBlocks, netherMountainHoldBlocks, netherCoreFadeBlocks, netherCoreHoldBlocks,
                disintegrationFirstOverworldBlocks, disintegrationSkyFadeOffsetBlocks,
                upsideDownEnabled, upsideDownFadeBlocks, upsideDownHoldBlocks, upsideDownExitGapBlocks,
                upsideDownExitFadeBlocks, upsideDownMirrorPlaneOffset, upsideDownCeilingGap, upsideDownFloorGap,
                upsideDownBedrockRoof, upsideDownCloudY, upsideDownExitNoiseSkipEpsilon,
                upsideDownMaxCeilingHeight, upsideDownMirrorPrecompute,
                chuncksEnabled, chuncksHoldBlocks, chuncksFadeBlocks, chuncksLeadGapBlocks,
                chuncksKeepDensity, chuncksSliceRatio,
                oceanEnabled, oceanHoldBlocks, oceanLeadGapBlocks, oceanIslandDensity,
                breakBlocksOnContact);
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

    /** Global default for train-on-contact block breaking; falls back to the hardcoded default pre-load. */
    public static boolean getDefaultBreakBlocksOnContact() {
        return isLoaded() ? BREAK_BLOCKS_ON_CONTACT.get() : DEFAULT_BREAK_BLOCKS_ON_CONTACT;
    }

    public static void setDefaultBreakBlocksOnContact(boolean value) {
        if (!isLoaded()) return;
        BREAK_BLOCKS_ON_CONTACT.set(value);
        BREAK_BLOCKS_ON_CONTACT.save();
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

    /** Leading sand-beach stage length (blocks); falls back to the hardcoded default pre-load. */
    public static int getNetherBeachBlocks() {
        return isLoaded() ? NETHER_BEACH_BLOCKS.get() : DEFAULT_NETHER_BEACH_BLOCKS;
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

    /** Sky/fog fade lag (blocks) behind the terrain on each band edge; falls back to the hardcoded default pre-load. */
    public static int getDisintegrationSkyFadeOffsetBlocks() {
        return isLoaded() ? DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS.get() : DEFAULT_DISINTEGRATION_SKY_FADE_OFFSET_BLOCKS;
    }

    /** Whether the upside-down band is active; falls back to the hardcoded default pre-load. */
    public static boolean isUpsideDownEnabled() {
        return isLoaded() ? UPSIDE_DOWN_ENABLED.get() : DEFAULT_UPSIDE_DOWN_ENABLED;
    }

    /** Fade-in/out span (blocks) of the upside-down atmosphere at each band edge; falls back pre-load. */
    public static int getUpsideDownFadeBlocks() {
        return isLoaded() ? UPSIDE_DOWN_FADE_BLOCKS.get() : DEFAULT_UPSIDE_DOWN_FADE_BLOCKS;
    }

    /** Mirrored upside-down core span (blocks); falls back to the hardcoded default pre-load. */
    public static int getUpsideDownHoldBlocks() {
        return isLoaded() ? UPSIDE_DOWN_HOLD_BLOCKS.get() : DEFAULT_UPSIDE_DOWN_HOLD_BLOCKS;
    }

    /** Trailing plain-overworld gap (blocks) after the band; falls back to the hardcoded default pre-load. */
    public static int getUpsideDownExitGapBlocks() {
        return isLoaded() ? UPSIDE_DOWN_EXIT_GAP_BLOCKS.get() : DEFAULT_UPSIDE_DOWN_EXIT_GAP_BLOCKS;
    }

    /** Exit crossfade span (blocks) where the mirror disperses and overworld fades in; falls back pre-load. */
    public static int getUpsideDownExitFadeBlocks() {
        return isLoaded() ? UPSIDE_DOWN_EXIT_FADE_BLOCKS.get() : DEFAULT_UPSIDE_DOWN_EXIT_FADE_BLOCKS;
    }

    /** Mirror plane offset (blocks) from the train Y (signed); falls back to the hardcoded default pre-load. */
    public static int getUpsideDownMirrorPlaneOffset() {
        return isLoaded() ? UPSIDE_DOWN_MIRROR_PLANE_OFFSET.get() : DEFAULT_UPSIDE_DOWN_MIRROR_PLANE_OFFSET;
    }

    /** Ceiling clearance (blocks) above the mirror plane; falls back to the hardcoded default pre-load. */
    public static int getUpsideDownCeilingGap() {
        return isLoaded() ? UPSIDE_DOWN_CEILING_GAP.get() : DEFAULT_UPSIDE_DOWN_CEILING_GAP;
    }

    /** Floor clearance (blocks) below the mirror plane; falls back to the hardcoded default pre-load. */
    public static int getUpsideDownFloorGap() {
        return isLoaded() ? UPSIDE_DOWN_FLOOR_GAP.get() : DEFAULT_UPSIDE_DOWN_FLOOR_GAP;
    }

    /** Whether the band removes the bedrock floor and caps the reflected ceiling with bedrock; falls back pre-load. */
    public static boolean isUpsideDownBedrockRoof() {
        return isLoaded() ? UPSIDE_DOWN_BEDROCK_ROOF.get() : DEFAULT_UPSIDE_DOWN_BEDROCK_ROOF;
    }

    /** In-band cloud plane world-Y (clouds render below the train); falls back to the hardcoded default pre-load. */
    public static int getUpsideDownCloudY() {
        return isLoaded() ? UPSIDE_DOWN_CLOUD_Y.get() : DEFAULT_UPSIDE_DOWN_CLOUD_Y;
    }

    /**
     * Epsilon for the exit-crossfade per-column noise skip (fidelity/perf tradeoff); falls back to the
     * hardcoded default pre-load. {@code 0.0} = output-identical (skip only provably-constant columns).
     */
    public static double getUpsideDownExitNoiseSkipEpsilon() {
        return isLoaded() ? UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON.get() : DEFAULT_UPSIDE_DOWN_EXIT_NOISE_SKIP_EPSILON;
    }

    /** Max reflected-ceiling height (blocks above mirror+ceilingGap); 0 = uncapped. Falls back pre-load. */
    public static int getUpsideDownMaxCeilingHeight() {
        return isLoaded() ? UPSIDE_DOWN_MAX_CEILING_HEIGHT.get() : DEFAULT_UPSIDE_DOWN_MAX_CEILING_HEIGHT;
    }

    /** Whether the in-band mirror is precomputed off-thread at SPAWN and applied at load; falls back pre-load. */
    public static boolean isUpsideDownMirrorPrecompute() {
        return isLoaded() ? UPSIDE_DOWN_MIRROR_PRECOMPUTE.get() : DEFAULT_UPSIDE_DOWN_MIRROR_PRECOMPUTE;
    }

    /** Whether the chuncks band is active; falls back to the hardcoded default pre-load. */
    public static boolean isChuncksEnabled() {
        return isLoaded() ? CHUNCKS_ENABLED.get() : DEFAULT_CHUNCKS_ENABLED;
    }

    /** Chuncks band span (blocks); falls back to the hardcoded default pre-load. */
    public static int getChuncksHoldBlocks() {
        return isLoaded() ? CHUNCKS_HOLD_BLOCKS.get() : DEFAULT_CHUNCKS_HOLD_BLOCKS;
    }

    /** Chuncks entry-fade span (blocks) where void ramps in; falls back to the hardcoded default pre-load. */
    public static int getChuncksFadeBlocks() {
        return isLoaded() ? CHUNCKS_FADE_BLOCKS.get() : DEFAULT_CHUNCKS_FADE_BLOCKS;
    }

    /** Overworld lead-in gap (blocks) before the chuncks band; falls back to the hardcoded default pre-load. */
    public static int getChuncksLeadGapBlocks() {
        return isLoaded() ? CHUNCKS_LEAD_GAP_BLOCKS.get() : DEFAULT_CHUNCKS_LEAD_GAP_BLOCKS;
    }

    /** Fraction 0..1 of chuncks-band chunks that keep real terrain; falls back to the hardcoded default pre-load. */
    public static double getChuncksKeepDensity() {
        return isLoaded() ? CHUNCKS_KEEP_DENSITY.get() : DEFAULT_CHUNCKS_KEEP_DENSITY;
    }

    /** Fraction 0..1 of kept chuncks that are a top-down slice; falls back to the hardcoded default pre-load. */
    public static double getChuncksSliceRatio() {
        return isLoaded() ? CHUNCKS_SLICE_RATIO.get() : DEFAULT_CHUNCKS_SLICE_RATIO;
    }

    /** Whether the ocean band is active; falls back to the hardcoded default pre-load. */
    public static boolean isOceanEnabled() {
        return isLoaded() ? OCEAN_ENABLED.get() : DEFAULT_OCEAN_ENABLED;
    }

    /** Ocean band span (blocks); falls back to the hardcoded default pre-load. */
    public static int getOceanHoldBlocks() {
        return isLoaded() ? OCEAN_HOLD_BLOCKS.get() : DEFAULT_OCEAN_HOLD_BLOCKS;
    }

    /** Overworld lead-in gap (blocks) before the ocean band; falls back to the hardcoded default pre-load. */
    public static int getOceanLeadGapBlocks() {
        return isLoaded() ? OCEAN_LEAD_GAP_BLOCKS.get() : DEFAULT_OCEAN_LEAD_GAP_BLOCKS;
    }

    /** Fraction 0..1 of ocean-band chunks that carry an island; falls back to the hardcoded default pre-load. */
    public static double getOceanIslandDensity() {
        return isLoaded() ? OCEAN_ISLAND_DENSITY.get() : DEFAULT_OCEAN_ISLAND_DENSITY;
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
                          ModConfigSpec.IntValue netherBeachBlocks,
                          ModConfigSpec.IntValue netherMountainHoldBlocks,
                          ModConfigSpec.IntValue netherCoreFadeBlocks,
                          ModConfigSpec.IntValue netherCoreHoldBlocks,
                          ModConfigSpec.IntValue disintegrationFirstOverworldBlocks,
                          ModConfigSpec.IntValue disintegrationSkyFadeOffsetBlocks,
                          ModConfigSpec.BooleanValue upsideDownEnabled,
                          ModConfigSpec.IntValue upsideDownFadeBlocks,
                          ModConfigSpec.IntValue upsideDownHoldBlocks,
                          ModConfigSpec.IntValue upsideDownExitGapBlocks,
                          ModConfigSpec.IntValue upsideDownExitFadeBlocks,
                          ModConfigSpec.IntValue upsideDownMirrorPlaneOffset,
                          ModConfigSpec.IntValue upsideDownCeilingGap,
                          ModConfigSpec.IntValue upsideDownFloorGap,
                          ModConfigSpec.BooleanValue upsideDownBedrockRoof,
                          ModConfigSpec.IntValue upsideDownCloudY,
                          ModConfigSpec.DoubleValue upsideDownExitNoiseSkipEpsilon,
                          ModConfigSpec.IntValue upsideDownMaxCeilingHeight,
                          ModConfigSpec.BooleanValue upsideDownMirrorPrecompute,
                          ModConfigSpec.BooleanValue chuncksEnabled,
                          ModConfigSpec.IntValue chuncksHoldBlocks,
                          ModConfigSpec.IntValue chuncksFadeBlocks,
                          ModConfigSpec.IntValue chuncksLeadGapBlocks,
                          ModConfigSpec.DoubleValue chuncksKeepDensity,
                          ModConfigSpec.DoubleValue chuncksSliceRatio,
                          ModConfigSpec.BooleanValue oceanEnabled,
                          ModConfigSpec.IntValue oceanHoldBlocks,
                          ModConfigSpec.IntValue oceanLeadGapBlocks,
                          ModConfigSpec.DoubleValue oceanIslandDensity,
                          ModConfigSpec.BooleanValue breakBlocksOnContact) {}
}
