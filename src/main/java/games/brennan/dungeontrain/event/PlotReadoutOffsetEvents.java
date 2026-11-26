package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.PlotReadoutOffsetPacket;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps every player's copy of the train's plot readout offset ({@link PlotReadoutOffsetPacket}) current:
 * sent to each player the first time they are seen and again whenever a train spawn records a new
 * one. Same shape as {@link BandDimensionEvents}: polled, and sent only on a change.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlotReadoutOffsetEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A second: the offset changes only when a train spawns. */
    private static final int CHECK_INTERVAL_TICKS = 20;

    /** Last packet sent per player. Server thread only. */
    private static final Map<UUID, PlotReadoutOffsetPacket> LAST_SENT = new HashMap<>();

    private static int tick = 0;

    private PlotReadoutOffsetEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tick % CHECK_INTERVAL_TICKS != 0) return;
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        PlotReadoutOffsetPacket current;
        try {
            DungeonTrainWorldData data = DungeonTrainWorldData.get(overworld);
            current = data.hasPlotReadoutOffset()
                ? new PlotReadoutOffsetPacket(true, data.getPlotReadoutDx(), data.getPlotReadoutDz())
                : new PlotReadoutOffsetPacket(false, 0L, 0L);
        } catch (RuntimeException e) {
            // A debug readout must never take the server tick down with it.
            LOGGER.warn("[DungeonTrain] Plot readout offset check failed", e);
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            if (current.equals(LAST_SENT.get(uuid))) continue;
            LAST_SENT.put(uuid, current);
            DungeonTrainNet.sendTo(player, current);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    /** The client drops its copy on disconnect, so the next world in the same JVM is sent its own. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_SENT.clear();
    }
}
