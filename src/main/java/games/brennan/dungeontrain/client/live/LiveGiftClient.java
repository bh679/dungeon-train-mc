package games.brennan.dungeontrain.client.live;

import com.mojang.brigadier.arguments.StringArgumentType;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.event.LiveEyeLootResolver;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.LiveGiftAcceptPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftJoin;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftOffer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Client side of gifting a Live Eye (relay {@code live-gift.js}).
 *
 * <p><b>Streamer:</b> a TV viewer who has never streamed joined the chat (a {@code joins[]} notice from
 * {@link LiveChatBridge}). At most one every {@link #PROMPT_GAP_MS} (12 minutes) prints
 * "X joins via a TV, they have never had the Live Eye. <b>[Gift it to them]</b>"; the rest are dropped.
 * The button runs {@code /dtlivegift give <joinId>}, which offers this streamer's own Live Eye through the
 * relay. It is a donation: when the viewer accepts, the relay ends this stream, so the next upload is
 * refused and the server burns the Live Eye ("moves onto the next streamer") as theirs goes on.</p>
 *
 * <p><b>Viewer:</b> an offer made out to this player (a {@code gifts[]} notice) prints
 * <b>[Accept]</b> / <b>[No thanks]</b>; accepting asks this player's own server to redeem it
 * ({@link LiveGiftAcceptPacket}), which hands over the item.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveGiftClient {

    /** The streamer sees the gift prompt at most this often. */
    public static final long PROMPT_GAP_MS = 12L * 60L * 1000L;
    private static final String COMMAND = "dtlivegift";
    private static final String KEY = "chat.dungeontrain.live_gift.";
    private static final int CAP = 32;

    /** Client thread only. */
    private static long lastPromptMs = -1;
    private static final Map<String, String> PROMPTED_JOINS = new LinkedHashMap<>(); // joinId → viewer name
    private static final Map<String, Offer> OFFERS = new LinkedHashMap<>();          // giftId → offer
    private static final Set<String> SEEN_GIFTS = new LinkedHashSet<>();

    private record Offer(String from, Channel channel) {}

    private LiveGiftClient() {}

    /**
     * Whether a player can take a gifted Live Eye: not a Kid, Livestreaming on, none carried or worn.
     * Only then does it join as eligible, see an offer, or accept one — so nobody is offered what they
     * can't take. Pure for the tests.
     */
    public static boolean canReceive(boolean kid, boolean streamingOn, boolean holdsLiveEye) {
        return !kid && streamingOn && !holdsLiveEye;
    }

    /** {@link #canReceive} for this client right now. Client thread. */
    public static boolean canReceive(Minecraft mc) {
        return mc.player != null && canReceive(ClientDisplayConfig.isKidMode(), LiveFeedClientConfig.streamingEnabled(),
            LiveEyeLootResolver.countRealLiveEyes(mc.player.getInventory()) > 0);
    }

    /** Whether a join at {@code now} may prompt, the last prompt having been at {@code lastMs} (−1 = never). */
    public static boolean shouldPrompt(long lastMs, long now) {
        return lastMs < 0 || now < lastMs || now - lastMs >= PROMPT_GAP_MS;
    }

    /** Streamer: a never-streamed TV viewer joined. Client thread. */
    public static void onJoin(GiftJoin join) {
        long now = System.currentTimeMillis();
        if (!shouldPrompt(lastPromptMs, now)) return;
        lastPromptMs = now;
        put(PROMPTED_JOINS, join.joinId(), join.name());
        MutableComponent line = Component.translatable(KEY + "prompt", join.name()).withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(" "))
            .append(button(KEY + "give", "/" + COMMAND + " give " + join.joinId(), ChatFormatting.GOLD));
        Minecraft.getInstance().gui.getChat().addMessage(line);
    }

    /** Viewer: the streamer offered this player a Live Eye on {@code channel}. Deduped by id. Client thread. */
    public static void onOffer(GiftOffer offer, Channel channel) {
        if (!SEEN_GIFTS.add(offer.giftId())) return;
        if (SEEN_GIFTS.size() > CAP) SEEN_GIFTS.remove(SEEN_GIFTS.iterator().next());
        // Joined as eligible, but no longer (found a Live Eye, switched Livestreaming off): never offered.
        if (!canReceive(Minecraft.getInstance())) return;
        put(OFFERS, offer.giftId(), new Offer(offer.from(), channel));
        MutableComponent line = Component.translatable(KEY + "offer", offer.from()).withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(" "))
            .append(button(KEY + "accept", "/" + COMMAND + " accept " + offer.giftId(), ChatFormatting.GREEN))
            .append(Component.literal(" "))
            .append(button(KEY + "decline", "/" + COMMAND + " decline " + offer.giftId(), ChatFormatting.GRAY));
        Minecraft.getInstance().gui.getChat().addMessage(line);
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(COMMAND)
            .then(Commands.literal("give").then(Commands.argument("id", StringArgumentType.word())
                .executes(ctx -> give(StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("accept").then(Commands.argument("id", StringArgumentType.word())
                .executes(ctx -> answer(StringArgumentType.getString(ctx, "id"), true))))
            .then(Commands.literal("decline").then(Commands.argument("id", StringArgumentType.word())
                .executes(ctx -> answer(StringArgumentType.getString(ctx, "id"), false)))));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        PROMPTED_JOINS.clear();
        OFFERS.clear();
    }

    private static int give(String joinId) {
        Minecraft mc = Minecraft.getInstance();
        String name = PROMPTED_JOINS.remove(joinId);
        String token = LiveStreamController.get().streamToken();
        if (name == null || token == null) {
            say(mc, Component.translatable(KEY + "give_expired").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        say(mc, Component.translatable(KEY + "offered", name).withStyle(ChatFormatting.GRAY));
        LiveFeedClient.gift(DungeonTrain.relayBaseUrl(), token, joinId).thenAcceptAsync(r -> {
            if (!r.ok()) say(mc, Component.translatable(KEY + "give_failed", name).withStyle(ChatFormatting.GRAY));
        }, mc);
        return 1;
    }

    private static int answer(String giftId, boolean accept) {
        Minecraft mc = Minecraft.getInstance();
        Offer offer = OFFERS.remove(giftId);
        if (offer == null || mc.getConnection() == null || (accept && !canReceive(mc))) {
            say(mc, Component.translatable(KEY + "expired").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        if (!accept) {
            say(mc, Component.translatable(KEY + "declined").withStyle(ChatFormatting.GRAY));
            return 1;
        }
        DungeonTrainNet.sendToServer(new LiveGiftAcceptPacket(giftId, offer.channel() == Channel.DEV));
        return 1;
    }

    private static void say(Minecraft mc, Component line) {
        if (mc.player != null) mc.gui.getChat().addMessage(line);
    }

    private static <V> void put(Map<String, V> map, String key, V value) {
        map.remove(key);
        map.put(key, value);
        while (map.size() > CAP) map.remove(map.keySet().iterator().next());
    }

    private static Component button(String key, String command, ChatFormatting colour) {
        return Component.literal("[").append(Component.translatable(key)).append("]")
            .withStyle(s -> s.withColor(colour).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(key + ".tip"))));
    }
}
