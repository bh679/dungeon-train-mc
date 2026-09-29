package games.brennan.dungeontrain.worldgen;

import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import net.minecraft.world.level.levelgen.GenerationStep;

import java.util.EnumSet;

/**
 * Detects world generation driven by <b>Distant Horizons'</b> LOD generator and decides whether DT's
 * worldgen may take its cheaper "LOD-lite" paths there.
 *
 * <p>DH (mode {@code FEATURES}, its default) runs the vanilla chunk pipeline up to FEATURES for every
 * LOD chunk on its own {@code DH-World Gen Thread[N]} workers — so it also runs DT's Nether-core
 * decoration ({@code NetherTransitionFeature#decorateCoreChunkWithNetherFeatures}) at up to ~24
 * chunks/s, which lag reports showed starving the server + Sable physics threads on 4-core machines.
 * Those chunks are never saved and only ever seen from beyond the vanilla render distance, so the
 * sub-block detail (ores, ceiling glowstone, fire dots, small mushrooms, springs) is below LOD
 * resolution; the silhouette features ({@link #LOD_VISIBLE_STEPS}) are what an LOD shows.</p>
 *
 * <p><b>Detection is by thread name.</b> DH API 5.0.0 exposes no "this generation is DH-driven"
 * signal; its {@code DhThreadFactory} names pool workers {@code "DH-" + pool + " Thread[" + n + "]"}
 * and the generator pool is {@code "World Gen"}. The check is cached per thread. A DH release that
 * renames the pool fails safe — every chunk gets the full decoration again.</p>
 *
 * <p>{@link #MODE} is the Gate 2 A/B seam ({@code /dungeontrain debug lod-lite auto|force|off}):
 * {@code FORCE_ON} treats every worldgen worker as an LOD thread so the saving can be measured on a
 * headless server with no DH installed. Same pattern as {@link BandEarlyOuts}.</p>
 */
public final class LodGeneration {

    /** Prefix of DH's LOD generator worker threads ({@code DH-World Gen Thread[N]}). */
    public static final String DH_WORLD_GEN_THREAD_PREFIX = "DH-World Gen";

    /**
     * Decoration steps an LOD can actually show: basalt pillars ({@code LOCAL_MODIFICATIONS}),
     * deltas + basalt columns ({@code SURFACE_STRUCTURES}), huge fungi / nether-forest vegetation /
     * vines ({@code VEGETAL_DECORATION}) and the top-layer pass. Everything else in a Nether biome's
     * feature list is ores, glowstone, fire, mushrooms and springs — sub-block scale.
     */
    public static final EnumSet<GenerationStep.Decoration> LOD_VISIBLE_STEPS = EnumSet.of(
            GenerationStep.Decoration.LOCAL_MODIFICATIONS,
            GenerationStep.Decoration.SURFACE_STRUCTURES,
            GenerationStep.Decoration.VEGETAL_DECORATION,
            GenerationStep.Decoration.TOP_LAYER_MODIFICATION);

    /** Debug override for the thread detection. */
    public enum Mode {
        /** Lite only on DH generator threads (shipping behaviour). */
        AUTO,
        /** Lite on every thread — measures the saving without DH. */
        FORCE_ON,
        /** Never lite, even on DH threads — the pre-change baseline. */
        FORCE_OFF
    }

    /** Live A/B seam; {@code volatile} so worker threads observe a flip immediately. */
    public static volatile Mode MODE = Mode.AUTO;

    private static final ThreadLocal<Boolean> LOD_THREAD =
            ThreadLocal.withInitial(() -> isLodThreadName(Thread.currentThread().getName()));

    private LodGeneration() {}

    /** Whether a thread with this name is one of DH's LOD generator workers. */
    public static boolean isLodThreadName(String threadName) {
        return threadName != null && threadName.startsWith(DH_WORLD_GEN_THREAD_PREFIX);
    }

    /** Whether the current thread is a DH LOD generator worker (cached per thread). */
    public static boolean isLodThread() {
        return LOD_THREAD.get();
    }

    /**
     * Whether the Nether-core decoration should take the LOD-lite path on the current thread:
     * the config allows it ({@code distantLodLiteDecoration}) and either a DH generator thread is
     * running us or the debug seam forces it.
     */
    public static boolean liteDecoration() {
        return DungeonTrainCommonConfig.isDistantLodLiteDecoration() && liteRequested(MODE, isLodThread());
    }

    /** The mode/thread half of {@link #liteDecoration()}, separated so it is unit-testable. */
    static boolean liteRequested(Mode mode, boolean lodThread) {
        return switch (mode) {
            case FORCE_ON -> true;
            case FORCE_OFF -> false;
            case AUTO -> lodThread;
        };
    }

    /**
     * Whether decoration step {@code stepIndex} (the index into
     * {@code BiomeGenerationSettings#features()}, i.e. {@link GenerationStep.Decoration#ordinal()})
     * is one an LOD can show. Out-of-range indices (a mod adding steps) are kept — never drop blind.
     */
    public static boolean isLodVisibleStep(int stepIndex) {
        GenerationStep.Decoration[] steps = GenerationStep.Decoration.values();
        if (stepIndex < 0 || stepIndex >= steps.length) return true;
        return LOD_VISIBLE_STEPS.contains(steps[stepIndex]);
    }
}
