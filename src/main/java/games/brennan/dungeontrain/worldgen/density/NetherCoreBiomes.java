package games.brennan.dungeontrain.worldgen.density;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.slf4j.Logger;

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
 * <p>The X is shifted by {@link #SAMPLE_OFFSET_X} (matching the terrain sampler in
 * {@code NetherTransitionFeature}) so successive bands sample different — but continuous — Nether
 * climate. Nether biome selection is effectively 2-D (depth/weirdness are constant across the five
 * Nether biomes), so a single fixed Nether-space Y is sampled. A missing Nether dimension or any
 * sampling error falls back to the {@code nether_wastes} holder — biome generation is never broken.</p>
 */
public final class NetherCoreBiomes {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** X offset into the Nether climate field — MUST match {@code NetherTransitionFeature}'s terrain offset. */
    public static final int SAMPLE_OFFSET_X = 12_000;
    /** Fixed Nether-space Y (block) the climate is sampled at — depth doesn't vary Nether biomes. */
    private static final int SAMPLE_BLOCK_Y = 40;
    private static final int SAMPLE_QUART_Y = QuartPos.fromBlock(SAMPLE_BLOCK_Y);

    private final BiomeSource netherBiomeSource; // nullable — fallback-only when the Nether is absent
    private final Climate.Sampler netherSampler; // nullable alongside the source
    private final Holder<Biome> fallback;        // minecraft:nether_wastes

    private NetherCoreBiomes(BiomeSource netherBiomeSource, Climate.Sampler netherSampler, Holder<Biome> fallback) {
        this.netherBiomeSource = netherBiomeSource;
        this.netherSampler = netherSampler;
        this.fallback = fallback;
    }

    /** The Nether biome for a core column at this world XZ (the same biome the world is labelled with). */
    public Holder<Biome> biomeAt(int worldX, int worldZ) {
        if (netherBiomeSource == null || netherSampler == null) return fallback;
        try {
            return netherBiomeSource.getNoiseBiome(
                    QuartPos.fromBlock(worldX + SAMPLE_OFFSET_X), SAMPLE_QUART_Y, QuartPos.fromBlock(worldZ),
                    netherSampler);
        } catch (Throwable t) {
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
        try {
            ServerLevel nether = server.getLevel(Level.NETHER);
            if (nether == null) {
                LOGGER.info("[DungeonTrain] No Nether dimension — Nether core stays single-biome (nether_wastes)");
                return new NetherCoreBiomes(null, null, fallback);
            }
            ChunkGenerator gen = nether.getChunkSource().getGenerator();
            BiomeSource src = gen.getBiomeSource();
            Climate.Sampler sampler = nether.getChunkSource().randomState().sampler();
            return new NetherCoreBiomes(src, sampler, fallback);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Failed to capture Nether biome source; core stays single-biome", t);
            return new NetherCoreBiomes(null, null, fallback);
        }
    }
}
