package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.valkyrienskies.core.api.ships.LoadedServerShip;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Pending-attach queue for train force inducers.
 *
 * {@link org.valkyrienskies.mod.common.assembly.ShipAssembler#assembleToShip}
 * returns a {@code ShipData} (cold-storage form) — the live
 * {@link LoadedServerShip} becomes available only after VS finishes
 * loading the new ship's chunks (typically within 1 server tick). This
 * class queues the inducer attach and retries each tick until the
 * loaded handle is available.
 *
 * MVP: non-persistent. Pending entries are lost on server stop.
 */
@Mod.EventBusSubscriber(modid = DungeonTrain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TrainRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentMap<Long, Vector3dc> pending = new ConcurrentHashMap<>();

    private TrainRegistry() {}

    /** Queue an inducer attach; resolved on the next server tick when the ship loads. */
    public static void enqueueAttach(long shipId, Vector3dc velocity) {
        pending.put(shipId, velocity);
        LOGGER.info("[DungeonTrain] Queued inducer attach for ship {} (pending={})", shipId, pending.size());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) return;

        Iterator<ConcurrentMap.Entry<Long, Vector3dc>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            ConcurrentMap.Entry<Long, Vector3dc> entry = it.next();
            long shipId = entry.getKey();
            Vector3dc velocity = entry.getValue();

            for (ServerLevel level : event.getServer().getAllLevels()) {
                LoadedServerShip loaded = VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips().getById(shipId);
                if (loaded == null) continue;
                try {
                    loaded.setAttachment(TrainForcesInducer.class, new TrainForcesInducer(velocity));
                    LOGGER.info("[DungeonTrain] Attached inducer to loaded ship {} in {}", shipId, level.dimension().location());
                    it.remove();
                } catch (Throwable t) {
                    LOGGER.error("[DungeonTrain] setAttachment failed for ship {}; dropping from queue", shipId, t);
                    it.remove();
                }
                break;
            }
        }
    }
}
