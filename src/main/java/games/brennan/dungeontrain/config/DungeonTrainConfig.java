package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.train.CarriageGenerationConfig;
import games.brennan.dungeontrain.train.CarriageGenerationMode;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Server-scoped Forge config for Dungeon Train tunables.
 *
 * Stored per-world at {@code <save>/serverconfig/dungeontrain-server.toml}.
 * Registered from {@link games.brennan.dungeontrain.DungeonTrain} constructor.
 *
 * Exposes:
 *   - {@code numCarriages} — rolling-window size, [1, 50]
 *   - {@code speed} — train speed along +X in blocks/second, [0.0, 20.0]
 *   - {@code trainY} — world Y where new trains spawn, [-64, 320]
 *   - {@code generateTracks} — auto-place world-block tracks under the train
 *   - {@code generateTunnels} — auto-place stone-brick tunnels through thick rock
 *   - {@code generationMode} — how {@link games.brennan.dungeontrain.train.CarriagePlacer#typeForIndex}
 *       picks a carriage variant (RANDOM, RANDOM_GROUPED, LOOPING)
 *   - {@code groupSize} — non-flatbed run length for RANDOM_GROUPED, [1, 16]
 */
public final class DungeonTrainConfig {

    public static final int MIN_CARRIAGES = 0;
    public static final int MAX_CARRIAGES = 50;
    public static final int DEFAULT_CARRIAGES = 0;
    /** Seed value passed to TrainAssembler.spawnTrain when config = 0 (auto). The appender retargets immediately on the first tick. */
    public static final int DEFAULT_CARRIAGES_AUTO_SEED = 15;
    /** Lower bound applied to the auto-from-render-distance computation so very low rd doesn't produce a degenerate train. */
    public static final int MIN_CARRIAGES_AUTO_FLOOR = 5;

    public static final double MIN_SPEED = 0.0;
    public static final double MAX_SPEED = 20.0;
    public static final double DEFAULT_SPEED = 2.0;

    public static final int MIN_TRAIN_Y = -64;
    public static final int MAX_TRAIN_Y = 320;
    public static final int DEFAULT_TRAIN_Y = 78;

    public static final boolean DEFAULT_GENERATE_TRACKS = true;
    public static final boolean DEFAULT_GENERATE_TUNNELS = true;

    public static final CarriageGenerationMode DEFAULT_GENERATION_MODE = CarriageGenerationMode.RANDOM_GROUPED;
    public static final int DEFAULT_GROUP_SIZE = CarriageGenerationConfig.DEFAULT_GROUP_SIZE;

    public static final boolean DEFAULT_DIFFICULTY_ENABLED = true;
    public static final int MIN_CARRIAGES_PER_TIER = 1;
    public static final int MAX_CARRIAGES_PER_TIER = 1000;
    public static final int DEFAULT_CARRIAGES_PER_TIER = 20;
    public static final boolean DEFAULT_DIFFICULTY_AFFECTS_BABY_MOBS = false;
    public static final int MIN_PROGRESSION_LEVEL_DELAY = 0;
    public static final int MAX_PROGRESSION_LEVEL_DELAY = 100;
    public static final int DEFAULT_PROGRESSION_LEVEL_DELAY = 1;
    public static final boolean DEFAULT_FIRST_LEVEL_NO_HOSTILES = true;
    public static final boolean DEFAULT_FIRST_LEVEL_EASY_MOBS = true;
    public static final boolean DEFAULT_FIRST_LEVEL_STARTER_LOOT = true;
    // Onboarding stage lengths in carriages of player progress (independent of carriagesPerTier).
    public static final int MIN_ONBOARDING_STAGE_CARRIAGES = 0;
    public static final int MAX_ONBOARDING_STAGE_CARRIAGES = 1000;
    public static final int DEFAULT_FIRST_LEVEL_NO_HOSTILES_CARRIAGES = 10;
    public static final int DEFAULT_FIRST_LEVEL_EASY_MOBS_CARRIAGES = 15;

    /** 1-in-N chance that a book dropped by breaking a bookshelf becomes a narrative Random Book. 0 disables. */
    public static final int MIN_RANDOM_BOOK_ONE_IN = 0;
    public static final int MAX_RANDOM_BOOK_ONE_IN = 1_000_000;
    public static final int DEFAULT_RANDOM_BOOK_ONE_IN = 100;

    public static final boolean DEFAULT_DEATH_REPORT_TO_DISCORD = true;

    public static final boolean DEFAULT_FREE_PLAY_NOTICE_TO_DISCORD = true;

    public static final boolean DEFAULT_ECHO_ENCOUNTER_TO_DISCORD = true;

    /** Play the fly-up spawn cinematic the first time each player enters a world. */
    public static final boolean DEFAULT_INTRO_CINEMATIC_ENABLED = true;
    public static final int MIN_INTRO_DURATION_TICKS = 20;
    public static final int MAX_INTRO_DURATION_TICKS = 600;
    public static final int DEFAULT_INTRO_DURATION_TICKS = 120;

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue NUM_CARRIAGES;
    public static final ModConfigSpec.DoubleValue SPEED;
    public static final ModConfigSpec.IntValue TRAIN_Y;
    public static final ModConfigSpec.BooleanValue GENERATE_TRACKS;
    public static final ModConfigSpec.BooleanValue GENERATE_TUNNELS;
    public static final ModConfigSpec.EnumValue<CarriageGenerationMode> GENERATION_MODE;
    public static final ModConfigSpec.IntValue GROUP_SIZE;
    public static final ModConfigSpec.BooleanValue DIFFICULTY_ENABLED;
    public static final ModConfigSpec.IntValue CARRIAGES_PER_TIER;
    public static final ModConfigSpec.BooleanValue DIFFICULTY_AFFECTS_BABY_MOBS;
    public static final ModConfigSpec.IntValue PROGRESSION_LEVEL_DELAY;
    public static final ModConfigSpec.BooleanValue FIRST_LEVEL_NO_HOSTILES;
    public static final ModConfigSpec.IntValue FIRST_LEVEL_NO_HOSTILES_CARRIAGES;
    public static final ModConfigSpec.BooleanValue FIRST_LEVEL_EASY_MOBS;
    public static final ModConfigSpec.IntValue FIRST_LEVEL_EASY_MOBS_CARRIAGES;
    public static final ModConfigSpec.BooleanValue FIRST_LEVEL_STARTER_LOOT;
    public static final ModConfigSpec.IntValue RANDOM_BOOK_FROM_BOOKSHELF_ONE_IN;
    public static final ModConfigSpec.BooleanValue DEATH_REPORT_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue FREE_PLAY_NOTICE_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue ECHO_ENCOUNTER_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue INTRO_CINEMATIC_ENABLED;
    public static final ModConfigSpec.IntValue INTRO_CINEMATIC_DURATION_TICKS;

    static {
        Pair<Holder, ModConfigSpec> pair = new ModConfigSpec.Builder()
                .configure(DungeonTrainConfig::build);
        SPEC = pair.getRight();
        NUM_CARRIAGES = pair.getLeft().numCarriages;
        SPEED = pair.getLeft().speed;
        TRAIN_Y = pair.getLeft().trainY;
        GENERATE_TRACKS = pair.getLeft().generateTracks;
        GENERATE_TUNNELS = pair.getLeft().generateTunnels;
        GENERATION_MODE = pair.getLeft().generationMode;
        GROUP_SIZE = pair.getLeft().groupSize;
        DIFFICULTY_ENABLED = pair.getLeft().difficultyEnabled;
        CARRIAGES_PER_TIER = pair.getLeft().carriagesPerTier;
        DIFFICULTY_AFFECTS_BABY_MOBS = pair.getLeft().difficultyAffectsBabyMobs;
        PROGRESSION_LEVEL_DELAY = pair.getLeft().progressionLevelDelay;
        FIRST_LEVEL_NO_HOSTILES = pair.getLeft().firstLevelNoHostiles;
        FIRST_LEVEL_NO_HOSTILES_CARRIAGES = pair.getLeft().firstLevelNoHostilesCarriages;
        FIRST_LEVEL_EASY_MOBS = pair.getLeft().firstLevelEasyMobs;
        FIRST_LEVEL_EASY_MOBS_CARRIAGES = pair.getLeft().firstLevelEasyMobsCarriages;
        FIRST_LEVEL_STARTER_LOOT = pair.getLeft().firstLevelStarterLoot;
        RANDOM_BOOK_FROM_BOOKSHELF_ONE_IN = pair.getLeft().randomBookFromBookshelfOneIn;
        DEATH_REPORT_TO_DISCORD = pair.getLeft().deathReportToDiscord;
        FREE_PLAY_NOTICE_TO_DISCORD = pair.getLeft().freePlayNoticeToDiscord;
        ECHO_ENCOUNTER_TO_DISCORD = pair.getLeft().echoEncounterToDiscord;
        INTRO_CINEMATIC_ENABLED = pair.getLeft().introCinematicEnabled;
        INTRO_CINEMATIC_DURATION_TICKS = pair.getLeft().introCinematicDurationTicks;
    }

    private DungeonTrainConfig() {}

    private static Holder build(ModConfigSpec.Builder b) {
        b.push("train");
        ModConfigSpec.IntValue numCarriages = b
                .comment("Carriages visible in the rolling window around each player. Set to 0 to auto-scale to each player's render distance (recommended). Positive values pin the count; e.g. 15 = 5 groups at the default groupSize of 3.")
                .defineInRange("numCarriages", DEFAULT_CARRIAGES, MIN_CARRIAGES, MAX_CARRIAGES);
        ModConfigSpec.DoubleValue speed = b
                .comment("Train speed along +X in blocks per second.")
                .defineInRange("speed", DEFAULT_SPEED, MIN_SPEED, MAX_SPEED);
        ModConfigSpec.IntValue trainY = b
                .comment("World Y coordinate where new trains spawn. Applies to the next spawn only; existing trains keep their current Y.")
                .defineInRange("trainY", DEFAULT_TRAIN_Y, MIN_TRAIN_Y, MAX_TRAIN_Y);
        ModConfigSpec.BooleanValue generateTracks = b
                .comment("Auto-generate stone-brick tracks, rails, and bridge pillars under every active train.")
                .define("generateTracks", DEFAULT_GENERATE_TRACKS);
        ModConfigSpec.BooleanValue generateTunnels = b
                .comment("Auto-generate stone-brick tunnels with stepped portal entrances where the train runs through thick underground rock.")
                .define("generateTunnels", DEFAULT_GENERATE_TUNNELS);
        ModConfigSpec.EnumValue<CarriageGenerationMode> generationMode = b
                .comment("How carriage variants are chosen. RANDOM = each index picks one of the built-in types at random (seeded per-world for determinism). RANDOM_GROUPED = groups of N random non-flatbed carriages separated by a flatbed. LOOPING = original cycle (STANDARD → WINDOWED → FLATBED).")
                .defineEnum("generationMode", DEFAULT_GENERATION_MODE);
        ModConfigSpec.IntValue groupSize = b
                .comment("Non-flatbed run length for RANDOM_GROUPED: every Nth carriage becomes a flatbed separator. Ignored by RANDOM and LOOPING modes.")
                .defineInRange("groupSize", DEFAULT_GROUP_SIZE,
                        CarriageGenerationConfig.MIN_GROUP_SIZE,
                        CarriageGenerationConfig.MAX_GROUP_SIZE);
        b.pop();
        b.push("difficulty");
        ModConfigSpec.BooleanValue difficultyEnabled = b
                .comment("Enable carriage-index-based difficulty progression. When true, mobs spawned by carriage variants receive armor, weapons, potion effects, and enchantments based on their carriage's tier.")
                .define("difficultyEnabled", DEFAULT_DIFFICULTY_ENABLED);
        ModConfigSpec.IntValue carriagesPerTier = b
                .comment("Number of carriages per tier step. tierIndex = floor(abs(pIdx) / carriagesPerTier), clamped to the loaded tier list. Game default 20; set to 1 for fast-paced/testing progression.")
                .defineInRange("carriagesPerTier", DEFAULT_CARRIAGES_PER_TIER, MIN_CARRIAGES_PER_TIER, MAX_CARRIAGES_PER_TIER);
        ModConfigSpec.BooleanValue difficultyAffectsBabyMobs = b
                .comment("When true, baby mobs (zombies, piglins, etc.) also receive difficulty gear and effects. Default false to avoid silly visuals (baby zombies in netherite).")
                .define("difficultyAffectsBabyMobs", DEFAULT_DIFFICULTY_AFFECTS_BABY_MOBS);
        ModConfigSpec.IntValue progressionLevelDelay = b
                .comment("Delay difficulty progression by this many levels (tiers). The effective Diff-Level driving mob gear, potion effects, villager trade caps, and the boarding HUD becomes max(0, rawTier - this), where rawTier = floor(abs(travelled) / carriagesPerTier). Default 1 = the whole difficulty curve arrives one level later. 0 = no delay (original curve).")
                .defineInRange("progressionLevelDelay", DEFAULT_PROGRESSION_LEVEL_DELAY, MIN_PROGRESSION_LEVEL_DELAY, MAX_PROGRESSION_LEVEL_DELAY);
        ModConfigSpec.BooleanValue firstLevelNoHostiles = b
                .comment("First onboarding stage. When true, hostile (Enemy) mobs authored into carriage interiors do not spawn at all while the lead player is within the first firstLevelNoHostilesCarriages carriages of progress, for a combat-free opening stretch. Passive/neutral carriage mobs (villagers, traders, animals, PlayerMobs) are unaffected. Keys off raw travelled carriages (independent of progressionLevelDelay).")
                .define("firstLevelNoHostiles", DEFAULT_FIRST_LEVEL_NO_HOSTILES);
        ModConfigSpec.IntValue firstLevelNoHostilesCarriages = b
                .comment("Length, in carriages of player progress, of the no-hostiles opening stage (see firstLevelNoHostiles). Default 10. Independent of carriagesPerTier; 0 disables the stage.")
                .defineInRange("firstLevelNoHostilesCarriages", DEFAULT_FIRST_LEVEL_NO_HOSTILES_CARRIAGES, MIN_ONBOARDING_STAGE_CARRIAGES, MAX_ONBOARDING_STAGE_CARRIAGES);
        ModConfigSpec.BooleanValue firstLevelEasyMobs = b
                .comment("Second onboarding stage. When true, hostile (Enemy) mobs authored into carriage interiors are replaced with small slimes (or small magma cubes for nether/raider mobs, per the dungeontrain:first_band_magma_mobs entity-type tag; piglins/piglin brutes in dungeontrain:first_band_nether_only_mobs only become magma cubes in the Nether and otherwise spawn as authored; mobs in dungeontrain:first_band_no_substitute_mobs, e.g. zombified piglins, are never substituted) while the lead player is within the slimes stage — the firstLevelEasyMobsCarriages carriages that follow the no-hostiles stage — giving an easy combat intro. Passive/neutral carriage mobs (villagers, animals, PlayerMobs) are unaffected. Keys off raw travelled carriages (independent of progressionLevelDelay).")
                .define("firstLevelEasyMobs", DEFAULT_FIRST_LEVEL_EASY_MOBS);
        ModConfigSpec.IntValue firstLevelEasyMobsCarriages = b
                .comment("Length, in carriages of player progress, of the slimes stage (see firstLevelEasyMobs), starting after the no-hostiles stage. Default 15. Independent of carriagesPerTier; 0 disables the stage.")
                .defineInRange("firstLevelEasyMobsCarriages", DEFAULT_FIRST_LEVEL_EASY_MOBS_CARRIAGES, MIN_ONBOARDING_STAGE_CARRIAGES, MAX_ONBOARDING_STAGE_CARRIAGES);
        ModConfigSpec.BooleanValue firstLevelStarterLoot = b
                .comment("When true, carriage-interior chests linked to the rich 'loot' or 'loot_irongold' loot prefabs instead roll the 'starter' prefab while the lead player is still within the gentle opening window (the firstLevelNoHostilesCarriages + firstLevelEasyMobsCarriages carriages spanning both the no-hostiles and slimes stages), so the easy intro hands out starter-tier loot. Other loot prefabs (wood, stone, mining, villager, etc.) are unaffected. Keys off raw travelled carriages (independent of progressionLevelDelay).")
                .define("firstLevelStarterLoot", DEFAULT_FIRST_LEVEL_STARTER_LOOT);
        b.pop();
        b.push("narrative");
        ModConfigSpec.IntValue randomBookFromBookshelfOneIn = b
                .comment("1-in-N chance that each book dropped by breaking a vanilla bookshelf is replaced with a narrative Random Book (from data/dungeontrain/narratives/random_books). Total drop count is unchanged. Default 100 (~1%). Set to 0 to disable.")
                .defineInRange("randomBookFromBookshelfOneIn", DEFAULT_RANDOM_BOOK_ONE_IN, MIN_RANDOM_BOOK_ONE_IN, MAX_RANDOM_BOOK_ONE_IN);
        b.pop();
        b.push("discord");
        ModConfigSpec.BooleanValue deathReportToDiscord = b
                .comment("Post a run-summary embed to Discord when a player dies — distance, time, carts, mobs,",
                        "loot, books, plus an image of the most-used weapon and worn armor — mirroring the in-game",
                        "death screen. Requires the bundled Discord Presence mod with a webhookUrl configured in",
                        "config/discordpresence-server.toml. To avoid a duplicate post, also set autoDeathReport=false",
                        "there (this richer report replaces Discord Presence's basic vanilla one).")
                .define("deathReportToDiscord", DEFAULT_DEATH_REPORT_TO_DISCORD);
        ModConfigSpec.BooleanValue freePlayNoticeToDiscord = b
                .comment("Post a short embed to Discord when a player's run first enters Free Play — i.e. they",
                        "switch to creative/spectator, run a non-allowlisted cheat command, or join a world already",
                        "in creative. Posts into that player's Discord thread, naming what tripped Free Play. Fires",
                        "once per run/world. Requires the bundled Discord Presence mod with a webhookUrl configured in",
                        "config/discordpresence-server.toml.")
                .define("freePlayNoticeToDiscord", DEFAULT_FREE_PLAY_NOTICE_TO_DISCORD);
        ModConfigSpec.BooleanValue echoEncounterToDiscord = b
                .comment("Post a short 'encounter story' to Discord when a player's run with a REMOTE echo ends — a",
                        "PlayerMob embodying a player who died in another world (requires PlayerMob + Discord Presence's",
                        "cross-world reincarnation bridge). The story names who the echo was and recounts how the player",
                        "interacted with it (met, traded, fought, shoved off the train, killed…). Posts top-level under the",
                        "player's name; requires a webhookUrl in config/discordpresence-server.toml. Local echoes never post.")
                .define("echoEncounterToDiscord", DEFAULT_ECHO_ENCOUNTER_TO_DISCORD);
        b.pop();
        b.push("intro");
        ModConfigSpec.BooleanValue introCinematicEnabled = b
                .comment("Play a fly-up camera cinematic the first time each player spawns into a world. The player spawns",
                        "standing on a flatbed deck of the moving train; the camera starts at the old ground spawn position,",
                        "rises and eases back while tracking the player. Press Space in-game to skip.")
                .define("introCinematicEnabled", DEFAULT_INTRO_CINEMATIC_ENABLED);
        ModConfigSpec.IntValue introCinematicDurationTicks = b
                .comment("Intro cinematic duration in ticks (20 ticks = 1 second).")
                .defineInRange("introCinematicDurationTicks", DEFAULT_INTRO_DURATION_TICKS,
                        MIN_INTRO_DURATION_TICKS, MAX_INTRO_DURATION_TICKS);
        b.pop();
        return new Holder(numCarriages, speed, trainY, generateTracks, generateTunnels, generationMode, groupSize,
                difficultyEnabled, carriagesPerTier, difficultyAffectsBabyMobs, progressionLevelDelay,
                firstLevelNoHostiles, firstLevelNoHostilesCarriages, firstLevelEasyMobs, firstLevelEasyMobsCarriages,
                firstLevelStarterLoot, randomBookFromBookshelfOneIn, deathReportToDiscord,
                freePlayNoticeToDiscord, echoEncounterToDiscord, introCinematicEnabled, introCinematicDurationTicks);
    }

    /**
     * SERVER config is loaded per-world, so outside an active world (e.g. the
     * title-screen Mods → Config menu) these values aren't available yet.
     * Call this before reading or writing to avoid IllegalStateException.
     */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    public static int getNumCarriages() {
        return isLoaded() ? NUM_CARRIAGES.get() : DEFAULT_CARRIAGES;
    }

    public static double getSpeed() {
        return isLoaded() ? SPEED.get() : DEFAULT_SPEED;
    }

    public static int getTrainY() {
        return isLoaded() ? TRAIN_Y.get() : DEFAULT_TRAIN_Y;
    }

    public static boolean getGenerateTracks() {
        return isLoaded() ? GENERATE_TRACKS.get() : DEFAULT_GENERATE_TRACKS;
    }

    public static boolean getGenerateTunnels() {
        return isLoaded() ? GENERATE_TUNNELS.get() : DEFAULT_GENERATE_TUNNELS;
    }

    public static CarriageGenerationMode getGenerationMode() {
        return isLoaded() ? GENERATION_MODE.get() : DEFAULT_GENERATION_MODE;
    }

    public static int getGroupSize() {
        return isLoaded() ? GROUP_SIZE.get() : DEFAULT_GROUP_SIZE;
    }

    public static boolean getDifficultyEnabled() {
        return isLoaded() ? DIFFICULTY_ENABLED.get() : DEFAULT_DIFFICULTY_ENABLED;
    }

    public static int getCarriagesPerTier() {
        return isLoaded() ? CARRIAGES_PER_TIER.get() : DEFAULT_CARRIAGES_PER_TIER;
    }

    public static boolean getDifficultyAffectsBabyMobs() {
        return isLoaded() ? DIFFICULTY_AFFECTS_BABY_MOBS.get() : DEFAULT_DIFFICULTY_AFFECTS_BABY_MOBS;
    }

    /** Difficulty levels (tiers) by which the whole progression curve is delayed; 0 = no delay. */
    public static int getProgressionLevelDelay() {
        return isLoaded() ? PROGRESSION_LEVEL_DELAY.get() : DEFAULT_PROGRESSION_LEVEL_DELAY;
    }

    /** When true, authored hostile carriage mobs do not spawn during the no-hostiles opening stage. */
    public static boolean getFirstLevelNoHostiles() {
        return isLoaded() ? FIRST_LEVEL_NO_HOSTILES.get() : DEFAULT_FIRST_LEVEL_NO_HOSTILES;
    }

    /** Length (carriages of player progress) of the no-hostiles opening stage. */
    public static int getFirstLevelNoHostilesCarriages() {
        return isLoaded() ? FIRST_LEVEL_NO_HOSTILES_CARRIAGES.get() : DEFAULT_FIRST_LEVEL_NO_HOSTILES_CARRIAGES;
    }

    /** When true, authored hostile carriage mobs are replaced with small slimes/magma cubes during the slimes stage. */
    public static boolean getFirstLevelEasyMobs() {
        return isLoaded() ? FIRST_LEVEL_EASY_MOBS.get() : DEFAULT_FIRST_LEVEL_EASY_MOBS;
    }

    /** Length (carriages of player progress) of the slimes stage, following the no-hostiles stage. */
    public static int getFirstLevelEasyMobsCarriages() {
        return isLoaded() ? FIRST_LEVEL_EASY_MOBS_CARRIAGES.get() : DEFAULT_FIRST_LEVEL_EASY_MOBS_CARRIAGES;
    }

    /** When true, rich loot/loot_irongold carriage chests roll the starter prefab during the gentle opening window. */
    public static boolean getFirstLevelStarterLoot() {
        return isLoaded() ? FIRST_LEVEL_STARTER_LOOT.get() : DEFAULT_FIRST_LEVEL_STARTER_LOOT;
    }

    /** 1-in-N chance a book dropped by breaking a bookshelf becomes a narrative Random Book; 0 disables. */
    public static int getRandomBookFromBookshelfOneIn() {
        return isLoaded() ? RANDOM_BOOK_FROM_BOOKSHELF_ONE_IN.get() : DEFAULT_RANDOM_BOOK_ONE_IN;
    }

    /** Whether to post the death-screen run summary to Discord (via the bundled Discord Presence mod). */
    public static boolean isDeathReportToDiscord() {
        return isLoaded() ? DEATH_REPORT_TO_DISCORD.get() : DEFAULT_DEATH_REPORT_TO_DISCORD;
    }

    /** Whether to post a notice to Discord when a player's run enters Free Play (via the bundled Discord Presence mod). */
    public static boolean isFreePlayNoticeToDiscord() {
        return isLoaded() ? FREE_PLAY_NOTICE_TO_DISCORD.get() : DEFAULT_FREE_PLAY_NOTICE_TO_DISCORD;
    }

    /** Whether to post a remote-echo encounter story to Discord when such an encounter ends. */
    public static boolean isEchoEncounterToDiscord() {
        return isLoaded() ? ECHO_ENCOUNTER_TO_DISCORD.get() : DEFAULT_ECHO_ENCOUNTER_TO_DISCORD;
    }

    /** Whether the fly-up spawn cinematic plays the first time a player enters a world. */
    public static boolean isIntroCinematicEnabled() {
        return isLoaded() ? INTRO_CINEMATIC_ENABLED.get() : DEFAULT_INTRO_CINEMATIC_ENABLED;
    }

    /** Intro cinematic length in ticks (20 = 1s). */
    public static int getIntroCinematicDurationTicks() {
        return isLoaded() ? INTRO_CINEMATIC_DURATION_TICKS.get() : DEFAULT_INTRO_DURATION_TICKS;
    }

    public static void setNumCarriages(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(MIN_CARRIAGES, Math.min(MAX_CARRIAGES, value));
        NUM_CARRIAGES.set(clamped);
        NUM_CARRIAGES.save();
    }

    public static void setSpeed(double value) {
        if (!isLoaded()) return;
        double clamped = Math.max(MIN_SPEED, Math.min(MAX_SPEED, value));
        SPEED.set(clamped);
        SPEED.save();
    }

    public static void setTrainY(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(MIN_TRAIN_Y, Math.min(MAX_TRAIN_Y, value));
        TRAIN_Y.set(clamped);
        TRAIN_Y.save();
    }

    public static void setGenerateTracks(boolean value) {
        if (!isLoaded()) return;
        GENERATE_TRACKS.set(value);
        GENERATE_TRACKS.save();
    }

    public static void setGenerateTunnels(boolean value) {
        if (!isLoaded()) return;
        GENERATE_TUNNELS.set(value);
        GENERATE_TUNNELS.save();
    }

    public static void setGenerationMode(CarriageGenerationMode value) {
        if (!isLoaded() || value == null) return;
        GENERATION_MODE.set(value);
        GENERATION_MODE.save();
    }

    public static void setGroupSize(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(CarriageGenerationConfig.MIN_GROUP_SIZE,
                Math.min(CarriageGenerationConfig.MAX_GROUP_SIZE, value));
        GROUP_SIZE.set(clamped);
        GROUP_SIZE.save();
    }

    public static void setDifficultyEnabled(boolean value) {
        if (!isLoaded()) return;
        DIFFICULTY_ENABLED.set(value);
        DIFFICULTY_ENABLED.save();
    }

    public static void setCarriagesPerTier(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(MIN_CARRIAGES_PER_TIER, Math.min(MAX_CARRIAGES_PER_TIER, value));
        CARRIAGES_PER_TIER.set(clamped);
        CARRIAGES_PER_TIER.save();
    }

    public static void setDifficultyAffectsBabyMobs(boolean value) {
        if (!isLoaded()) return;
        DIFFICULTY_AFFECTS_BABY_MOBS.set(value);
        DIFFICULTY_AFFECTS_BABY_MOBS.save();
    }

    private record Holder(
            ModConfigSpec.IntValue numCarriages,
            ModConfigSpec.DoubleValue speed,
            ModConfigSpec.IntValue trainY,
            ModConfigSpec.BooleanValue generateTracks,
            ModConfigSpec.BooleanValue generateTunnels,
            ModConfigSpec.EnumValue<CarriageGenerationMode> generationMode,
            ModConfigSpec.IntValue groupSize,
            ModConfigSpec.BooleanValue difficultyEnabled,
            ModConfigSpec.IntValue carriagesPerTier,
            ModConfigSpec.BooleanValue difficultyAffectsBabyMobs,
            ModConfigSpec.IntValue progressionLevelDelay,
            ModConfigSpec.BooleanValue firstLevelNoHostiles,
            ModConfigSpec.IntValue firstLevelNoHostilesCarriages,
            ModConfigSpec.BooleanValue firstLevelEasyMobs,
            ModConfigSpec.IntValue firstLevelEasyMobsCarriages,
            ModConfigSpec.BooleanValue firstLevelStarterLoot,
            ModConfigSpec.IntValue randomBookFromBookshelfOneIn,
            ModConfigSpec.BooleanValue deathReportToDiscord,
            ModConfigSpec.BooleanValue freePlayNoticeToDiscord,
            ModConfigSpec.BooleanValue echoEncounterToDiscord,
            ModConfigSpec.BooleanValue introCinematicEnabled,
            ModConfigSpec.IntValue introCinematicDurationTicks
    ) {}
}
