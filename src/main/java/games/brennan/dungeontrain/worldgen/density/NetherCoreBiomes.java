package games.brennan.dungeontrain.worldgen.density;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.slf4j.Logger;

import java.util.Comparator;
import java.util.List;

/**
 * Picks the real-Nether biome for a Dungeon-Train Nether-<b>core</b> column the exact way the Nether
 * itself does: by sampling the live Nether dimension's {@link BiomeSource}
 * ({@code MultiNoiseBiomeSource}) with the Nether's own {@link Climate.Sampler}. So the core cycles
 * through all five Nether biomes (nether_wastes / crimson_forest / warped_forest / soul_sand_valley /
 * basalt_deltas) using vanilla's climate parameters — no hand-rolled palette.
 *
 * <p>Resolved once at server start (registry/dimension access off the hot path) and stored in
 * {@link NetherBandContext}; the biome-source mixin and {@code NetherTransitionFeature} both call
 * {@link #biomeAt} so the world label, the surface skin, and the decoration always agree.</p>
 *
 * <p><b>Alternate bands use BetterNether.</b> Odd Nether passes (the 2nd, 4th… band) pick from
 * BetterNether's biomes through {@link BetterNetherCoreBiomes}; even passes stay vanilla. The vanilla
 * passes sample a private copy of the <i>vanilla</i> Nether preset source rather than the live Nether
 * dimension's source, because BetterNether's WorldWeaver injects its biomes into the live one, which
 * would otherwise leak BetterNether into every band.</p>
 *
 * <p>The X is shifted by {@link #SAMPLE_OFFSET_X} (matching the terrain sampler in
 * {@code NetherTransitionFeature}) so successive bands sample different — but continuous — Nether
 * climate. Nether biome selection is effectively 2-D (depth/weirdness are constant across the five
 * Nether biomes), so a single fixed Nether-space Y is sampled. A missing Nether dimension or any
 * sampling error falls back to the {@code nether_wastes} holder — biome generation is never broken.</p>
 */
public final class NetherCoreBiomes {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SAMPLE_ERRORS = new LogFirstN(5);

    /** X offset into the Nether climate field — MUST match {@code NetherTransitionFeature}'s terrain offset. */
    public static final int SAMPLE_OFFSET_X = 12_000;
    /** Fixed Nether-space Y (block) the climate is sampled at — depth doesn't vary Nether biomes. */
    private static final int SAMPLE_BLOCK_Y = 40;
    private static final int SAMPLE_QUART_Y = QuartPos.fromBlock(SAMPLE_BLOCK_Y);

    private final BiomeSource netherBiomeSource; // nullable — fallback-only when the Nether is absent
    private final Climate.Sampler netherSampler; // nullable alongside the source
    private final Holder<Biome> fallback;        // minecraft:nether_wastes
    private final BetterNetherCoreBiomes<Holder<Biome>> betterNether; // nullable — no BetterNether biomes

    private NetherCoreBiomes(BiomeSource netherBiomeSource, Climate.Sampler netherSampler, Holder<Biome> fallback,
                             BetterNetherCoreBiomes<Holder<Biome>> betterNether) {
        this.netherBiomeSource = netherBiomeSource;
        this.netherSampler = netherSampler;
        this.fallback = fallback;
        this.betterNether = betterNether;
    }

    /**
     * The Nether biome for a core column at this world XZ (the same biome the world is labelled with).
     * {@code betterPass} — the cycle's verdict for this column's pass
     * ({@link games.brennan.dungeontrain.worldgen.WorldGenCycle#isBetterNetherAt}) — picks BetterNether
     * biomes; otherwise vanilla is sampled. If no BetterNether biomes are registered, every pass is vanilla.
     */
    public Holder<Biome> biomeAt(int worldX, int worldZ, boolean betterPass) {
        if (betterNether != null && betterPass) {
            return betterNether.biomeAt(worldX, worldZ);
        }
        return vanillaBiomeAt(worldX, worldZ);
    }

    /** True when BetterNether biomes were found at server start (the better passes will use them). */
    public boolean hasBetterNether() {
        return betterNether != null;
    }

    private Holder<Biome> vanillaBiomeAt(int worldX, int worldZ) {
        if (netherBiomeSource == null || netherSampler == null) return fallback;
        try {
            return netherBiomeSource.getNoiseBiome(
                    QuartPos.fromBlock(worldX + SAMPLE_OFFSET_X), SAMPLE_QUART_Y, QuartPos.fromBlock(worldZ),
                    netherSampler);
        } catch (Throwable t) {
            SAMPLE_ERRORS.error(LOGGER,
                    "[DungeonTrain] Nether core biome sample failed; baking nether_wastes fallback instead", t);
            return fallback;
        }
    }

