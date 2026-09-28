package games.brennan.dungeontrain.debug;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TrainDebugBandPacket;
import games.brennan.dungeontrain.worldgen.BandLabel;
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
 * Tells each player who may open the F3+4 debug panel which band they are standing in.
 *
 * <p>Every {@value #CHECK_INTERVAL_TICKS} ticks the player's block X is classified with
 * {@link BandLabel} and a {@link TrainDebugBandPacket} goes out only when the answer changed, so a
 * player riding through a band costs one packet per boundary. Players without access are skipped
 * entirely — nothing is classified and nothing is sent — and forgotten, so a grant that arrives
 * later gets a fresh send on its first check.</p>
 *
 * <p>Separate from {@link DebugAccessEvents} because that tick handler stands down on developer
 * builds, which are exactly where the panel is always open.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DebugBandEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Half a second — a band boundary shows up on the panel before the player has gone far past it. */
    private static final int CHECK_INTERVAL_TICKS = 10;

    /** Last label sent per player. Server thread only. */
    private static final Map<UUID, BandLabel> LAST_SENT = new HashMap<>();

    private static int tick = 0;

    private DebugBandEvents() {}

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
                // A readout for the dev panel must never take the server tick down with it.
                LOGGER.debug("[DT-DebugBand] classify failed for {}", player.getGameProfile().getName(), e);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    /**
     * The client drops its band on disconnect, so a singleplayer player opening another world in
     * the same JVM must be sent theirs again rather than matched against the last world's.
     */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LAST_SENT.clear();
    }

    private static void check(ServerLevel overworld, ServerPlayer player) {
        UUID uuid = player.getUUID();
        if (!DebugAccessEvents.isPermitted(player)) {
            LAST_SENT.remove(uuid);
            return;
        }
        BandLabel label = BandLabel.at(overworld, player.getBlockX());
        if (label.equals(LAST_SENT.get(uuid))) return;
        LAST_SENT.put(uuid, label);
        DungeonTrainNet.sendTo(player, new TrainDebugBandPacket(label.band(), label.stage(), label.lap()));
    }
}
