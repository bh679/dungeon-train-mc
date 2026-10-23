package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.LiveCloseCallPacket;
import games.brennan.dungeontrain.net.LiveTrainOfferPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The train's own Live Eye offer: a player is close to a rare challenge ({@link LiveCloseCallEvents}) but
 * nobody anywhere is live, so there is no streamer to hand them the Live Eye. The train offers it instead:
 * "The train senses you are close to a rare challenge. Take the Live Eye? [Accept] [No]".
 *
 * <p>Nothing goes to the relay. The offer is held here, one per player, for
 * {@link LiveCloseCallPacket#WATCH_SECONDS}; an accept must name it ({@code LiveTrainAcceptPacket}), so a
 * forged or stale accept earns nothing. The same {@link LiveGiftServer#refusalFor} blockers run again at
 * accept time before the Live Eye goes on their head ({@link LiveGiftServer#grant}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class LiveTrainOffer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEY = "chat.dungeontrain.live.train_offer.";
    static final long TTL_MS = LiveCloseCallPacket.WATCH_SECONDS * 1000L;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    /** An offer made out to one player: {@code id} redeems it until {@code expiresAtMs}. */
    record Pending(String id, long expiresAtMs) {
        /** Whether an accept naming {@code offerId} at {@code nowMs} redeems this offer. Pure for the tests. */
        boolean redeems(String offerId, long nowMs) {
            return id.equals(offerId) && nowMs < expiresAtMs;
        }
    }

    private LiveTrainOffer() {}

    /** Offer {@code player} the Live Eye from the train. Server thread. */
    public static void offer(ServerPlayer player) {
        String id = HexFormat.of().formatHex(randomBytes());
        PENDING.put(player.getUUID(), new Pending(id, System.currentTimeMillis() + TTL_MS));
        DungeonTrainNet.sendTo(player, new LiveTrainOfferPacket(id));
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[8];
        RANDOM.nextBytes(b);
        return b;
    }

    /** {@code player} accepted the train's offer {@code offerId}. Server thread. */
    public static void onAccept(ServerPlayer player, String offerId) {
        UUID uuid = player.getUUID();
        Pending pending = PENDING.get(uuid);
        if (pending == null || !pending.redeems(offerId, System.currentTimeMillis())) {
            player.sendSystemMessage(Component.translatable("chat.dungeontrain.live_gift.expired").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (LiveGiftServer.refused(player)) return; // offer stays open: they may fix it and accept again
        PENDING.remove(uuid);
        LiveGiftServer.grant(player, Component.translatable(KEY + "received"));
        LOGGER.info("[DungeonTrain] {} took the Live Eye the train offered", player.getGameProfile().getName());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!event.isEndConquered()) PENDING.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        PENDING.remove(event.getEntity().getUUID());
    }
}
