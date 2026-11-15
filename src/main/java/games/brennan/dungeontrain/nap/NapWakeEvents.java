package games.brennan.dungeontrain.nap;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.event.TrainRelocation;
import games.brennan.dungeontrain.player.RunRestore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The second half of waking ({@link NapWake} loaded the player): once the woken player has settled on
 * the new world's spawn train, move them to the X they napped at, the same relocation an admin run
 * restore uses ({@link TrainRelocation}, difficulty left to the restored run state), and turn them to
 * face the way they were heading.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class NapWakeEvents {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** In-world ticks before the move, so the spawn train and the join placement are done. */
    static final int SETTLE_TICKS = 20 * 5;
    /** Facing -X; the relocation lands the player facing +X (yaw -90). */
    private static final float FACING_NEGATIVE_X = 90.0F;

    private record Turn(float yaw) {}

    /** Server-thread only. */
    private static final Map<UUID, NapSnapshot> SETTLING = new HashMap<>();
    private static final Map<UUID, Turn> TURNING = new HashMap<>();

    private NapWakeEvents() {}

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        NapWake.takeWoken(player.getUUID()).ifPresent(nap -> SETTLING.put(player.getUUID(), nap));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SETTLING.remove(event.getEntity().getUUID());
        TURNING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SETTLING.clear();
        TURNING.clear();
        NapWake.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SETTLING.isEmpty() && TURNING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        moveSettled(server);
        turnPlaced(server);
    }

    private static void moveSettled(MinecraftServer server) {
        Iterator<Map.Entry<UUID, NapSnapshot>> it = SETTLING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, NapSnapshot> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (player.tickCount < SETTLE_TICKS || !player.isAlive()) continue;
            it.remove();
            wake(player, e.getValue());
        }
    }

    private static void wake(ServerPlayer player, NapSnapshot nap) {
        // The Free Play that came from the old world itself (keepInventory, portal tuning) isn't in
        // the player tag; carry it so a nap can never clean a run.
        if (nap.cheated() && !RunIntegrity.isCheated(player)) {
            RunIntegrity.markCheated(player, Component.translatable("gui.dungeontrain.nap.free_play_cause"));
        }
        RunRestore.raiseWorldProgress(player.serverLevel(), nap.worldDepth());
        TrainRelocation.Result result = TrainRelocation.relocate(player, nap.x(), false);
        if (!result.ok()) {
            LOGGER.warn("[DungeonTrain] Nap {}: relocation to x={} failed: {}", nap.napId(), Math.round(nap.x()),
                result.failure().getString());
            return;
        }
        if (nap.direction() < 0) TURNING.put(player.getUUID(), new Turn(FACING_NEGATIVE_X));
        LOGGER.info("[DungeonTrain] Nap {} woke {} at x={} carriage={}", nap.napId(), player.getName().getString(),
            Math.round(nap.x()), nap.carriage());
    }

    /** The relocation clears invulnerability once the player is on the deck; then turn them. */
    private static void turnPlaced(MinecraftServer server) {
        Iterator<Map.Entry<UUID, Turn>> it = TURNING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Turn> e = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player == null) {
                it.remove();
                continue;
            }
            if (player.isInvulnerable()) continue;
            it.remove();
            player.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(),
                e.getValue().yaw(), player.getXRot());
        }
    }
}
