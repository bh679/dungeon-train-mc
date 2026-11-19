package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.BandDimensionPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.BandDimensionName;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
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
 * Tells every player which dimension id their debug screen should print for the band they are in.
 *
 * <p>Every {@value #CHECK_INTERVAL_TICKS} ticks the player's block X is classified and a
 * {@link BandDimensionPacket} goes out only when the answer changed, so riding through a band costs one
 * packet per boundary. Anyone outside the overworld, or in a world without a train, gets the empty id:
 * their real dimension is the right answer.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class BandDimensionEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Half a second, the same cadence the F3+4 panel's band readout keeps. */
    private static final int CHECK_INTERVAL_TICKS = 10;

    /** Last id sent per player; a client that has been sent nothing assumes empty. Server thread only. */
    private static final Map<UUID, String> LAST_SENT = new HashMap<>();

    private static int tick = 0;

    private BandDimensionEvents() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tick % CHECK_INTERVAL_TICKS != 0) return;
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        if (overworld == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                check(overworld, player);
            } catch (RuntimeException e) {
                // A debug-screen label must never take the server tick down with it.
                LOGGER.debug("[DT-BandDimension] classify failed for {}", player.getGameProfile().getName(), e);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    /**
     * The client drops its id on disconnect, so a singleplayer player opening another world in the same
     * JVM must be sent theirs again rather than matched against the last world's.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_SENT.clear();
    }

    private static void check(ServerLevel overworld, ServerPlayer player) {
        String id = idFor(overworld, player);
        UUID uuid = player.getUUID();
        if (id.equals(LAST_SENT.getOrDefault(uuid, ""))) return;
        LAST_SENT.put(uuid, id);
        DungeonTrainNet.sendTo(player, new BandDimensionPacket(id));
    }

    private static String idFor(ServerLevel overworld, ServerPlayer player) {
        if (!player.level().dimension().equals(Level.OVERWORLD)) return "";
        if (!DungeonTrainWorldData.get(overworld).startsWithTrain()) return "";
        return BandDimensionName.of(TrainPhase.phaseAt(overworld, player.getBlockX()));
    }
}
