package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.EndBandConfig;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.worldgen.EndBandSampler;
import games.brennan.dungeontrain.worldgen.OfflineChunkSampler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.slf4j.Logger;

/**
 * Generates a sampled End-band chunk's terrain <b>inside its own worldgen</b> ({@code endBandTerrain =
 * worldgen}, the default): called at the tail of the display chunk's biome decoration, after the track bed
 * and every other feature, it runs the End sample on the calling worldgen worker
 * ({@link EndBandSampler#sampleNow}) and writes it through the same gate the background path uses
 * ({@link EndBandTerrainWriter}). The chunk therefore never exists without its islands — nothing is sent
 * to a client bare, so there are no void squares to see, however fast the player arrives.
 *
 * <p>Costs the sample (~50–150 ms) on vanilla's worldgen pool, which already runs one thread per core
 * minus one; no thread is added. On any failure — the End not available yet at server start, a sampler
 * error — the chunk is flagged {@link ModDataAttachments#END_BAND_PENDING} instead and the background path
 * ({@link WorldEndBandEvents}) writes it later, as before.</p>
 */
public final class EndBandInlineTerrain {

    private static final Logger LOGGER = LogUtils.getLogger();

    private EndBandInlineTerrain() {}

    /** Worldgen worker, at the tail of {@code applyBiomeDecoration} for {@code chunk}. */
    public static void write(WorldGenLevel level, ChunkAccess chunk) {
        if (!EndBandConfig.terrainInWorldgen()) return;
        if (OfflineChunkSampler.isSampling()) return;          // this is a sample's own decoration, not a display chunk
        if (!(chunk instanceof ProtoChunk proto)) return;
        ServerLevel overworld = level.getLevel();
        if (overworld == null || !overworld.dimension().equals(Level.OVERWORLD)) return;
        ChunkPos pos = proto.getPos();
        try {
            long pass = WorldEndBandEvents.betterEndPass(overworld, pos);
            if (pass < 0L) return;
            if (proto.getData(ModDataAttachments.END_BAND_PENDING)) return;   // already owed to the background path
            MinecraftServer server = overworld.getServer();
            EndBandSampler.Result r = EndBandSampler.sampleNow(server, pos, pass,
                    SphereCarveGeometry.of(overworld).bedY(), overworld.getMinBuildHeight(), overworld.getMaxBuildHeight());
            if (r == null) {
                owe(proto);
                return;
            }
            EndBandTerrainWriter.write(overworld, proto, r, EndBandTerrainWriter.protoSink(proto, overworld.registryAccess()));
            if (!r.spill().isEmpty()) WorldEndBandEvents.offerSpill(r.spill());
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] End-band terrain failed in worldgen at {}; leaving it to the background sampler", pos, t);
            owe(proto);
        }
    }

    /** Flag the chunk for the background path; the flag rides the proto chunk's attachments into the live chunk. */
    private static void owe(ChunkAccess chunk) {
        chunk.setData(ModDataAttachments.END_BAND_PENDING, Boolean.TRUE);
        chunk.setUnsaved(true);
    }
}
