package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.track.TrackGenerator;
import games.brennan.dungeontrain.track.TrackGeometry;
import games.brennan.dungeontrain.train.TrainTransformProvider;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

/**
 * Auto-generates tracks into newly-loaded server chunks that intersect an
 * active train's Z corridor. Paired with the per-tick periodic fill in
 * {@link TrainTickEvents} which handles chunks that were already loaded at
 * spawn (and so never fire Load after this listener registers).
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrackChunkEvents {

    private TrackChunkEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!DungeonTrainConfig.getGenerateTracks()) return;

        ChunkAccess chunk = event.getChunk();
        ChunkPos pos = chunk.getPos();
        int cx = pos.x;
        int cz = pos.z;
        if (TrackGenerator.isShipyardChunk(cx, cz)) return;

        int chunkMinZ = cz << 4;
        int chunkMaxZ = chunkMinZ + 15;

        for (LoadedServerShip ship : VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips()) {
            if (!(ship.getTransformProvider() instanceof TrainTransformProvider provider)) continue;
            TrackGeometry g = provider.getTrackGeometry();
            if (g == null) continue;
            // Fast Z-corridor prefilter before the generator loop.
            if (chunkMaxZ < g.trackZMin() || chunkMinZ > g.trackZMax()) continue;
            TrackGenerator.ensureTracksForChunk(level, cx, cz, g, provider.getFilledChunks());
        }
    }
}
