package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.world.TrackGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.valkyrienskies.core.api.ships.ServerShip;

import java.util.List;

/**
 * Server-side hook that paints tracks into every chunk as it loads. Together
 * with {@link TrackGenerator#bootstrapForTrain} (called from
 * {@code TrainAssembler.spawnTrain} for chunks already loaded at spawn time)
 * this gives tracks out to render distance in both directions with zero
 * runtime bookkeeping.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrackGenEvents {

    private TrackGenEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!DungeonTrainConfig.isGenerateTracks()) return;

        List<ServerShip> trains = TrainAssembler.getActiveTrainShips(level);
        if (trains.isEmpty()) return;

        ChunkAccess chunk = event.getChunk();
        for (ServerShip train : trains) {
            TrackGenerator.generateForChunk(level, chunk, train);
        }
    }
}
