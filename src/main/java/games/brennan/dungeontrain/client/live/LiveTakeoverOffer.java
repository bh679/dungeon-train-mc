package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvItems;
import games.brennan.dungeontrain.config.LiveFeedClientConfig;
import games.brennan.dungeontrain.net.LiveTakeoverPacket;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Channel;
import games.brennan.dungeontrain.net.relay.LiveFeedClient.Takeover;
import games.brennan.dungeontrain.registry.ModMobEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * The viewer's half of a death takeover: the streamer died, and the relay offers their stream to
 * every in-game viewer ({@code takeover} in the status poll). A viewer wearing a TV on their head is
 * told in chat, with a button ({@link #promptLine}) and one under their TV box
 * ({@link LiveTakeoverButton}). The first to accept wins a one-use ticket; their server checks it and
 * swaps the worn TV for a Live Eye ({@link LiveTakeoverPacket}), which goes live as any Live Eye does,
 * and the ticket carries the claim past the relay's tenure check ({@link #takeTicket}). The dead
 * streamer is cut off by that claim. No vote and no second confirmation: first click wins.
 *
 * <p>Render thread, except where noted.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveTakeoverOffer {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final String COMMAND = "dungeontrain-live-takeover";
    /** How long a won ticket waits for the Live Eye to land and its claim to go out. */
    static final long TICKET_TTL_MS = 55_000;

    @Nullable private static Takeover offer;
    private static Channel offerChannel = Channel.LIVE;
    private static long offerUntilMs;
    /** The last offer said in chat, so each death is announced once. */
    @Nullable private static String announcedId;
    private static boolean accepting;
    @Nullable private static volatile String ticket;
    private static volatile long ticketUntilMs;

    private LiveTakeoverOffer() {}

    /** The status poll's answer on the channel this world's TVs show (render thread). */
    static void polled(@Nullable Takeover t, Channel channel) {
        if (t == null) {
            offer = null;
            return;
        }
        offer = t;
        offerChannel = channel;
        offerUntilMs = System.currentTimeMillis() + t.untilMs();
        if (t.id().equals(announcedId) || !eligible()) return;
        announcedId = t.id();
        say(promptLine(t));
    }

    /** An offer is open that this player could take — the poller speeds up while it is. */
    static boolean open() {
        return offer != null && System.currentTimeMillis() < offerUntilMs && eligible();
    }

    /** The open offer's id, or null. */
    @Nullable
    static Takeover current() {
        return open() ? offer : null;
    }

    /**
     * Who may take over: wearing a TV on their head (it becomes the Live Eye), not Free Play (a Free
     * Play run never goes live), Live Eye streaming switched on behind the internet consent, ffmpeg
     * usable, and not streaming already.
     */
    static boolean eligible() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.player.isAlive()) return false;
        return eligible(TvItems.isTv(mc.player.getItemBySlot(EquipmentSlot.HEAD)),
            mc.player.hasEffect(ModMobEffects.FREE_PLAY),
            DiscordPresenceClientConfig.isGranted() && LiveFeedClientConfig.streamingEnabled(),
            FfmpegSupport.state() != FfmpegSupport.State.OFF && FfmpegSupport.state() != FfmpegSupport.State.FAILED,
            LiveStreamController.get().state() == LiveStreamController.State.IDLE);
    }

    static boolean eligible(boolean tvOnHead, boolean freePlay, boolean streamingAllowed, boolean ffmpegUsable, boolean idle) {
        return tvOnHead && !freePlay && streamingAllowed && ffmpegUsable && idle;
    }

    /** "<Name> died live — [Take over the stream]". */
    static Component promptLine(Takeover t) {
        MutableComponent button = Component.literal("[").append(Component.translatable("chat.dungeontrain.live.takeover.button")).append("]")
            .withStyle(s -> s.withColor(ChatFormatting.GREEN).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + COMMAND + " " + t.id()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.dungeontrain.live.takeover.button.tip"))));
        return Component.translatable("chat.dungeontrain.live.takeover.offer", t.from().isEmpty() ? "?" : t.from(), button)
            .withStyle(ChatFormatting.YELLOW);
    }

    /** Take the open offer: first to the relay wins. */
    static void accept() {
        Takeover t = current();
        Minecraft mc = Minecraft.getInstance();
        if (t == null) {
            say(Component.translatable(eligible() || offer == null ? "chat.dungeontrain.live.takeover.gone"
                : "chat.dungeontrain.live.takeover.not_eligible").withStyle(ChatFormatting.GRAY));
            return;
        }
        if (accepting) return;
        UUID uuid = mc.getUser() != null ? mc.getUser().getProfileId() : null;
        String name = mc.getUser() != null ? mc.getUser().getName() : "";
        if (uuid == null || name.isEmpty()) return;
        accepting = true;
        LiveFeedClient.acceptTakeover(offerChannel, t.id(), uuid, name).whenCompleteAsync((r, err) -> {
            accepting = false;
            String won = r == null ? "" : r.str("ticket");
            if (r != null && r.ok() && !won.isEmpty()) {
                LOGGER.info("[DungeonTrain] live: won the takeover of {}'s stream", t.from());
                offer = null;
                ticket = won;
                ticketUntilMs = System.currentTimeMillis() + TICKET_TTL_MS;
                if (mc.getConnection() != null) PacketDistributor.sendToServer(new LiveTakeoverPacket(won));
                say(Component.translatable("chat.dungeontrain.live.takeover.won").withStyle(ChatFormatting.GREEN));
                return;
            }
            String error = r == null ? "" : r.str("error");
            String key = switch (error) {
                case "taken" -> "chat.dungeontrain.live.takeover.taken";
                case "gone", "self" -> "chat.dungeontrain.live.takeover.gone";
                default -> "chat.dungeontrain.live.takeover.failed";
            };
            if (!key.endsWith(".failed")) offer = null; // a relay hiccup leaves the offer up to try again
            say(Component.translatable(key).withStyle(ChatFormatting.GRAY));
        }, mc);
    }

    /** The won ticket for the claim, once; null when there is none or it has run out. Any thread. */
    @Nullable
    static String takeTicket(long nowMs) {
        String t = ticket;
        ticket = null;
        return t != null && nowMs < ticketUntilMs ? t : null;
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(COMMAND)
            .then(Commands.argument("id", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                String id = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "id");
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> {
                    Takeover t = offer;
                    if (t != null && !t.id().equals(id)) {
                        say(Component.translatable("chat.dungeontrain.live.takeover.gone").withStyle(ChatFormatting.GRAY));
                        return;
                    }
                    accept();
                });
                return 1;
            })));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        offer = null;
        accepting = false;
    }

    private static void say(Component line) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(line, false);
    }
}
