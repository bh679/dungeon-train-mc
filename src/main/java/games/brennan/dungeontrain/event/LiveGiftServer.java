package games.brennan.dungeontrain.event;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import games.brennan.dungeontrain.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of a streamer's Live Eye gift (relay {@code live-gift.js}): the viewer accepted, so
 * check they may have one, redeem the gift with the relay — 200 once, only for the uuid it was made out
 * to — and put a fresh Live Eye on their head, which takes them live.
 *
 * <p>Refused, with a line saying why, for a Kid or a player with Livestreaming off (the relay never
 * offers those, this is the backstop), a player banned from the Live Eye, and one who already holds
 * one (one at a time, as for loot — {@link LiveEyeLootResolver}). The checks run before the redeem, so a
 * refusal leaves the gift unspent, and again after it, since the reply lands later.</p>
 */
public final class LiveGiftServer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String KEY = "chat.dungeontrain.live_gift.";

    /** Players with a redeem in flight: a double-click asks the relay once. */
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    public enum Refusal { NOT_ALLOWED, BANNED, HOLDS_ONE }

    private LiveGiftServer() {}

    /** Why a player can't take a gifted Live Eye, or null when they can. Pure for the tests. */
    @Nullable
    public static Refusal refusal(boolean kid, boolean livestreamOn, boolean banned, boolean holdsLiveEye) {
        if (kid || !livestreamOn) return Refusal.NOT_ALLOWED;
        if (banned) return Refusal.BANNED;
        if (holdsLiveEye) return Refusal.HOLDS_ONE;
        return null;
    }

    /** {@code player} accepted gift {@code giftId} from {@code channel}'s stream. Server thread. */
    public static void onAccept(ServerPlayer player, String giftId, Channel channel) {
        if (!LiveFeedClient.isGiftId(giftId)) return;
        if (refuse(player)) return;
        UUID uuid = player.getUUID();
        if (!IN_FLIGHT.add(uuid)) return;
        MinecraftServer server = player.server;
        LiveFeedClient.giftClaim(LiveFeedClient.viewerBase(channel), giftId, uuid).whenComplete((r, t) -> server.execute(() -> {
            IN_FLIGHT.remove(uuid);
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p == null) return;
            if (t != null || r == null || !r.ok()) {
                int status = r == null ? 0 : r.status();
                LOGGER.info("[DungeonTrain] Live Eye gift {} not redeemed for {} (status {})", giftId, p.getGameProfile().getName(), status);
                p.sendSystemMessage(Component.translatable(status == 404 || status == 409 ? KEY + "expired" : KEY + "failed")
                    .withStyle(ChatFormatting.GRAY));
                return;
            }
            if (refuse(p)) return;
            grant(p, r.str("from"));
        }));
    }

    private static boolean refuse(ServerPlayer player) {
        Refusal why = refusal(ContentModeMirror.isKid(player), ContentModeMirror.isLivestreamOn(player),
            LiveModeration.isBanned(player.getUUID()), LiveEyeLootResolver.holdsLiveEye(player));
        if (why == null) return false;
        String key = switch (why) {
            case NOT_ALLOWED -> KEY + "not_allowed";
            case BANNED -> KEY + "banned";
            case HOLDS_ONE -> KEY + "holds_one";
        };
        player.sendSystemMessage(Component.translatable(key).withStyle(ChatFormatting.GRAY));
        return true;
    }

    /**
     * Put the Live Eye straight on {@code player}'s head — wearing it is what starts the stream
     * ({@link LiveFeedEvents#onEquipmentChange}). Whatever was worn there, normally the TV they were
     * watching on, goes to the inventory, or at their feet when it is full.
     */
    private static void grant(ServerPlayer player, String from) {
        ItemStack worn = player.getItemBySlot(EquipmentSlot.HEAD).copy();
        player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(ModItems.LIVE_HEADPIECE.get()));
        if (!worn.isEmpty() && !player.getInventory().add(worn)) player.drop(worn, false);
        LiveEyeLootResolver.markSeen(player);
        String giver = from == null || from.isBlank() ? "Someone" : from;
        player.sendSystemMessage(Component.translatable(KEY + "received", giver).withStyle(ChatFormatting.GOLD));
        LOGGER.info("[DungeonTrain] {} received a Live Eye gifted by {}", player.getGameProfile().getName(), giver);
    }
}
