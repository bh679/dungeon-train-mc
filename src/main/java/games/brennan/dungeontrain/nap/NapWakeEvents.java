package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.event.PlayerJoinEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * The last of waking ({@link NapWake} already started the train at the nap and loaded the player):
 * carry the run's Free Play over, and once the join placement has put the player on the deck, turn
 * them to face -X when that is the way they were heading (the placement faces +X).
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NapWakeEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Facing -X; the join placement lands the player facing +X (yaw -90). */
    private static final float FACING_NEGATIVE_X = 90.0F;

    /** Woken players to turn once placed. Server-thread only. */
    private static final Set<UUID> TURNING = new HashSet<>();

    private NapWakeEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        NapWake.takeWoken(player.getUUID()).ifPresent(nap -> {
            // The Free Play that came from the old world itself (keepInventory, portal tuning) isn't in
            // the player tag; carry it so a nap can never clean a run.
            if (nap.cheated() && !RunIntegrity.isCheated(player)) {
                RunIntegrity.markCheated(player, Component.translatable("gui.dungeontrain.nap.free_play_cause"));
            }
            if (nap.direction() < 0) TURNING.add(player.getUUID());
            LOGGER.info("[DungeonTrain] Nap {} woke {} at x={} carriage={}", nap.napId(),
                player.getName().getString(), Math.round(nap.x()), nap.carriage());
        });
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TURNING.remove(event.getEntity().getUUID());
        NapWakeGrace.close(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        TURNING.clear();
        NapWake.clear();
        NapWakeGrace.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        NapWakeGrace.tick(PlayerJoinEvents::isPlacementPending);
        if (TURNING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        Iterator<UUID> it = TURNING.iterator();
        while (it.hasNext()) {
            ServerPlayer player = server.getPlayerList().getPlayer(it.next());
            if (player == null) {
                it.remove();
                continue;
            }
            if (PlayerJoinEvents.isPlacementPending(player.getUUID()) || player.tickCount < 20) continue;
            it.remove();
            player.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                FACING_NEGATIVE_X, player.getXRot());
        }
    }
}
