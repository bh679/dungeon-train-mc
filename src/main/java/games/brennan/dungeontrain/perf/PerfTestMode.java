package games.brennan.dungeontrain.perf;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.presets.WorldPreset;

/**
 * Reproducible-measurement mode for server-tick A/B benchmarking.
 *
 * <h2>Why this exists</h2>
 * DT's per-tick cost on a normal world is dominated by <b>chunk generation</b>, not by the train
 * work a perf change usually targets. In practice that made A/B comparisons unusable: a single
 * measured window swung 13→73 ms between samples, and one contents-despawn comparison produced a
 * +29 % result that inverted to −20 % purely by swapping which arm ran first. The signal was real
 * but far smaller than the noise around it.
 *
 * <p>Perf-test mode removes the two biggest sources of that noise:</p>
 * <ul>
 *   <li><b>Flat terrain</b> — the {@code dungeontrain:dungeon_train_flat} world preset generates a
 *       fixed layer stack with no features, lakes or structures, so almost no per-chunk generation
 *       work happens as the train drives forward.</li>
 *   <li><b>A pinned seed</b> — every run lays out the same world and the same train, removing
 *       spawn-jitter variance between arms.</li>
 * </ul>
 * <p>and quiets the remaining per-tick background work via {@link #QUIET_GAME_RULES}.</p>
 *
 * <h2>One seed pins both world and train</h2>
 * There is deliberately no separate "train seed" knob.
 * {@code DungeonTrainWorldData.deriveGenerationSeed(overworld.getSeed())} derives the train's
 * {@code generationSeed} from the world seed, so pinning the world seed pins the train layout too.
 * Adding a second seed here would be redundant and could drift out of sync with the first.
 *
 * <h2>How to turn it on</h2>
 * A JVM system property, mirroring {@code GenDeterminismLog}'s seam:
 * <pre>
 *   ./gradlew runClient -PperfTest              # flat + pinned seed quick-world
 *   ./gradlew runServer -PperfTest              # quiet game rules; preset/seed come from
 *                                               # server.properties (level-type + level-seed)
 *   ./gradlew runClient -PperfTest -PperfSeed=123
 * </pre>
 * A property rather than a config option because the preset and seed must be chosen at world
 * <i>creation</i> — by the time a command or a config reload could run, the world already exists.
 *
 * <h2>What a flat world cannot measure</h2>
 * {@code minecraft:flat} bypasses DT's noise settings and biome-source mixin, so band content
 * (disintegration, the Nether transition) does not generate. That is exactly what makes it a clean
 * baseline for train / AI / physics work — but it means a flat-world benchmark can never measure
 * DT's own <i>worldgen</i> cost. Use a normal preset for that.
 */
public final class PerfTestMode {

    /** True when {@code -Ddungeontrain.perfTest=true} was passed to the JVM. */
    public static final boolean ENABLED = Boolean.getBoolean("dungeontrain.perfTest");

    private static final String SEED_PROPERTY = "dungeontrain.perfSeed";

    /**
     * Default pinned seed. Arbitrary but fixed — the value matters far less than it never changing,
     * since a changed default silently invalidates comparisons against earlier recorded runs.
     */
    public static final long DEFAULT_SEED = 8675309031337L;

    /** The flat preset selected by the dev quick-world when this mode is on. */
    public static final ResourceKey<WorldPreset> FLAT_PRESET = ResourceKey.create(
        net.minecraft.core.registries.Registries.WORLD_PRESET,
        ResourceLocation.fromNamespaceAndPath("dungeontrain", "dungeon_train_flat"));

    private PerfTestMode() {}

    /**
     * The seed to create a perf-test world with — {@link #DEFAULT_SEED} unless
     * {@code -Ddungeontrain.perfSeed=<long>} overrides it.
     *
     * <p>An unparseable override falls back to the default rather than throwing: a typo in a
     * benchmark command should not stop the game launching, and the resolved seed is visible in the
     * world's own settings if anyone needs to check which one was used.</p>
     */
    public static long seed() {
        String raw = System.getProperty(SEED_PROPERTY);
        return parseSeed(raw, DEFAULT_SEED);
    }

    /**
     * Pure core of {@link #seed()}, split out so it is unit-testable without touching system
     * properties (the unit-test source set runs with no Minecraft bootstrap).
     */
    static long parseSeed(String raw, long fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Per-tick background work switched off in perf-test mode, as {@code (rule, value)} pairs
     * applied by {@link PerfTestModeEvents}.
     *
     * <p>Each of these is a source of tick-to-tick variance that has nothing to do with the train:
     * daylight and weather drive lighting and precipitation updates, mob spawning adds an unbounded
     * and seed-sensitive entity population on top of the carriage contents being measured, fire tick
     * and random ticks scale with loaded chunk count. Removing them is what makes two runs of the
     * same protocol comparable.</p>
     *
     * <p>Kept as data rather than a block of imperative setters so the set is visible in one place
     * and can be asserted in a test.</p>
     */
    public static final String[][] QUIET_GAME_RULES = {
        {"doDaylightCycle", "false"},
        {"doWeatherCycle", "false"},
        {"doMobSpawning", "false"},
        {"doFireTick", "false"},
        {"randomTickSpeed", "0"},
    };
}