    /** The {@code nether_wastes} fallback holder (used when the Nether dimension is unavailable). */
    public Holder<Biome> fallback() {
        return fallback;
    }

    /**
     * Capture the real Nether's biome source + climate sampler at server start. Returns a fallback-only
     * instance (every {@link #biomeAt} yields {@code nether_wastes}) if the world has no Nether dimension.
     */
    public static NetherCoreBiomes resolve(MinecraftServer server, Holder<Biome> fallback) {
        BetterNetherCoreBiomes<Holder<Biome>> betterNether = resolveBetterNether(server);
        try {
            ServerLevel nether = server.getLevel(Level.NETHER);
            if (nether == null) {
                // debug: legitimately fires during the overworld's own Load (Nether not yet
                // created) before the Nether-Load republish upgrades the snapshot.
                LOGGER.debug("[DungeonTrain] No Nether dimension — vanilla Nether core stays single-biome (nether_wastes)");
                return new NetherCoreBiomes(null, null, fallback, betterNether);
            }
            Climate.Sampler sampler = nether.getChunkSource().randomState().sampler();
            return new NetherCoreBiomes(vanillaNetherSource(server, nether), sampler, fallback, betterNether);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Failed to capture Nether biome source; vanilla core stays single-biome", t);
            return new NetherCoreBiomes(null, null, fallback, betterNether);
        }
    }

    /**
     * A private source built from vanilla's own key-based Nether preset table, kept to
     * {@code minecraft:} biomes, so even passes stay the five vanilla biomes. WorldWeaver injects
     * BetterNether into both the live Nether source <i>and</i> the registered Nether parameter-list
     * preset, so neither can be sampled directly. Falls back to the live Nether source if the table
     * can't be resolved.
     */
    public static BiomeSource vanillaNetherSource(MinecraftServer server, ServerLevel nether) {
        try {
            HolderGetter<Biome> biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
            List<Pair<Climate.ParameterPoint, Holder<Biome>>> points = MultiNoiseBiomeSourceParameterList
                    .knownPresets().get(MultiNoiseBiomeSourceParameterList.Preset.NETHER).values().stream()
                    .filter(e -> ResourceLocation.DEFAULT_NAMESPACE.equals(e.getSecond().location().getNamespace()))
                    .<Pair<Climate.ParameterPoint, Holder<Biome>>>map(e -> Pair.of(e.getFirst(), biomes.getOrThrow(e.getSecond())))
                    .toList();
            if (points.isEmpty()) throw new IllegalStateException("vanilla Nether preset has no minecraft: biomes");
            return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(points));
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Vanilla Nether biome preset unavailable; sampling the live Nether source", t);
            ChunkGenerator gen = nether.getChunkSource().getGenerator();
            return gen.getBiomeSource();
        }
    }

    /**
     * Every registered, standalone BetterNether Nether biome, sorted by id for a stable seeded order.
     * Returns {@code null} (odd passes fall back to vanilla) when BetterNether contributes none.
     */
    private static BetterNetherCoreBiomes<Holder<Biome>> resolveBetterNether(MinecraftServer server) {
        try {
            List<Holder<Biome>> biomes = server.registryAccess().registryOrThrow(Registries.BIOME).holders()
                    .filter(h -> h.is(BiomeTags.IS_NETHER))
                    .filter(h -> isStandaloneBetterNether(h.key()))
                    .sorted(Comparator.comparing(h -> h.key().location().toString()))
                    .<Holder<Biome>>map(h -> h)
                    .toList();
            long seed = server.getWorldData().worldGenOptions().seed();
            BetterNetherCoreBiomes<Holder<Biome>> picker = BetterNetherCoreBiomes.of(biomes, seed);
            if (picker == null) {
                LOGGER.warn("[DungeonTrain] No BetterNether biomes registered — alternate Nether bands stay vanilla");
            } else {
                LOGGER.debug("[DungeonTrain] Alternate Nether bands cycle {} BetterNether biomes", picker.size());
            }
            return picker;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Failed to collect BetterNether biomes; alternate Nether bands stay vanilla", t);
            return null;
        }
    }

    private static boolean isStandaloneBetterNether(ResourceKey<Biome> key) {
        return BetterNetherCoreBiomes.NAMESPACE.equals(key.location().getNamespace())
                && BetterNetherCoreBiomes.isStandaloneBiomePath(key.location().getPath());
    }
}
