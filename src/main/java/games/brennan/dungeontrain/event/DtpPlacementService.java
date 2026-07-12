package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.SpawnDeckHoldPacket;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deferred second half of {@code /dtp <x>}. {@code DtpCommand} spawns a fresh
 * train seeded at the requested world-X and parks the player in a safe
 * holding spot above the assembly footprint, then queues them here. A
 * freshly-assembled Sable group's {@code canonicalPos} is null until its
 * first physics tick — the same race {@link PlayerJoinEvents} handles on
 * login — so this retries once per overworld tick until
 * {@link PlayerJoinEvents#findNearestFlatbedTarget} resolves a settled deck,
 * then drops the player onto it and releases the hold.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class DtpPlacementService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Max ticks to retry before giving up and releasing the player in place. 20 TPS, so 100 ≈ 5s. */
    private static final int MAX_RETRY_TICKS = 100;

    private record PendingDtp(ServerLevel trainLevel, double targetX, int ticks) {}

    /** Player UUID → in-flight /dtp request. Cleared on success/timeout/logout. */
    private static final Map<UUID, PendingDtp> PENDING = new ConcurrentHashMap<>();

    private DtpPlacementService() {}

    /** Queue {@code player} for deferred flatbed placement once the train seeded at {@code targetX} settles. */
    public static void enqueue(ServerPlayer player, ServerLevel trainLevel, double targetX) {
        PENDING.put(player.getUUID(), new PendingDtp(trainLevel, targetX, 0));
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }

        public static void onLevelTick(net.minecraft.world.level.Level tickedLevel) {
        if (!(tickedLevel instanceof ServerLevel level)) return;
        if (level.dimension() != Level.OVERWORLD) return;
        if (PENDING.isEmpty()) return;

        MinecraftServer server = level.getServer();
        Iterator<Map.Entry<UUID, PendingDtp>> it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingDtp> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }

            PendingDtp pending = entry.getValue();
            if (tryFinish(server, player, pending)) {
                it.remove();
                continue;
            }

            int ticks = pending.ticks() + 1;
            if (ticks >= MAX_RETRY_TICKS) {
                LOGGER.warn("[DungeonTrain] /dtp placement timed out for {} after {} ticks — releasing at holding spot",
                    player.getName().getString(), MAX_RETRY_TICKS);
                player.setInvulnerable(false);
                player.sendSystemMessage(Component.literal(
                    "The train didn't settle in time — you've been left at the holding spot. Try /dtp again."));
                it.remove();
            } else {
                entry.setValue(new PendingDtp(pending.trainLevel(), pending.targetX(), ticks));
            }
        }
    }

    /** @return true once a placement decision was made (success) — false to retry next tick. */
    private static boolean tryFinish(MinecraftServer server, ServerPlayer player, PendingDtp pending) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(server.overworld());
        PlayerJoinEvents.FlatbedTarget flat = PlayerJoinEvents.findNearestFlatbedTarget(
            pending.trainLevel(), data, pending.targetX());
        if (flat == null) return false; // train not settled yet — retry next tick

        player.teleportTo(pending.trainLevel(), flat.x(), flat.y(), flat.z(), -90.0f, 0.0f);
        DungeonTrainNet.sendTo(player, new SpawnDeckHoldPacket(
            data.getTrainY() + 1.0, SpawnDeckHoldPacket.DEFAULT_HOLD_TICKS));
        player.setInvulnerable(false);
        player.sendSystemMessage(Component.literal(String.format(
            "Train ready — you're on the flatbed at (%.1f, %.1f, %.1f).", flat.x(), flat.y(), flat.z())));

        LOGGER.info("[DungeonTrain] /dtp placed {} on flatbed at ({}, {}, {})",
            player.getName().getString(),
            String.format("%.1f", flat.x()), String.format("%.1f", flat.y()), String.format("%.1f", flat.z()));
        return true;
    }
}
