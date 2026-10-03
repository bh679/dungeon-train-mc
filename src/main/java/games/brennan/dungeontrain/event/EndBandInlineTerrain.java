package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.config.EndBandConfig;
import games.brennan.dungeontrain.registry.ModDataAttachments;
import games.brennan.dungeontrain.worldgen.EndBandSampler;
import games.brennan.dungeontrain.worldgen.OfflineChunkSampler;
import games.brennan.dungeontrain.worldgen.SampledCells;
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
 * <p>Ordering against the void erosion ({@link WorldDisintegrationEvents}): the background path writes
 * its sample on a tick after the chunk loaded, i.e. after the erosion; here the erosion is run first by
 * hand, and the cells the sample then fills are recorded ({@link ModDataAttachments#END_BAND_SAMPLED_CELLS})
 * so the load-time erosion leaves them standing. Without that the islands were eroded down to their
 * end stone — the fade keeps a sampled block on exactly the noise values the erosion removes one.</p>
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
            // Same order as the background path: the void erosion first, so the sample lands in cleared air
            // rather than only in the gaps between overworld blocks the erosion is about to remove ...
            WorldDisintegrationEvents.erode(overworld, proto, SampledCells.NONE);
            // ... then the sample, recording every cell it writes so the load-time erosion pass (which runs
            // after every neighbour has decorated) skips the islands and only cleans up what spilled in.
            SampledCells cells = SampledCells.forChunk(proto.getMinBuildHeight(), proto.getHeight());
            EndBandTerrainWriter.write(overworld, proto, r,
                    EndBandTerrainWriter.recordingSink(EndBandTerrainWriter.protoSink(proto, overworld.registryAccess()), cells));
            if (!cells.isEmpty()) proto.setData(ModDataAttachments.END_BAND_SAMPLED_CELLS, cells);
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
