package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.util.LogFirstN;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.slf4j.Logger;

/**
 * The core of a Deeper Dark pass ({@code nether:otherside} in the cycle order): real chunks of Deeper Darker's
 * <b>Otherside</b> dimension, copied into the band whole — bedrock floor, bedrock roof and everything between.
 *
 * <p>The Otherside is a 128-tall roofed cave world (y 0..127; bedrock 5 deep at both ends, deepslate inside
 * them, sculk stone between, a sculk-grime sea at y 17). Its y {@link #CENTER_Y} sits on the track bed, so the
 * slab spans {@code bedY − 40 .. bedY + 87}: the roof is ~75 blocks over the tunnel and the floor ~40 under it,
 * so the track crosses the slab through its open sides and never meets bedrock.</p>
 *
 * <p>Each display chunk samples the Otherside chunk at the same chunk coordinates — noise, surface rules and
 * carvers, run on the calling worldgen worker ({@link OfflineChunkSampler#fillGroundInline}) — so neighbouring
 * chunks join up exactly as they do in the Otherside itself. Decoration is not sampled here: the band's core
 * decoration pass runs the Otherside biomes' own features over the stamped slab, like the Nether core's.</p>
 *
 * <p>Deeper Darker is a hard dependency, but every caller still treats a missing level as "no Otherside": the
 * pass then falls back to a vanilla Nether core rather than leaving a hole.</p>
 */
public final class OthersideCore {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final LogFirstN SAMPLE_ERRORS = new LogFirstN(5);

    /** Deeper Darker's mod id / registry namespace. */
    public static final String NAMESPACE = "deeperdarker";
    /** The Otherside dimension. */
    public static final ResourceKey<Level> LEVEL =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(NAMESPACE, "otherside"));

    /** Otherside y that sits on the track bed. */
    public static final int CENTER_Y = 40;
    /** Lowest Otherside y copied (its bedrock floor). */
    public static final int SAMPLE_Y_MIN = 0;
    /** Highest Otherside y copied (its bedrock roof). */
    public static final int SAMPLE_Y_MAX = 127;

    /** {@code deeperdarker:sculk_stone} — the Otherside's default block; deepslate when Deeper Darker is absent. */
    private static volatile BlockState sculkStone;

    private OthersideCore() {}

    /** Deeper Darker's sculk stone (the crossfade skin into the Deeper Dark), or deepslate without the mod. */
    public static BlockState sculkStone() {
        BlockState s = sculkStone;
        if (s == null) {
            Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath(NAMESPACE, "sculk_stone"));
            s = block == Blocks.AIR ? Blocks.DEEPSLATE.defaultBlockState() : block.defaultBlockState();
            sculkStone = s;
        }
        return s;
    }

    /** World y of Otherside y {@code othersideY} on a track bed at {@code bedY}. */
    public static int displayY(int othersideY, int bedY) {
        return bedY + (othersideY - CENTER_Y);
    }

    /** Otherside y of world y {@code worldY} on a track bed at {@code bedY}. */
    public static int othersideY(int worldY, int bedY) {
        return worldY - bedY + CENTER_Y;
    }

    /** Lowest world y of the stamped slab (its bedrock floor) on a bed at {@code bedY}. */
    public static int slabBottom(int bedY) {
        return displayY(SAMPLE_Y_MIN, bedY);
    }

    /** Highest world y of the stamped slab (its bedrock roof) on a bed at {@code bedY}. */
    public static int slabTop(int bedY) {
        return displayY(SAMPLE_Y_MAX, bedY);
    }

    /** The Otherside level, or {@code null} when this server has none or it doesn't run a noise generator. */
    public static ServerLevel level(MinecraftServer server) {
        if (server == null) return null;
        ServerLevel level = server.getLevel(LEVEL);
        if (level == null) return null;
        return level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator ? level : null;
    }

    /** True if this server's Otherside can be sampled. */
    public static boolean available(MinecraftServer server) {
        return level(server) != null;
    }

    /**
     * The Otherside ground behind display chunk {@code pos} — noise, surface rules and carvers, no decoration —
     * generated now on this thread, or {@code null} when the Otherside can't be sampled. Never throws.
     */
    public static Sample sample(MinecraftServer server, ChunkPos pos) {
        ServerLevel otherside = level(server);
        if (otherside == null) return null;
        long t0 = GenProfiler.t0();
        try {
            ChunkGenerator generator = otherside.getChunkSource().getGenerator();
            if (!(generator instanceof NoiseBasedChunkGenerator noise)) return null;
            RandomState random = otherside.getChunkSource().randomState();
            BiomeSource biomes = noise.getBiomeSource();
            ProtoChunk chunk = OfflineChunkSampler.blankSample(otherside, noise, random, pos, biomes);
            OfflineChunkSampler.Workspace workspace = OfflineChunkSampler.workspaceFor(otherside, noise, random, chunk, biomes);
            ProtoChunk ground = OfflineChunkSampler.fillGroundInline(otherside, noise, random, chunk, workspace);
            if (ground == null) return null;
            try {
                OfflineChunkSampler.carve(noise, random, ground, workspace, otherside.getSeed());
            } catch (Throwable t) {
                LOGGER.debug("[DungeonTrain] Otherside carvers failed at {}; keeping uncarved terrain", pos, t);
            }
            return new Sample(ground, otherside.getMinBuildHeight(), otherside.getMaxBuildHeight());
        } catch (Throwable t) {
            SAMPLE_ERRORS.error(LOGGER, "[DungeonTrain] Otherside sample failed at " + pos + "; that chunk keeps a Nether core", t);
            return null;
        } finally {
            GenProfiler.add(GenProfiler.Bucket.OTHERSIDE_SAMPLE, t0);
        }
    }

    /** One sampled Otherside chunk, read by chunk-local column and Otherside y. */
    public static final class Sample {
        private final ProtoChunk chunk;
        private final int minY;
        private final int maxY;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        Sample(ProtoChunk chunk, int minY, int maxBuildHeight) {
            this.chunk = chunk;
            this.minY = minY;
            this.maxY = maxBuildHeight - 1;
        }

        /** The block at chunk-local {@code (dx, dz)} and Otherside y {@code y}, or {@code null} outside the level. */
        public BlockState stateAt(int dx, int y, int dz) {
            if (y < minY || y > maxY) return null;
            return chunk.getBlockState(cursor.set(chunk.getPos().getMinBlockX() + dx, y, chunk.getPos().getMinBlockZ() + dz));
        }
    }
}
