package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.train.TrainAssembler;
import games.brennan.dungeontrain.world.TrackGenerator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.ServerShip;

import java.util.List;

/**
 * Server-side hooks that paint tracks into the world beneath every Dungeon
 * Train. Two triggers:
 *
 *  - {@link ChunkEvent.Load} — fires for chunks that load after the train
 *    exists; generation is cheap per chunk (flag-18 setBlock, no neighbor
 *    cascades) so we do it synchronously.
 *
 *  - {@link TickEvent.LevelTickEvent} — drains the deferred bootstrap queue
 *    populated by {@link TrackGenerator#bootstrapForTrain}, which covers
 *    chunks that were already loaded at spawn time. Spread across ticks so
 *    login is never blocked.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrackGenEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TrackGenEvents() {}

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!DungeonTrainConfig.isGenerateTracks()) return;

        List<ServerShip> trains = TrainAssembler.getActiveTrainShips(level);
        if (trains.isEmpty()) return;

        ChunkAccess chunk = event.getChunk();
        LOGGER.debug("[DungeonTrain] ChunkEvent.Load painting tracks: chunk={} newChunk={} trains={}",
                chunk.getPos(), event.isNewChunk(), trains.size());
        for (ServerShip train : trains) {
            TrackGenerator.generateForChunk(level, chunk, train);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.level instanceof ServerLevel level)) return;
        if (!DungeonTrainConfig.isGenerateTracks()) return;

        List<ServerShip> trains = TrainAssembler.getActiveTrainShips(level);
        TrackGenerator.processPendingBootstrap(level, trains);
    }
}
