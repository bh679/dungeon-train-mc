package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import games.brennan.dungeontrain.worldgen.density.NetherBandContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One overworld chunk's decoration <b>outside</b> the WWOO stretch, driven from
 * {@code ChunkGeneratorDecorationMixin} and {@code BiomeFilterMixin} (see {@link VanillaBiomeFeatures}).
 *
 * <p>During vanilla's own feature loop, WWOO-only features are vetoed and the biome check only lets a
 * live feature through where the biome's vanilla list still has it. After each decoration step, the
 * vanilla features WWOO took out (or overrode) are placed with their own seeds, their biome check
 * limited to the biomes that lost them — so each position gets each vanilla feature exactly once.
 * Placing them step by step keeps vanilla's order: ores before trees, trees before the snow layer and
 * before DT's own top-layer features.</p>
 *
 * <p>One pass per decoration call, held in a {@link ThreadLocal}: {@code applyBiomeDecoration} runs to
 * completion on one worldgen thread, and the next call's {@link #begin} replaces it.</p>
 */
public final class WwooDecorationPass {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN ERRORS = new LogFirstN(5);
    private static final ThreadLocal<WwooDecorationPass> ACTIVE = new ThreadLocal<>();

    // Session counters for /dungeontrain debug overworld-laps — evidence the confinement is live.
    private static final AtomicLong WWOO_CHUNKS = new AtomicLong();
    private static final AtomicLong CONFINED_CHUNKS = new AtomicLong();
    private static final AtomicLong VETOED = new AtomicLong();
    private static final AtomicLong VANILLA_PLACED = new AtomicLong();

    private final VanillaBiomeFeatures features;
    private final List<VanillaBiomeFeatures.BiomeRule> chunkRules;
    private final WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
    private final long decorationSeed;
    private final BlockPos origin;
    private int nextStep;
    private boolean placingExtras;

    private WwooDecorationPass(VanillaBiomeFeatures features, List<VanillaBiomeFeatures.BiomeRule> chunkRules,
                               long levelSeed, BlockPos origin) {
        this.features = features;
        this.chunkRules = chunkRules;
        this.origin = origin;
        this.decorationSeed = random.setDecorationSeed(levelSeed, origin.getX(), origin.getZ());
    }

    /**
     * Start (or skip) the pass for this chunk. No pass on other dimensions, inside the WWOO stretch, or
     * when no biome around the chunk is one WWOO changed — decoration then runs exactly as before.
     */
    public static void begin(WorldGenLevel level, ChunkAccess chunk, boolean skipDecoration) {
        ACTIVE.remove();
        if (skipDecoration) return;
        VanillaBiomeFeatures features = VanillaBiomeFeatures.current();
        if (features == null || level.getLevel().dimension() != Level.OVERWORLD) return;
        try {
            ChunkPos pos = chunk.getPos();
            NetherBandContext ctx = NetherBandContext.current();
            WorldGenCycle cycle = ctx != null ? ctx.cycle() : null;
            boolean hasTrain = ctx != null && ctx.hasTrain();
            if (SecondLapOverworld.at(cycle, hasTrain, pos.getMiddleBlockX()) == SecondLapOverworld.Stretch.WWOO) {
                WWOO_CHUNKS.incrementAndGet();
                return;
            }
            List<VanillaBiomeFeatures.BiomeRule> rules = rulesAround(level, pos, features);
            if (rules.isEmpty()) return;
            CONFINED_CHUNKS.incrementAndGet();
            BlockPos origin = SectionPos.of(pos, level.getMinSection()).origin();
            ACTIVE.set(new WwooDecorationPass(features, rules, level.getSeed(), origin));
        } catch (Throwable t) {
            ERRORS.error(LOGGER, "[DungeonTrain] WWOO confinement skipped for a chunk; it decorates as WWOO", t);
        }
    }

    /** Session counters, for {@code /dungeontrain debug overworld-laps}. */
    public static String describeCounters() {
        return "wwooChunks=" + WWOO_CHUNKS.get() + " confinedChunks=" + CONFINED_CHUNKS.get()
                + " vetoed=" + VETOED.get() + " vanillaPlaced=" + VANILLA_PLACED.get();
    }

