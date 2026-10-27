package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.event.LiveEyeLootResolver;
import games.brennan.dungeontrain.net.ClientActionPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.LiveGiftAcceptPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftJoin;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.GiftOffer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

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
 * The button offers this streamer's own Live Eye through the
 * relay. It is a donation: once the viewer's own stream claims the channel, the relay hands it over, so this
 * stream's next upload is refused and the server burns the Live Eye ("moves onto the next streamer"). A
 * viewer who accepts but never goes live leaves this stream running.</p>
 *
 * <p><b>Viewer:</b> an offer made out to this player (a {@code gifts[]} notice) prints
 * <b>[Accept]</b> / <b>[No thanks]</b>; accepting asks this player's own server to redeem it
 * ({@link LiveGiftAcceptPacket}), which hands over the item.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveGiftClient {

    /** The streamer sees the gift prompt at most this often. */
    public static final long PROMPT_GAP_MS = 12L * 60L * 1000L;
    /** The buttons' action ({@link ChatClick}): {@code live-gift/give|accept|decline/<id>}. */
    static final String CLICK_ACTION = "live-gift";
    private static final String KEY = "chat.dungeontrain.live_gift.";
    static final String OFFER_KEY = "command.dungeontrain.offer_eye.";
    private static final String CLOSE_KEY = "chat.dungeontrain.live.close_call.";
    private static final int CAP = 32;

    /** Client thread only. */
    private static long lastPromptMs = -1;
    private static final Map<String, String> PROMPTED_JOINS = new LinkedHashMap<>(); // joinId → viewer name
    private static final Map<String, Offer> OFFERS = new LinkedHashMap<>();          // giftId → offer
    private static final Set<String> SEEN_GIFTS = new LinkedHashSet<>();

    private record Offer(String from, Channel channel) {}

    private LiveGiftClient() {}

    /**
     * Whether a player can take a gifted Live Eye and go live with it: not a Kid, Livestreaming on behind
     * the internet consent, not a Free Play run (which burns it on the way on), ffmpeg usable, none carried
     * or worn. Only then does it join as eligible, see an offer, or accept one — so nobody is offered what
     * they can't take. Pure for the tests.
     */
    public static boolean canReceive(boolean kid, boolean streamingOn, boolean holdsLiveEye, boolean freePlay, boolean ffmpegUsable) {
        return !kid && streamingOn && !holdsLiveEye && !freePlay && ffmpegUsable;
    }

    /** {@link #canReceive} for this client right now. Client thread. */
    public static boolean canReceive(Minecraft mc) {
        return mc.player != null && canReceive(ClientDisplayConfig.isKidMode(), LiveEyeEligibility.streamingAllowed(),
            LiveEyeLootResolver.countRealLiveEyes(mc.player.getInventory()) > 0,
            LiveEyeEligibility.freePlayBlocks(mc.player), LiveEyeEligibility.ffmpegUsable());
    }

    /** Whether a join at {@code now} may prompt, the last prompt having been at {@code lastMs} (−1 = never). */
    public static boolean shouldPrompt(long lastMs, long now) {
        return lastMs < 0 || now < lastMs || now - lastMs >= PROMPT_GAP_MS;
    }

    /** Streamer: a never-streamed TV viewer joined, or a player is close to a rare challenge. Client thread. */
    public static void onJoin(GiftJoin join) {
        if (join.closeCall()) {
            onCloseCall(join);
            return;
        }
        long now = System.currentTimeMillis();
        if (!shouldPrompt(lastPromptMs, now)) return;
        lastPromptMs = now;
        put(PROMPTED_JOINS, join.joinId(), join.name());
        MutableComponent line = Component.translatable(KEY + "prompt", join.name()).withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(" "))
            .append(button(KEY + "give", ChatClick.event(CLICK_ACTION, "give", join.joinId()), ChatFormatting.GOLD, false));
        Minecraft.getInstance().gui.getChat().addMessage(line);
    }

    /**
     * Streamer: {@code join} is a few minutes from a rare challenge ({@code event/LiveCloseCallEvents}). Rare
     * (once per advancement per life, 30 minutes apart per player), so not held to {@link #PROMPT_GAP_MS}.
     * Never names the advancement: they are hidden ones.
     */
    private static void onCloseCall(GiftJoin join) {
        put(PROMPTED_JOINS, join.joinId(), join.name());
        MutableComponent line = Component.translatable(CLOSE_KEY + "prompt", join.name()).withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(" "))
            .append(button(CLOSE_KEY + "give", ChatClick.event(CLICK_ACTION, "give", join.joinId()), ChatFormatting.GOLD, false));
        Minecraft.getInstance().gui.getChat().addMessage(line);
    }

    /** Viewer: the streamer offered this player a Live Eye on {@code channel}. Deduped by id. Client thread. */
    public static void onOffer(GiftOffer offer, Channel channel) {
        if (!SEEN_GIFTS.add(offer.giftId())) return;
        if (SEEN_GIFTS.size() > CAP) SEEN_GIFTS.remove(SEEN_GIFTS.iterator().next());
        // Joined as eligible, but no longer (found a Live Eye, switched Livestreaming off): never offered.
        if (!canReceive(Minecraft.getInstance())) return;
        put(OFFERS, offer.giftId(), new Offer(offer.from(), channel));
        String offerKey = offer.all() ? KEY + "offer_all"
            : LiveCloseCallWatch.watching() ? CLOSE_KEY + "offer" : KEY + "offer";
        MutableComponent line = Component.translatable(offerKey, offer.from()).withStyle(ChatFormatting.LIGHT_PURPLE)
            .append(Component.literal(" "))
            .append(button(KEY + "accept", ChatClick.event(CLICK_ACTION, "accept", offer.giftId()), ChatFormatting.GREEN, true))
            .append(Component.literal(" "))
            .append(button(KEY + "decline", ChatClick.event(CLICK_ACTION, "decline", offer.giftId()), ChatFormatting.GRAY, true));
        Minecraft.getInstance().gui.getChat().addMessage(line);
    }

    /** Wire the gift buttons ({@code DungeonTrainClient.clientSetup}). */
    public static void registerClicks() {
        ChatClickActions.register(CLICK_ACTION, args -> {
            if (args.size() < 2) return;
            String id = args.get(1);
            switch (args.get(0)) {
                case "give" -> give(id);
                case "offer" -> offerTo(id);
                case "offer-all" -> offerAll();
                case "accept" -> answer(id, true);
                case "decline" -> answer(id, false);
                default -> { }
            }
        });
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
            if (!r.ok()) {
                say(mc, Component.translatable(KEY + "give_failed", name).withStyle(ChatFormatting.GRAY));
            } else if (mc.getConnection() != null) {
                DungeonTrainNet.sendToServer(new ClientActionPacket("gifted_live_eye")); // Pass It On
            }
        }, mc);
        return 1;
    }

    /**
     * Streamer: {@code /offereye <name>} or its list's [Offer] button. Any eligible TV viewer, not only one the
     * relay prompted for; the relay checks they are watching and can take it. Client thread.
     */
    static int offerTo(String name) {
        Minecraft mc = Minecraft.getInstance();
        String token = LiveStreamController.get().streamToken();
        if (token == null) {
            say(mc, Component.translatable(OFFER_KEY + "not_live").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        LiveFeedClient.giftOffer(DungeonTrain.relayBaseUrl(), token, name).thenAcceptAsync(r -> {
            if (r.ok()) {
                say(mc, Component.translatable(KEY + "offered", r.str("name").isEmpty() ? name : r.str("name"))
                    .withStyle(ChatFormatting.GRAY));
                if (mc.getConnection() != null) DungeonTrainNet.sendToServer(new ClientActionPacket("gifted_live_eye"));
                return;
            }
            say(mc, Component.translatable(offerFailureKey(r.status(), r.str("error")), name).withStyle(ChatFormatting.GRAY));
        }, mc);
        return 1;
    }

    /** Streamer: {@code /offereye all} or the list's All [Offer]: everyone who can take it; the first to accept wins. */
    static int offerAll() {
        Minecraft mc = Minecraft.getInstance();
        String token = LiveStreamController.get().streamToken();
        if (token == null) {
            say(mc, Component.translatable(OFFER_KEY + "not_live").withStyle(ChatFormatting.GRAY));
            return 0;
        }
        LiveFeedClient.giftOfferAll(DungeonTrain.relayBaseUrl(), token).thenAcceptAsync(r -> {
            if (r.ok()) {
                say(mc, Component.translatable(OFFER_KEY + "offered_all", r.str("count")).withStyle(ChatFormatting.GRAY));
                if (mc.getConnection() != null) DungeonTrainNet.sendToServer(new ClientActionPacket("gifted_live_eye"));
                return;
            }
            say(mc, Component.translatable(offerAllFailureKey(r.status(), r.str("error"))).withStyle(ChatFormatting.GRAY));
        }, mc);
        return 1;
    }

    /** The line for a refused {@code /offereye all}. None of these name a viewer. Pure. */
    static String offerAllFailureKey(int status, String error) {
        if (status == 404 && "not_watching".equals(error)) return OFFER_KEY + "none";
        if (status == 409 && "already_offered".equals(error)) return OFFER_KEY + "all_already_offered";
        String key = offerFailureKey(status, error);
        return key.endsWith("give_failed") ? OFFER_KEY + "list_failed" : key;
    }

    /** The line for a refused {@code /offereye}. An older relay 404s with no error: the command isn't there yet. Pure. */
    static String offerFailureKey(int status, String error) {
        if (status == 409 && "already_given".equals(error)) return OFFER_KEY + "already_given";
        if (status == 409) return OFFER_KEY + "already_offered";
        if (status == 404 && "not_watching".equals(error)) return OFFER_KEY + "not_watching";
        if (status == 404) return OFFER_KEY + "unavailable";
        if (status == 403) return OFFER_KEY + "not_live";
        return KEY + "give_failed";
    }

    /** The list's {@code All [Offer]} button: offer it to everyone, first to accept gets it. */
    static Component offerAllButton() {
        return Component.literal("[").append(Component.translatable(OFFER_KEY + "button")).append("]")
            .withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(true)
                .withClickEvent(ChatClick.event(CLICK_ACTION, "offer-all", "all"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(OFFER_KEY + "all_tip"))));
    }

    /** A gold {@code [Offer]} button for {@code name} ({@link OfferEyeCommand}'s list). */
    static Component offerButton(String name) {
        return button(OFFER_KEY + "button", ChatClick.event(CLICK_ACTION, "offer", name), ChatFormatting.GOLD, true);
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

    static void say(Minecraft mc, Component line) {
        if (mc.player != null) mc.gui.getChat().addMessage(line);
    }

    private static <V> void put(Map<String, V> map, String key, V value) {
        map.remove(key);
        map.put(key, value);
        while (map.size() > CAP) map.remove(map.keySet().iterator().next());
    }

    /** A clickable {@code [label]}; {@code tip} adds the {@code key + ".tip"} hover text. */
    private static Component button(String key, ClickEvent click, ChatFormatting colour, boolean tip) {
        return Component.literal("[").append(Component.translatable(key)).append("]")
            .withStyle(s -> {
                var style = s.withColor(colour).withBold(true)
                    .withClickEvent(click);
                return tip ? style.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(key + ".tip"))) : style;
            });
    }
}
