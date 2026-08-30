package games.brennan.dungeontrain.cheat;

import com.electronwill.nightconfig.core.file.FileConfig;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.train.CarriageGenerationConfig;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dungeon Train's own balance config, held to its defaults — the DT-side twin of
 * {@link AisDataIntegrity}.
 *
 * <p>DT's difficulty curve, loot pacing and travel speed are what every global stat and
 * cross-world advancement is measured against. A world running with a modified
 * {@code dungeontrain-server.toml} / {@code dungeontrain-common.toml} is not playing the game
 * those numbers describe, so the whole server session runs in <b>Free Play</b>: stats and
 * advancements don't persist to the cross-world profile while the deviation exists.</p>
 *
 * <p>This closes a real hole rather than a theoretical one. The {@code /dungeontrain} tuning
 * subcommands write the <b>global</b> server config and save it, and the {@code RUN_CHEATED}
 * taint is per-world — so flattening the difficulty curve in one world and then starting a fresh
 * one gave a "clean" run on a rebalanced game. Hand-editing the toml tainted nothing at all.</p>
 *
 * <p><b>Session-only</b>, exactly like the AIS taint: re-checked at every server start, nothing
 * written to the world or player, and restoring the defaults restores normal play on the next
 * boot.</p>
 *
 * <p><b>Only balance keys are governed</b> — see {@link #GOVERNED}. Performance and visual knobs,
 * the Discord/relay privacy toggles, the player-content switches and everything in
 * {@code dungeontrain-client.toml} are deliberately excluded: a player turning off telemetry, or a
 * parent restricting content, must never be told they are cheating.</p>
 *
 * <p>The check reads the config <em>files</em> rather than the loaded specs, because the SERVER
 * spec is not loaded outside a world (see {@code DungeonTrainConfig.isLoaded}) and the same code
 * has to answer at the title screen for {@code ConfigDeviationScreen}. It mirrors NeoForge's own
 * load semantics: a missing file, a missing key, a wrong-typed value or one outside the entry's
 * range is what NeoForge would replace with the default, so none of those count as a deviation.
 * Config is player-editable data — bad input must never take the game down or false-positive.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DtConfigIntegrity {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** DT's two gameplay config files, under the loader config dir. */
    public static final String SERVER_FILE = "dungeontrain-server.toml";
    public static final String COMMON_FILE = "dungeontrain-common.toml";

    /**
     * One governed entry: which file it lives in, its dotted path, the expected (default) value,
     * and — for numbers — the range NeoForge would clamp to. A value outside that range is one
     * NeoForge replaces with the default on load, so it is not a deviation.
     *
     * <p>{@code sinceVersion} dates the <em>current</em> expected value to a
     * {@link DungeonTrainConfig#CURRENT_CONFIG_VERSION config version}: the version whose
     * {@code runPendingMigrations()} step writes it into files that predate it. {@link #ALWAYS} —
     * the value every key that has never had its default changed carries — means "compare
     * unconditionally". See {@link #notYetMigrated}.</p>
     */
    record Key(String file, String path, Object expected, double min, double max, int sinceVersion) {

        /** A key whose shipped default has never changed, so no file can be behind on it. */
        static final int ALWAYS = 0;

        static Key flag(String file, String path, boolean expected) {
            return new Key(file, path, expected, 0, 0, ALWAYS);
        }

        static Key number(String file, String path, Number expected, double min, double max) {
            return new Key(file, path, expected, min, max, ALWAYS);
        }

        static Key option(String file, String path, Enum<?> expected) {
            return new Key(file, path, expected, 0, 0, ALWAYS);
        }

        /**
         * As {@link #number}, for a key whose default changed in {@code sinceVersion}: a file
         * recording an older config version has not been migrated yet and is not judged on it.
         */
        static Key numberSince(String file, String path, Number expected, double min, double max,
                               int sinceVersion) {
            return new Key(file, path, expected, min, max, sinceVersion);
        }
    }

    /** The migration bookkeeping key, read alongside the governed ones to date the server file. */
    static final String CONFIG_VERSION_PATH = "configVersion";

    /**
     * The balance surface. Adding a key here makes changing it cost persistence, so each one has
     * to genuinely change the game the stats describe.
     *
     * <p>Two deliberate omissions worth naming. {@code difficulty.difficultyTravelledOffset} is a
     * mirror of per-world state — {@code DifficultyOffsetLifecycle} overwrites it from the world at
     * load, so reading the file would report the <em>previous</em> world's value; the tainting
     * {@code /dungeontrain difficulty} command already covers it. {@code train.numCarriages} is a
     * render-distance/performance knob, not a balance one.</p>
     */
    static final List<Key> GOVERNED = List.of(
        // --- dungeontrain-server.toml : [train] ---
        Key.number(SERVER_FILE, "train.speed", DungeonTrainConfig.DEFAULT_SPEED,
            DungeonTrainConfig.MIN_SPEED, DungeonTrainConfig.MAX_SPEED),
        Key.option(SERVER_FILE, "train.generationMode", DungeonTrainConfig.DEFAULT_GENERATION_MODE),
        Key.number(SERVER_FILE, "train.groupSize", DungeonTrainConfig.DEFAULT_GROUP_SIZE,
            CarriageGenerationConfig.MIN_GROUP_SIZE, CarriageGenerationConfig.MAX_GROUP_SIZE),

        // --- dungeontrain-server.toml : [difficulty] ---
        Key.flag(SERVER_FILE, "difficulty.difficultyEnabled",
            DungeonTrainConfig.DEFAULT_DIFFICULTY_ENABLED),
        Key.number(SERVER_FILE, "difficulty.carriagesPerTier",
            DungeonTrainConfig.DEFAULT_CARRIAGES_PER_TIER,
            DungeonTrainConfig.MIN_CARRIAGES_PER_TIER, DungeonTrainConfig.MAX_CARRIAGES_PER_TIER),
        Key.number(SERVER_FILE, "difficulty.progressionLevelDelay",
            DungeonTrainConfig.DEFAULT_PROGRESSION_LEVEL_DELAY,
            DungeonTrainConfig.MIN_PROGRESSION_LEVEL_DELAY,
            DungeonTrainConfig.MAX_PROGRESSION_LEVEL_DELAY),
        Key.flag(SERVER_FILE, "difficulty.difficultyAffectsBabyMobs",
            DungeonTrainConfig.DEFAULT_DIFFICULTY_AFFECTS_BABY_MOBS),
        Key.flag(SERVER_FILE, "difficulty.difficultyScaleHostileGearPastCap",
            DungeonTrainConfig.DEFAULT_DIFFICULTY_SCALE_HOSTILE_GEAR_PAST_CAP),
        Key.flag(SERVER_FILE, "difficulty.difficultyIsolatedStash",
            DungeonTrainConfig.DEFAULT_DIFFICULTY_ISOLATED_STASH),
        Key.flag(SERVER_FILE, "difficulty.villagerTradeScalingEnabled",
            DungeonTrainConfig.DEFAULT_VILLAGER_TRADE_SCALING_ENABLED),
        Key.number(SERVER_FILE, "difficulty.villagerTradeScalingMinCarriage",
            DungeonTrainConfig.DEFAULT_VILLAGER_TRADE_SCALING_MIN_CARRIAGE,
            DungeonTrainConfig.MIN_VILLAGER_TRADE_SCALING_MIN_CARRIAGE,
            DungeonTrainConfig.MAX_VILLAGER_TRADE_SCALING_MIN_CARRIAGE),
        Key.number(SERVER_FILE, "difficulty.villagerTradeScalingTiersPerStep",
            DungeonTrainConfig.DEFAULT_VILLAGER_TRADE_SCALING_TIERS_PER_STEP,
            DungeonTrainConfig.MIN_VILLAGER_TRADE_SCALING_TIERS_PER_STEP,
            DungeonTrainConfig.MAX_VILLAGER_TRADE_SCALING_TIERS_PER_STEP),
        Key.flag(SERVER_FILE, "difficulty.firstLevelNoHostiles",
            DungeonTrainConfig.DEFAULT_FIRST_LEVEL_NO_HOSTILES),
        Key.numberSince(SERVER_FILE, "difficulty.firstLevelNoHostilesCarriages",
            DungeonTrainConfig.DEFAULT_FIRST_LEVEL_NO_HOSTILES_CARRIAGES,
            DungeonTrainConfig.MIN_ONBOARDING_STAGE_CARRIAGES,
            DungeonTrainConfig.MAX_ONBOARDING_STAGE_CARRIAGES,
            DungeonTrainConfig.ONBOARDING_LENGTHS_CONFIG_VERSION),
        Key.flag(SERVER_FILE, "difficulty.firstLevelEasyMobs",
            DungeonTrainConfig.DEFAULT_FIRST_LEVEL_EASY_MOBS),
        Key.numberSince(SERVER_FILE, "difficulty.firstLevelEasyMobsCarriages",
            DungeonTrainConfig.DEFAULT_FIRST_LEVEL_EASY_MOBS_CARRIAGES,
            DungeonTrainConfig.MIN_ONBOARDING_STAGE_CARRIAGES,
            DungeonTrainConfig.MAX_ONBOARDING_STAGE_CARRIAGES,
            DungeonTrainConfig.ONBOARDING_LENGTHS_CONFIG_VERSION),
        Key.flag(SERVER_FILE, "difficulty.firstLevelStarterLoot",
            DungeonTrainConfig.DEFAULT_FIRST_LEVEL_STARTER_LOOT),

        // --- dungeontrain-common.toml ---
        Key.number(COMMON_FILE, "spawning.defaultPlayerMobSpawnOneIn",
            DungeonTrainCommonConfig.DEFAULT_PLAYER_MOB_SPAWN_ONE_IN,
            DungeonTrainCommonConfig.MIN_PLAYER_MOB_SPAWN_ONE_IN,
            DungeonTrainCommonConfig.MAX_PLAYER_MOB_SPAWN_ONE_IN),
        Key.number(COMMON_FILE, "spawning.defaultPlayerMobBehindSpawnPercent",
            DungeonTrainCommonConfig.DEFAULT_PLAYER_MOB_BEHIND_SPAWN_PERCENT,
            DungeonTrainCommonConfig.MIN_PLAYER_MOB_BEHIND_SPAWN_PERCENT,
            DungeonTrainCommonConfig.MAX_PLAYER_MOB_BEHIND_SPAWN_PERCENT),
        Key.flag(COMMON_FILE, "train.defaultBreakBlocksOnContact",
            DungeonTrainCommonConfig.DEFAULT_BREAK_BLOCKS_ON_CONTACT)
    );

    /**
     * Deviations found at the current server session's boot; empty when DT's config matches its
     * defaults (or no server is running). Immutable snapshot, replaced whole — never mutated
     * (volatile: written on the server thread, read from event handlers).
     */
    private static volatile List<String> deviations = List.of();

    private DtConfigIntegrity() {}

    /** Is the current server session Free Play because DT's own balance config was changed? */
    public static boolean isSessionFreePlay() {
        return !deviations.isEmpty();
    }

    /**
     * The deviations found at this session's boot, e.g. {@code train.speed=5.0 (expected 2.0)} —
     * shown to the player so they can see exactly WHAT was changed. Empty when clean.
     */
    public static List<String> deviations() {
        return deviations;
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        deviations = check(FMLPaths.CONFIGDIR.get());
        if (!deviations.isEmpty()) {
            LOGGER.warn("[DungeonTrain] DT config differs from defaults — this session runs in Free Play: {}",
                String.join(", ", deviations));
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        deviations = List.of();
    }

    /**
     * Read DT's two gameplay config files from {@code configDir} and report deviations from the
     * shipped defaults. Safe to call with no world loaded — that is the point; the title-screen
     * prompt uses this too.
     */
    public static List<String> check(Path configDir) {
        return deviationsOf(
            read(configDir.resolve(SERVER_FILE), SERVER_FILE),
            read(configDir.resolve(COMMON_FILE), COMMON_FILE));
    }

    /**
     * Pull the governed paths out of one toml. A missing file, an unreadable one, or any failure
     * inside night-config yields no values — which reads as "all defaults", the same conclusion
     * NeoForge reaches for a file that isn't there. Fails open by design: a config integrity check
     * must never be the thing that stops the game starting.
     */
    private static Map<String, Object> read(Path file, String fileName) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!Files.exists(file)) return values;
        try (FileConfig config = FileConfig.of(file)) {
            config.load();
            // The version stamp rides along in the server map so the pure comparison below can tell
            // "behind on a migration" from "edited", without changing its signature.
            if (SERVER_FILE.equals(fileName)) {
                Object version = config.get(CONFIG_VERSION_PATH);
                if (version != null) values.put(CONFIG_VERSION_PATH, version);
            }
            for (Key key : GOVERNED) {
                if (!key.file().equals(fileName)) continue;
                Object raw = config.get(key.path());
                if (raw != null) values.put(key.path(), raw);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Could not read {} — assuming DT defaults ({})", file, t.toString());
            return Map.of();
        }
        return values;
    }

    /**
     * Pure: compare already-read raw values against the shipped defaults, mirroring NeoForge's
     * per-entry load semantics — absent, wrong-typed and out-of-range values are what NeoForge
     * replaces with the default, so they are <em>not</em> deviations. Package-visible for tests.
     */
    static List<String> deviationsOf(Map<String, Object> serverValues, Map<String, Object> commonValues) {
        List<String> found = new ArrayList<>();
        int fileVersion = configVersionOf(serverValues);
        for (Key key : GOVERNED) {
            if (notYetMigrated(key, fileVersion)) continue;
            Map<String, Object> values = SERVER_FILE.equals(key.file()) ? serverValues : commonValues;
            Object effective = effectiveValue(key, values.get(key.path()));
            if (!effective.equals(key.expected())) {
                found.add(key.path() + "=" + display(effective) + " (expected " + display(key.expected()) + ")");
            }
        }
        return List.copyOf(found);
    }

    /**
     * Whether {@code key}'s expected value postdates the config file, i.e. the
     * {@code runPendingMigrations()} step that writes it has not run on this install yet.
     *
     * <p>The two are read at different moments and only one of them loads the spec: the migration
     * runs when the SERVER config loads (world load), while this check also answers at the title
     * screen for {@code ConfigDeviationScreen} — so on the first launch after an update the file
     * still holds DT's own previous default. That is a value the player never chose and is about to
     * be corrected automatically, so treating it as a deviation would put the entire existing player
     * base into Free Play and offer to move their configs aside. Skipping it here is the same
     * reasoning as the absent / wrong-typed / out-of-range cases in {@link #effectiveValue}: judge
     * the value the file is about to be worth, not the stale one on disk. Pure, for tests.</p>
     */
    static boolean notYetMigrated(Key key, int fileConfigVersion) {
        return key.sinceVersion() > Key.ALWAYS && fileConfigVersion < key.sinceVersion();
    }

    /**
     * The migration version the server file records, or {@link DungeonTrainConfig#DEFAULT_CONFIG_VERSION}
     * (pre-versioning) when it is absent or unreadable — the reading that skips dated keys rather than
     * judging on them, matching how the rest of this class fails open. A file with no stamp is either
     * one NeoForge has not written yet or one predating the mechanism entirely, and NeoForge writes the
     * stamp back on the very next load, so the gap closes itself. Pure, for tests.
     */
    static int configVersionOf(Map<String, Object> serverValues) {
        return serverValues.get(CONFIG_VERSION_PATH) instanceof Number n
            ? n.intValue()
            : DungeonTrainConfig.DEFAULT_CONFIG_VERSION;
    }

    /** What this entry would actually be worth once NeoForge has loaded and corrected the file. */
    private static Object effectiveValue(Key key, Object raw) {
        if (raw == null) return key.expected();
        if (key.expected() instanceof Boolean) {
            return raw instanceof Boolean b ? b : key.expected();
        }
        if (key.expected() instanceof Enum<?> expected) {
            String name = raw instanceof Enum<?> e ? e.name() : raw instanceof String s ? s.trim() : null;
            if (name == null) return key.expected();
            for (Object constant : expected.getDeclaringClass().getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(name)) return constant;
            }
            return key.expected();
        }
        if (!(raw instanceof Number number)) return key.expected();
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < key.min() || value > key.max()) return key.expected();
        return key.expected() instanceof Double ? (Object) value : (Object) (int) Math.round(value);
    }

    /** Render a value the way the config file spells it, so the player can find it. */
    private static String display(Object value) {
        return value instanceof Enum<?> e ? e.name() : String.valueOf(value);
    }
}
