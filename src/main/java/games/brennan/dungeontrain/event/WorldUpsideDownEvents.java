package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.MirrorPlanCache;
import games.brennan.dungeontrain.worldgen.UpsideDownBand;
import games.brennan.dungeontrain.worldgen.UpsideDownMirror;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Realises the <b>upside-down band</b> ({@link UpsideDownBand}) at chunk load by mirroring each in-band
 * column of finished overworld terrain vertically around the carriage height — the ground you'd walk on
 * becomes a ceiling overhead, hills that rose above the train hang below it. The reflection maths and
 * the full model (bedrock-roof inversion, water/fallable handling, entry-lead reveal window, exit
 * crossfade) live in {@link UpsideDownMirror}; see its javadoc.
 *
 * <p><b>Where the work runs.</b> The mirror's expensive half — the full-column snapshot and the
 * per-block reflection — is precomputed on the worldgen worker thread at the {@code SPAWN} generation
 * step ({@code ChunkStatusSpawnMixin}) and stashed in {@link MirrorPlanCache}. This handler runs on the
 * server main thread at {@link ChunkEvent.Load} (gated on {@link ChunkEvent.Load#isNewChunk()} — so it
 * fires once at generation, never on reload, so player builds survive) and only <em>applies</em> the
 * precomputed writes (through the raw, Sable-safe section-write path). When precompute is disabled or the
 * plan was evicted/failed, it falls back to computing the mirror inline here — identical terrain, just on
 * the main thread. A non-null plan means the chunk is band-relevant, which is also the gate for laying
 * the flipped train corridor.</p>
 *
 * <p><b>Corridor.</b> After the mirror, {@link TrackGenerator#layFlippedCorridor} carves the carriage
 * tube and stamps the authored bed/rails onto the mirrored terrain ({@code TrackBedFeature} skips in-band
 * chunks, so this is the primary track lay there). It needs a {@link LevelChunk} and is negligibly cheap,
 * so it stays here at load rather than moving to generation.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WorldUpsideDownEvents {

    private WorldUpsideDownEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.isNewChunk()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();

        // Apply the vertical mirror. Prefer the plan precomputed on the worldgen worker at SPAWN; when it
        // is absent (precompute off, evicted, or the worker step failed) compute it inline now — the same
        // terrain, just on this thread. A null plan means the chunk isn't in the band / entry-lead / exit.
        UpsideDownMirror.MirrorPlan plan = MirrorPlanCache.remove(pos.toLong());
        if (plan == null) plan = UpsideDownMirror.compute(level, chunk);
        if (plan == null) return;

        UpsideDownMirror.apply(chunk, plan);

        // Lay the flipped corridor into the now-mirrored terrain (the counterpart to TrackBedFeature,
        // which skips band / lead-in / exit-fade chunks). Runs after the mirror so it writes onto the
        // composited terrain and wins the corridor Z-strip.
        if (chunk instanceof LevelChunk levelChunk) {
            DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
            TrackGeometry g = TrackGeometry.from(data.dims(), data.getTrainY());
            TrackGenerator.layFlippedCorridor(level, levelChunk, data.dims(), pos.x, pos.z, g);
            levelChunk.setUnsaved(true);
        }
    }

    /**
     * Drop any pending precomputed plans when the overworld unloads, so a stale plan from a previous
     * world can never be applied to a same-positioned chunk in a newly loaded one.
     */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension().equals(Level.OVERWORLD)) {
            MirrorPlanCache.clear();
        }
    }
}
