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
    /** Max tier directly requestable via {@code /dungeontrain difficulty <tier>}. */
    public static final int MAX_REQUESTED_DIFFICULTY_TIER = 1000;
    /** Generous symmetric bound for the persisted travelled offset — it's always a computed (target - raw) difference, never set directly. */
    public static final int MIN_DIFFICULTY_TRAVELLED_OFFSET = -1_000_000;
    public static final int MAX_DIFFICULTY_TRAVELLED_OFFSET = 1_000_000;
    public static final int DEFAULT_DIFFICULTY_TRAVELLED_OFFSET = 0;
    public static final boolean DEFAULT_DIFFICULTY_AFFECTS_BABY_MOBS = false;
    public static final int MIN_PROGRESSION_LEVEL_DELAY = 0;
    public static final int MAX_PROGRESSION_LEVEL_DELAY = 100;
    public static final int DEFAULT_PROGRESSION_LEVEL_DELAY = 1;
    public static final boolean DEFAULT_DIFFICULTY_SCALE_HOSTILE_GEAR_PAST_CAP = true;
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

    public static final boolean DEFAULT_DEV_MESSAGE_CONSENT_TO_DISCORD = true;

    public static final boolean DEFAULT_ECHO_ENCOUNTER_TO_DISCORD = true;

    public static final boolean DEFAULT_WORLD_JOIN_REPORT_TO_DISCORD = true;

    /** Default for POSTing per-join world-info telemetry (world/train seeds + regen inputs + mods) to the relay. */
    public static final boolean DEFAULT_WORLD_INFO_TO_RELAY = true;

    /** Default master for the community "share books" contribution half (uploading signed books). */
    public static final boolean DEFAULT_SHARE_BOOKS_ENABLED = true;

    /** Default master for the community "shared books" discovery half (found in chest loot). */
    public static final boolean DEFAULT_DISCOVER_SHARED_BOOKS_ENABLED = true;

    /** Default master for the "Death Note" curse book mechanic (local sign/burn + relay sync). */
    public static final boolean DEFAULT_DEATH_NOTES_ENABLED = true;

    /**
     * STARTING max chance a rolled chest book comes from the shared community pool. The effective
     * chance scales from 0% (no hardcoded random books read) up to this max (100% of them read), so
     * community books surface only as a world exhausts the hand-authored pool. This max is not fixed:
     * it tapers down from the configured value toward the community pool's fair share {@code P/(P+V)}
     * as the world reads more community books, so late-game loot settles at an even distribution across
     * all books instead of staying community-heavy. Default 0.75.
     */
    public static final double DEFAULT_SHARED_BOOK_LOOT_MAX_CHANCE = 0.75;
    public static final double MIN_SHARED_BOOK_LOOT_CHANCE = 0.0;
    public static final double MAX_SHARED_BOOK_LOOT_CHANCE = 1.0;

    public static final boolean DEFAULT_DIFFICULTY_LEVEL_NOTICE_TO_DISCORD = true;

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
    public static final ModConfigSpec.IntValue DIFFICULTY_TRAVELLED_OFFSET;
    public static final ModConfigSpec.BooleanValue DIFFICULTY_AFFECTS_BABY_MOBS;
    public static final ModConfigSpec.IntValue PROGRESSION_LEVEL_DELAY;
    public static final ModConfigSpec.BooleanValue DIFFICULTY_SCALE_HOSTILE_GEAR_PAST_CAP;
    public static final ModConfigSpec.BooleanValue FIRST_LEVEL_NO_HOSTILES;
    public static final ModConfigSpec.IntValue FIRST_LEVEL_NO_HOSTILES_CARRIAGES;
    public static final ModConfigSpec.BooleanValue FIRST_LEVEL_EASY_MOBS;
    public static final ModConfigSpec.IntValue FIRST_LEVEL_EASY_MOBS_CARRIAGES;
    public static final ModConfigSpec.BooleanValue FIRST_LEVEL_STARTER_LOOT;
    public static final ModConfigSpec.IntValue RANDOM_BOOK_FROM_BOOKSHELF_ONE_IN;
    public static final ModConfigSpec.BooleanValue DEATH_REPORT_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue FREE_PLAY_NOTICE_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue DEV_MESSAGE_CONSENT_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue ECHO_ENCOUNTER_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue WORLD_JOIN_REPORT_TO_DISCORD;
    public static final ModConfigSpec.BooleanValue WORLD_INFO_TO_RELAY;
    public static final ModConfigSpec.BooleanValue SHARE_BOOKS_ENABLED;
    public static final ModConfigSpec.BooleanValue DISCOVER_SHARED_BOOKS_ENABLED;
    public static final ModConfigSpec.BooleanValue DEATH_NOTES_ENABLED;
    public static final ModConfigSpec.DoubleValue SHARED_BOOK_LOOT_MAX_CHANCE;
    public static final ModConfigSpec.BooleanValue DIFFICULTY_LEVEL_NOTICE_TO_DISCORD;
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
        DIFFICULTY_TRAVELLED_OFFSET = pair.getLeft().difficultyTravelledOffset;
        DIFFICULTY_AFFECTS_BABY_MOBS = pair.getLeft().difficultyAffectsBabyMobs;
        PROGRESSION_LEVEL_DELAY = pair.getLeft().progressionLevelDelay;
        DIFFICULTY_SCALE_HOSTILE_GEAR_PAST_CAP = pair.getLeft().difficultyScaleHostileGearPastCap;
        FIRST_LEVEL_NO_HOSTILES = pair.getLeft().firstLevelNoHostiles;
        FIRST_LEVEL_NO_HOSTILES_CARRIAGES = pair.getLeft().firstLevelNoHostilesCarriages;
        FIRST_LEVEL_EASY_MOBS = pair.getLeft().firstLevelEasyMobs;
        FIRST_LEVEL_EASY_MOBS_CARRIAGES = pair.getLeft().firstLevelEasyMobsCarriages;
        FIRST_LEVEL_STARTER_LOOT = pair.getLeft().firstLevelStarterLoot;
        RANDOM_BOOK_FROM_BOOKSHELF_ONE_IN = pair.getLeft().randomBookFromBookshelfOneIn;
        DEATH_REPORT_TO_DISCORD = pair.getLeft().deathReportToDiscord;
        FREE_PLAY_NOTICE_TO_DISCORD = pair.getLeft().freePlayNoticeToDiscord;
        DEV_MESSAGE_CONSENT_TO_DISCORD = pair.getLeft().devMessageConsentToDiscord;
        ECHO_ENCOUNTER_TO_DISCORD = pair.getLeft().echoEncounterToDiscord;
        WORLD_JOIN_REPORT_TO_DISCORD = pair.getLeft().worldJoinReportToDiscord;
        WORLD_INFO_TO_RELAY = pair.getLeft().worldInfoToRelay;
        SHARE_BOOKS_ENABLED = pair.getLeft().shareBooksEnabled;
        DISCOVER_SHARED_BOOKS_ENABLED = pair.getLeft().discoverSharedBooksEnabled;
        DEATH_NOTES_ENABLED = pair.getLeft().deathNotesEnabled;
        SHARED_BOOK_LOOT_MAX_CHANCE = pair.getLeft().sharedBookLootMaxChance;
        DIFFICULTY_LEVEL_NOTICE_TO_DISCORD = pair.getLeft().difficultyLevelNoticeToDiscord;
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
        ModConfigSpec.IntValue difficultyTravelledOffset = b
                .comment("Signed offset (in carriages) added to every player's live travelled-carriage progress for difficulty purposes: the game behaves as if each player had travelled this many extra carriages. Shifts the boarding HUD (Diff-Car + Diff-Level), the onboarding stages (no-hostiles/slimes), mob gearing, villager trade caps, carriage-distance achievements, and Discord level-up posts — all together. 0 (default) = fully automatic. Set via /dungeontrain difficulty <tier>, which recomputes this as (target carriages for the requested tier - current raw progress) so the effective tier becomes exactly what was requested at that moment; the offset then stays fixed while real travel keeps moving the effective value, until the next command invocation re-anchors it. /dungeontrain difficulty auto resets this to 0. Does NOT affect the deterministic per-carriage world-gen tier (that keys off carriage position, not player progress).")
                .defineInRange("difficultyTravelledOffset", DEFAULT_DIFFICULTY_TRAVELLED_OFFSET, MIN_DIFFICULTY_TRAVELLED_OFFSET, MAX_DIFFICULTY_TRAVELLED_OFFSET);
        ModConfigSpec.BooleanValue difficultyAffectsBabyMobs = b
                .comment("When true, baby mobs (zombies, piglins, etc.) also receive difficulty gear and effects. Default false to avoid silly visuals (baby zombies in netherite).")
                .define("difficultyAffectsBabyMobs", DEFAULT_DIFFICULTY_AFFECTS_BABY_MOBS);
        ModConfigSpec.IntValue progressionLevelDelay = b
                .comment("Delay difficulty progression by this many levels (tiers). The effective Diff-Level driving mob gear, potion effects, villager trade caps, and the boarding HUD becomes max(0, rawTier - this), where rawTier = floor(abs(travelled) / carriagesPerTier). Default 1 = the whole difficulty curve arrives one level later. 0 = no delay (original curve).")
                .defineInRange("progressionLevelDelay", DEFAULT_PROGRESSION_LEVEL_DELAY, MIN_PROGRESSION_LEVEL_DELAY, MAX_PROGRESSION_LEVEL_DELAY);
        ModConfigSpec.BooleanValue difficultyScaleHostileGearPastCap = b
                .comment("When true, hostile carriage mobs keep gaining gear strength after their armor/weapon material caps at netherite (difficulty level 50): each rolled equipment piece gets a flat per-tier primary-stat bonus (attack damage on weapons, armor on armor) scaled by how far the tier is past the cap, so difficulty keeps climbing beyond ~level 50 instead of plateauing. Tiers 50 and below are unchanged. Reuses the same AIS stat-scaling PlayerMobs already receive. Default true; set false to restore the original behavior where hostile gear stops improving at netherite.")
                .define("difficultyScaleHostileGearPastCap", DEFAULT_DIFFICULTY_SCALE_HOSTILE_GEAR_PAST_CAP);
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
        ModConfigSpec.BooleanValue shareBooksEnabled = b
                .comment("Community shared books — CONTRIBUTION half. When true, signing a book & quill uploads its text to",
                        "the Dungeon Train relay (fire-and-forget) and burns the book away in your hand instead of keeping",
                        "the written copy; approved uploads may later appear in other players' chests. Uploading also",
                        "requires the player's client to have granted network consent (Discord Presence's 'use the",
                        "internet?' prompt). False disables uploading entirely — signing behaves like vanilla.")
                .define("shareBooksEnabled", DEFAULT_SHARE_BOOKS_ENABLED);
        ModConfigSpec.BooleanValue discoverSharedBooksEnabled = b
                .comment("Community shared books — DISCOVERY half. When true, some written books rolled into dungeon chests",
                        "are approved community submissions fetched from the relay, credited to their author, instead of the",
                        "local narrative random-book pool. Server-wide opt-in (not per-player). False keeps chest books",
                        "entirely local. See sharedBookLootMaxChance for the mix.")
                .define("discoverSharedBooksEnabled", DEFAULT_DISCOVER_SHARED_BOOKS_ENABLED);
        ModConfigSpec.BooleanValue deathNotesEnabled = b
                .comment("The \"Death Note\" curse book. When true, signing a book titled \"Death Note\" (any caps/spacing)",
                        "consumes it (burning it away with soul-fire) and marks the player named on its first page: after the",
                        "author later dies, the next time that target reaches the carriage the author died at, a hostile echo of",
                        "the author spawns to hunt them. Syncing the curse across players uses the relay and additionally",
                        "requires network consent (Discord Presence's 'use the internet?' prompt). False disables the mechanic",
                        "entirely — a \"Death Note\" signs like any other book.")
                .define("deathNotesEnabled", DEFAULT_DEATH_NOTES_ENABLED);
        ModConfigSpec.DoubleValue sharedBookLootMaxChance = b
                .comment("The STARTING maximum per-roll chance that a chest book comes from the shared community pool instead",
                        "of the local narrative pool. The effective chance SCALES with progress: 0% when none of the hardcoded",
                        "random books have been read, rising toward this max once 100% of them have been read — so community",
                        "books surface only as a world exhausts the hand-authored pool. This max also TAPERS over time: as the",
                        "world reads more community books it eases from the configured value toward the pool's fair share",
                        "(community books / all books), so late-game loot settles at an even distribution rather than staying",
                        "community-heavy. Default 0.75 (max 75%). Set 0.0 to disable shared books in loot. If the shared pool",
                        "is empty or the relay is unreachable, the roll silently falls back to the local pool regardless.")
                .defineInRange("sharedBookLootMaxChance", DEFAULT_SHARED_BOOK_LOOT_MAX_CHANCE,
                        MIN_SHARED_BOOK_LOOT_CHANCE, MAX_SHARED_BOOK_LOOT_CHANCE);
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
        ModConfigSpec.BooleanValue devMessageConsentToDiscord = b
                .comment("Post a short embed to Discord for the Developer-message consent handshake: one when the",
                        "in-game consent prompt is shown to a player (the Developer has a message waiting), and one",
                        "when the player accepts (types @Dev). Both post into that player's Discord thread. Requires",
                        "the bundled Discord Presence mod with a webhookUrl configured in config/discordpresence-server.toml.")
                .define("devMessageConsentToDiscord", DEFAULT_DEV_MESSAGE_CONSENT_TO_DISCORD);
        ModConfigSpec.BooleanValue echoEncounterToDiscord = b
                .comment("Post a short 'encounter story' to Discord when a player's run with a REMOTE echo ends — a",
                        "PlayerMob embodying a player who died in another world (requires PlayerMob + Discord Presence's",
                        "cross-world reincarnation bridge). The story names who the echo was and recounts how the player",
                        "interacted with it (met, traded, fought, shoved off the train, killed…). Posts top-level under the",
                        "player's name; requires a webhookUrl in config/discordpresence-server.toml. Local echoes never post.")
                .define("echoEncounterToDiscord", DEFAULT_ECHO_ENCOUNTER_TO_DISCORD);
        ModConfigSpec.BooleanValue worldJoinReportToDiscord = b
                .comment("Append a one-time world-info block to the player-join Discord message: the Dungeon Train",
                        "version, the train generation seed plus the data needed to regenerate the same train, and a",
                        "collapsed (spoiler) list of installed mods + versions. Fires once per world, into the joining",
                        "player's Discord thread — useful for reproducing and debugging a player's run. Requires the",
                        "bundled Discord Presence mod with a webhookUrl configured in config/discordpresence-server.toml.")
                .define("worldJoinReportToDiscord", DEFAULT_WORLD_JOIN_REPORT_TO_DISCORD);
        ModConfigSpec.BooleanValue worldInfoToRelay = b
                .comment("Send a small per-join 'world info' telemetry record to the Dungeon Train relay so the",
                        "private data explorer's Mods and Seeds cards can populate: the world seed, the train",
                        "generation seed plus the inputs needed to regenerate the same train (mode, group size,",
                        "carriage dims, train Y, starting dimension), the Dungeon Train version, the launcher, and",
                        "the list of installed mods + versions. Fires on every join (the relay dedupes identical",
                        "records); carries no chat, location, or personal data beyond the Minecraft UUID + name",
                        "already sent to Discord. Independent of worldJoinReportToDiscord.")
                .define("worldInfoToRelay", DEFAULT_WORLD_INFO_TO_RELAY);
        ModConfigSpec.BooleanValue difficultyLevelNoticeToDiscord = b
                .comment("Post a short embed to Discord each time a player's difficulty tier increases — i.e. they",
                        "have advanced far enough through carriages to reach the next Difficulty Level. Fires once per",
                        "tier per life (tier resets to 0 on death). Posts into the player's Discord thread. Requires",
                        "the bundled Discord Presence mod with a webhookUrl configured in config/discordpresence-server.toml.")
                .define("difficultyLevelNoticeToDiscord", DEFAULT_DIFFICULTY_LEVEL_NOTICE_TO_DISCORD);
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
                difficultyEnabled, carriagesPerTier, difficultyTravelledOffset, difficultyAffectsBabyMobs, progressionLevelDelay,
                difficultyScaleHostileGearPastCap,
                firstLevelNoHostiles, firstLevelNoHostilesCarriages, firstLevelEasyMobs, firstLevelEasyMobsCarriages,
                firstLevelStarterLoot, randomBookFromBookshelfOneIn, deathReportToDiscord,
                freePlayNoticeToDiscord, devMessageConsentToDiscord, echoEncounterToDiscord, worldJoinReportToDiscord,
                worldInfoToRelay, shareBooksEnabled, discoverSharedBooksEnabled, deathNotesEnabled, sharedBookLootMaxChance,
                difficultyLevelNoticeToDiscord, introCinematicEnabled, introCinematicDurationTicks);
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

    /**
     * Signed offset (in carriages) added to every player's live travelled-carriage
     * progress for difficulty purposes — the game behaves as if each player had
     * travelled this many extra carriages (shifts HUD, onboarding, mob gear, trades,
     * achievements, Discord). 0 (default) = no adjustment. Re-anchored by
     * {@code /dungeontrain difficulty <tier>}; cleared to 0 by
     * {@code /dungeontrain difficulty auto}.
     */
    public static int getDifficultyTravelledOffset() {
        return isLoaded() ? DIFFICULTY_TRAVELLED_OFFSET.get() : DEFAULT_DIFFICULTY_TRAVELLED_OFFSET;
    }

    public static boolean getDifficultyAffectsBabyMobs() {
        return isLoaded() ? DIFFICULTY_AFFECTS_BABY_MOBS.get() : DEFAULT_DIFFICULTY_AFFECTS_BABY_MOBS;
    }

    /** Difficulty levels (tiers) by which the whole progression curve is delayed; 0 = no delay. */
    public static int getProgressionLevelDelay() {
        return isLoaded() ? PROGRESSION_LEVEL_DELAY.get() : DEFAULT_PROGRESSION_LEVEL_DELAY;
    }

    /** When true, hostile carriage mob gear keeps gaining per-tier stat bonuses past the netherite material cap (difficulty level 50). */
    public static boolean getDifficultyScaleHostileGearPastCap() {
        return isLoaded() ? DIFFICULTY_SCALE_HOSTILE_GEAR_PAST_CAP.get() : DEFAULT_DIFFICULTY_SCALE_HOSTILE_GEAR_PAST_CAP;
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

    /** Whether to post the Developer-message consent notices (requested / accepted) to Discord. */
    public static boolean isDevMessageConsentToDiscord() {
        return isLoaded() ? DEV_MESSAGE_CONSENT_TO_DISCORD.get() : DEFAULT_DEV_MESSAGE_CONSENT_TO_DISCORD;
    }

    /** Whether to post a remote-echo encounter story to Discord when such an encounter ends. */
    public static boolean isEchoEncounterToDiscord() {
        return isLoaded() ? ECHO_ENCOUNTER_TO_DISCORD.get() : DEFAULT_ECHO_ENCOUNTER_TO_DISCORD;
    }

    /** Whether to append the world-info block (DT version + train seed + mods) to the player-join Discord message. */
    public static boolean isWorldJoinReportToDiscord() {
        return isLoaded() ? WORLD_JOIN_REPORT_TO_DISCORD.get() : DEFAULT_WORLD_JOIN_REPORT_TO_DISCORD;
    }

    /** Whether to POST per-join world-info telemetry (world/train seeds + regen inputs + mods) to the relay. */
    public static boolean isWorldInfoToRelay() {
        return isLoaded() ? WORLD_INFO_TO_RELAY.get() : DEFAULT_WORLD_INFO_TO_RELAY;
    }

    /** Master for the community shared-books CONTRIBUTION half (upload + burn on sign). */
    public static boolean isShareBooksEnabled() {
        return isLoaded() ? SHARE_BOOKS_ENABLED.get() : DEFAULT_SHARE_BOOKS_ENABLED;
    }

    /** Master for the community shared-books DISCOVERY half (approved books in chest loot). */
    public static boolean isDiscoverSharedBooksEnabled() {
        return isLoaded() ? DISCOVER_SHARED_BOOKS_ENABLED.get() : DEFAULT_DISCOVER_SHARED_BOOKS_ENABLED;
    }

    /** Master for the "Death Note" curse book mechanic (local sign/burn + relay sync). */
    public static boolean isDeathNotesEnabled() {
        return isLoaded() ? DEATH_NOTES_ENABLED.get() : DEFAULT_DEATH_NOTES_ENABLED;
    }

    /** MAX shared-pool chance (reached at 100% hardcoded random books read); scaled by read fraction. Clamped [0,1]. */
    public static double getSharedBookLootMaxChance() {
        double v = isLoaded() ? SHARED_BOOK_LOOT_MAX_CHANCE.get() : DEFAULT_SHARED_BOOK_LOOT_MAX_CHANCE;
        return Math.max(MIN_SHARED_BOOK_LOOT_CHANCE, Math.min(MAX_SHARED_BOOK_LOOT_CHANCE, v));
    }

    /** Whether to post a notice to Discord each time a player's difficulty tier increases. */
    public static boolean isDifficultyLevelNoticeToDiscord() {
        return isLoaded() ? DIFFICULTY_LEVEL_NOTICE_TO_DISCORD.get() : DEFAULT_DIFFICULTY_LEVEL_NOTICE_TO_DISCORD;
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

    /** Sets the difficulty travelled-carriage offset directly; pass 0 to clear it (fully automatic). */
    public static void setDifficultyTravelledOffset(int value) {
        if (!isLoaded()) return;
        int clamped = Math.max(MIN_DIFFICULTY_TRAVELLED_OFFSET, Math.min(MAX_DIFFICULTY_TRAVELLED_OFFSET, value));
        DIFFICULTY_TRAVELLED_OFFSET.set(clamped);
        DIFFICULTY_TRAVELLED_OFFSET.save();
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
            ModConfigSpec.IntValue difficultyTravelledOffset,
            ModConfigSpec.BooleanValue difficultyAffectsBabyMobs,
            ModConfigSpec.IntValue progressionLevelDelay,
            ModConfigSpec.BooleanValue difficultyScaleHostileGearPastCap,
            ModConfigSpec.BooleanValue firstLevelNoHostiles,
            ModConfigSpec.IntValue firstLevelNoHostilesCarriages,
            ModConfigSpec.BooleanValue firstLevelEasyMobs,
            ModConfigSpec.IntValue firstLevelEasyMobsCarriages,
            ModConfigSpec.BooleanValue firstLevelStarterLoot,
            ModConfigSpec.IntValue randomBookFromBookshelfOneIn,
            ModConfigSpec.BooleanValue deathReportToDiscord,
            ModConfigSpec.BooleanValue freePlayNoticeToDiscord,
            ModConfigSpec.BooleanValue devMessageConsentToDiscord,
            ModConfigSpec.BooleanValue echoEncounterToDiscord,
            ModConfigSpec.BooleanValue worldJoinReportToDiscord,
            ModConfigSpec.BooleanValue worldInfoToRelay,
            ModConfigSpec.BooleanValue shareBooksEnabled,
            ModConfigSpec.BooleanValue discoverSharedBooksEnabled,
            ModConfigSpec.BooleanValue deathNotesEnabled,
            ModConfigSpec.DoubleValue sharedBookLootMaxChance,
            ModConfigSpec.BooleanValue difficultyLevelNoticeToDiscord,
            ModConfigSpec.BooleanValue introCinematicEnabled,
            ModConfigSpec.IntValue introCinematicDurationTicks
    ) {}
}
