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
 * Picks the real-End biome for a Dungeon-Train End-<b>core</b> column the exact way the End itself
 * does: by sampling the live End dimension's {@link BiomeSource} ({@code TheEndBiomeSource}) with the
 * End's own {@link Climate.Sampler}. Unlike the Nether ({@link NetherCoreBiomes}, climate-based), End
 * biome selection is radius-from-the-End's-origin based, so successive core passes are swept outward
 * from the real End's origin instead of sampled at a single fixed offset: pass 0 lands on the main
 * island (always {@code the_end} — vanilla resolves that biome by a fixed radius check, not noise),
 * and every later pass advances {@link #OUTER_PASS_STEP} blocks further into the outer noise field
 * (the same field {@code DisintegrationFeature} samples for chorus placement), which mixes
 * {@code end_highlands}/{@code end_midlands}/{@code end_barrens}/{@code small_end_islands} — so a
 * normal game session's handful of End-band crossings realistically covers all five End biomes.
 *
 * <p>Resolved once at server start and stored in {@link NetherBandContext}; the biome-source mixin
 * calls {@link #biomeAt} so the world label, the surface skin, and the decoration always agree.</p>
 *
 * <p>A missing End dimension or any sampling error falls back to the {@code the_end} holder — biome
 * generation is never broken.</p>
 */
public final class EndCoreBiomes {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** X offset into the outer End noise field — matches {@code DisintegrationFeature.ISLAND_SAMPLE_OFFSET_X}. */
    private static final int OUTER_SAMPLE_OFFSET_X = 16_000;
    /** Blocks each successive pass advances outward into the outer noise field. */
    private static final int OUTER_PASS_STEP = 4_000;
    /** End-space Y sampled — End biome selection doesn't vary with depth. */
    private static final int SAMPLE_BLOCK_Y = 64;
    private static final int SAMPLE_QUART_Y = QuartPos.fromBlock(SAMPLE_BLOCK_Y);

    private final BiomeSource endBiomeSource; // nullable — fallback-only when the End is absent
    private final Climate.Sampler endSampler; // nullable alongside the source
    private final Holder<Biome> fallback;     // minecraft:the_end

    private EndCoreBiomes(BiomeSource endBiomeSource, Climate.Sampler endSampler, Holder<Biome> fallback) {
        this.endBiomeSource = endBiomeSource;
        this.endSampler = endSampler;
        this.fallback = fallback;
    }

    /**
     * The End biome for a core column at this world XZ. {@code passIndex} (which repeat of the
     * world-gen cycle this End-band occurrence is — see
     * {@link games.brennan.dungeontrain.worldgen.WorldGenCycle#endPassIndex}) selects where in the
     * real End we sample: pass 0 (or earlier/unknown, {@code <= 0}) always resolves to the real End's
     * main island; later passes sweep further into the outer noise field.
     */
    public Holder<Biome> biomeAt(int worldX, int worldZ, long passIndex) {
        if (endBiomeSource == null || endSampler == null) return fallback;
        try {
            if (passIndex <= 0L) {
                // The real End's main-island check is a fixed radius around its origin — sampling
                // the origin itself always resolves to `the_end`, matching a player's first arrival.
                return endBiomeSource.getNoiseBiome(0, SAMPLE_QUART_Y, 0, endSampler);
            }
            int sampleX = worldX + OUTER_SAMPLE_OFFSET_X + (int) Math.min(Integer.MAX_VALUE, passIndex * (long) OUTER_PASS_STEP);
            return endBiomeSource.getNoiseBiome(
                    QuartPos.fromBlock(sampleX), SAMPLE_QUART_Y, QuartPos.fromBlock(worldZ), endSampler);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** The {@code the_end} fallback holder (used when the End dimension is unavailable). */
    public Holder<Biome> fallback() {
        return fallback;
    }

    /**
     * Capture the real End's biome source + climate sampler at server start. Returns a fallback-only
     * instance (every {@link #biomeAt} yields {@code the_end}) if the world has no End dimension.
     */
    public static EndCoreBiomes resolve(MinecraftServer server, Holder<Biome> fallback) {
        try {
            ServerLevel end = server.getLevel(Level.END);
            if (end == null) {
                LOGGER.info("[DungeonTrain] No End dimension — End core stays single-biome (the_end)");
                return new EndCoreBiomes(null, null, fallback);
            }
            ChunkGenerator gen = end.getChunkSource().getGenerator();
            BiomeSource src = gen.getBiomeSource();
            Climate.Sampler sampler = end.getChunkSource().randomState().sampler();
            return new EndCoreBiomes(src, sampler, fallback);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Failed to capture End biome source; core stays single-biome", t);
            return new EndCoreBiomes(null, null, fallback);
        }
    }
}