    /** True while a pass runs on this thread — the cheap gate before a biome lookup. */
    public static boolean active() {
        return ACTIVE.get() != null;
    }

    /** True when the live loop must skip this feature (WWOO-only, or overridden — the vanilla one places later). */
    public static boolean vetoes(PlacedFeature feature) {
        WwooDecorationPass pass = ACTIVE.get();
        boolean veto = pass != null && !pass.placingExtras && pass.features.vetoed(feature);
        if (veto) VETOED.incrementAndGet();
        return veto;
    }

    /**
     * The biome check for {@code feature} at a position in {@code biome} while the pass runs, or
     * {@code null} to use vanilla's own check (no pass, or a biome WWOO didn't change).
     */
    public static Boolean biomeAllows(PlacedFeature feature, Holder<Biome> biome) {
        WwooDecorationPass pass = ACTIVE.get();
        if (pass == null) return null;
        VanillaBiomeFeatures.BiomeRule rule = pass.features.rule(biome.unwrapKey().orElse(null));
        if (rule == null) return pass.placingExtras ? Boolean.FALSE : null;
        ResourceKey<PlacedFeature> key = pass.features.keyOf(feature);
        if (key == null) return pass.placingExtras ? Boolean.FALSE : null;
        return pass.placingExtras ? rule.extraAllowed().contains(key) : rule.liveAllowed().contains(key);
    }

    /** Vanilla is about to start a decoration step: place the previous step's vanilla features first. */
    public static void beforeStep(WorldGenLevel level, ChunkGenerator generator) {
        WwooDecorationPass pass = ACTIVE.get();
        if (pass == null) return;
        if (pass.nextStep > 0) pass.placeExtras(level, generator, pass.nextStep - 1);
        pass.nextStep++;
    }

    /** Vanilla finished decorating: place the last step's vanilla features and end the pass. */
    public static void finish(WorldGenLevel level, ChunkGenerator generator) {
        WwooDecorationPass pass = ACTIVE.get();
        ACTIVE.remove();
        if (pass == null || pass.nextStep == 0) return;
        pass.placeExtras(level, generator, pass.nextStep - 1);
    }

    private void placeExtras(WorldGenLevel level, ChunkGenerator generator, int step) {
        if (step >= VanillaBiomeFeatures.STEPS) return;
        Map<Integer, VanillaBiomeFeatures.Extra> ordered = new TreeMap<>();
        for (VanillaBiomeFeatures.BiomeRule rule : chunkRules) {
            for (VanillaBiomeFeatures.Extra extra : rule.extraPerStep().get(step)) ordered.putIfAbsent(extra.seedIndex(), extra);
        }
        placingExtras = true;
        try {
            for (VanillaBiomeFeatures.Extra extra : ordered.values()) {
                random.setFeatureSeed(decorationSeed, extra.seedIndex(), step);
                try {
                    level.setCurrentlyGenerating(() -> "dungeontrain vanilla " + extra.key().location());
                    if (extra.feature().placeWithBiomeCheck(level, generator, random, origin)) {
                        VANILLA_PLACED.incrementAndGet();
                    }
                } catch (Exception e) {
                    ERRORS.error(LOGGER, "[DungeonTrain] Vanilla feature " + extra.key().location()
                            + " failed outside the WWOO stretch; skipped", e);
                }
            }
        } finally {
            placingExtras = false;
            level.setCurrentlyGenerating(null);
        }
    }

    /** The rules of every WWOO-changed biome vanilla's own loop would decorate this chunk with. */
    private static List<VanillaBiomeFeatures.BiomeRule> rulesAround(WorldGenLevel level, ChunkPos centre,
                                                                    VanillaBiomeFeatures features) {
        Set<Holder<Biome>> biomes = new HashSet<>();
        ChunkPos.rangeClosed(centre, 1).forEach(p -> {
            for (LevelChunkSection section : level.getChunk(p.x, p.z).getSections()) {
                section.getBiomes().getAll(biomes::add);
            }
        });
        List<VanillaBiomeFeatures.BiomeRule> out = new ArrayList<>();
        for (Holder<Biome> biome : biomes) {
            VanillaBiomeFeatures.BiomeRule rule = features.rule(biome.unwrapKey().orElse(null));
            if (rule != null && !out.contains(rule)) out.add(rule);
        }
        return out;
    }
}
