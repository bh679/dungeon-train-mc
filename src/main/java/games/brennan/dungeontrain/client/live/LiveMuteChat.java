package games.brennan.dungeontrain.client.live;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.config.LiveViewerClientConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The viewer's ways to silence a stream on the TVs ({@link LiveViewerMutes}): a Live chat line with
 * [Mute stream] [Mute name] buttons the first time a broadcast is heard, a confirmation with [Undo] after
 * either, and the hidden {@code /livemute} client command ({@code stream}, {@code streamer}, {@code list},
 * {@code unmute [name]}), which acts on the stream heard last ({@link LiveTvSpeakers#heard}).
 *
 * <p>The buttons are {@link ChatClick} actions, never commands. A stream's playlist URL cannot ride in a
 * click (it holds slashes), so each announced stream gets a small number here instead.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LiveMuteChat {

    static final String ACTION = "live-mute";
    static final String COMMAND = "livemute";
    private static final String KEY = "chat.dungeontrain.live_mute.";

    /** Streams already announced, by playlist URL. */
    private static final Set<String> ANNOUNCED = ConcurrentHashMap.newKeySet();
    /** Click id → stream playlist URL. */
    private static final Map<String, String> STREAM_IDS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    private LiveMuteChat() {}

    /** A speaker started playing {@code h}: announce the stream once, with its mute buttons. */
    static void heard(LiveFeedSource.Hearing h) {
        if (!ANNOUNCED.add(h.streamKey()) || h.replay()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        String id = idFor(h.streamKey());
        MutableComponent line = (h.streamer() != null
                ? Component.translatable(KEY + "hearing", h.streamer())
                : Component.translatable(KEY + "hearing_unknown"))
            .append(" ").append(button(Component.translatable(KEY + "button.stream"),
                Component.translatable(KEY + "button.stream.hover"), "stream", id));
        if (LiveViewerMutes.validName(h.streamer())) {
            line.append(" ").append(button(Component.translatable(KEY + "button.streamer", h.streamer()),
                Component.translatable(KEY + "button.streamer.hover", h.streamer()), "streamer", h.streamer()));
        }
        mc.player.displayClientMessage(LiveChatRules.systemLine(line), false);
    }

    private static String idFor(String streamKey) {
        String id = Integer.toString(NEXT_ID.incrementAndGet());
        STREAM_IDS.put(id, streamKey);
        return id;
    }

    private static Component button(Component label, Component hover, String... args) {
        String[] segments = new String[args.length + 1];
        segments[0] = ACTION;
        System.arraycopy(args, 0, segments, 1, args.length);
        return label.copy().withStyle(style -> style.withColor(ChatFormatting.GOLD).withBold(true)
            .withClickEvent(ChatClick.event(segments))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }

    // ---- actions ---------------------------------------------------------------------------------

    /** Wire the chat buttons ({@code LiveFeedClientInit}). */
    public static void registerClicks() {
        ChatClickActions.register(ACTION, args -> {
            if (args.size() < 2) return;
            String arg = args.get(1);
            switch (args.get(0)) {
                case "stream" -> { String k = STREAM_IDS.get(arg); if (k != null) muteStream(k); }
                case "unstream" -> { String k = STREAM_IDS.get(arg); if (k != null) unmuteStream(k); }
                case "streamer" -> { if (LiveViewerMutes.validName(arg)) muteStreamer(arg); }
                case "unstreamer" -> { if (LiveViewerMutes.validName(arg)) unmuteStreamer(arg); }
                default -> { }
            }
        });
    }

    private static void muteStream(String streamKey) {
        LiveViewerMutes.muteStream(streamKey);
        say(Component.translatable(KEY + "stream_muted").append(" ")
            .append(button(Component.translatable(KEY + "button.undo"), Component.translatable(KEY + "button.undo.hover"),
                "unstream", idFor(streamKey))));
    }

    private static void unmuteStream(String streamKey) {
        LiveViewerMutes.unmuteStream(streamKey);
        say(Component.translatable(KEY + "stream_unmuted"));
    }

    private static void muteStreamer(String name) {
        LiveViewerMutes.muteStreamer(name);
        say(Component.translatable(KEY + "streamer_muted", name).append(" ")
            .append(button(Component.translatable(KEY + "button.undo"), Component.translatable(KEY + "button.undo.hover"),
                "unstreamer", name)));
    }

    /** False when {@code name} was not muted. */
    private static boolean unmuteStreamer(String name) {
        for (LiveViewerMutes.Muted m : LiveViewerClientConfig.mutedStreamers()) {
            if (m.is(name, LiveViewerMutes.uuidOf(name))) {
                LiveViewerMutes.unmuteStreamer(m);
                say(Component.translatable(KEY + "unmuted", m.name()));
                return true;
            }
        }
        return false;
    }

    private static void say(Component body) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(LiveChatRules.systemLine(body), false);
    }

    // ---- /livemute -------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(COMMAND)
            .executes(ctx -> streamCommand())
            .then(Commands.literal("stream").executes(ctx -> streamCommand()))
            .then(Commands.literal("streamer").executes(ctx -> streamerCommand()))
            .then(Commands.literal("list").executes(ctx -> listCommand()))
            .then(Commands.literal("unmute")
                .executes(ctx -> unmuteHeardCommand())
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> unmuteNameCommand(StringArgumentType.getString(ctx, "name"))))));
    }

    private static int streamCommand() {
        LiveFeedSource.Hearing h = LiveTvSpeakers.heard();
        if (h == null) { say(Component.translatable(KEY + "nothing")); return 0; }
        muteStream(h.streamKey());
        return 1;
    }

    private static int streamerCommand() {
        LiveFeedSource.Hearing h = LiveTvSpeakers.heard();
        if (h == null || !LiveViewerMutes.validName(h.streamer())) { say(Component.translatable(KEY + "nothing")); return 0; }
        muteStreamer(h.streamer());
        return 1;
    }

    private static int listCommand() {
        var muted = LiveViewerClientConfig.mutedStreamers();
        say(muted.isEmpty() ? Component.translatable(KEY + "list_empty")
            : Component.translatable(KEY + "list", muted.stream().map(LiveViewerMutes.Muted::name).collect(Collectors.joining(", "))));
        return muted.size();
    }

    private static int unmuteHeardCommand() {
        LiveFeedSource.Hearing h = LiveTvSpeakers.heard();
        if (h != null && LiveViewerMutes.streamMuted(h.streamKey())) { unmuteStream(h.streamKey()); return 1; }
        if (h != null && h.streamer() != null && unmuteStreamer(h.streamer())) return 1;
        say(Component.translatable(KEY + "nothing"));
        return 0;
    }

    private static int unmuteNameCommand(@Nullable String name) {
        if (name != null && unmuteStreamer(name)) return 1;
        say(Component.translatable(KEY + "not_muted", name));
        return 0;
    }

    /** Leaving a world: stream mutes and announcements last one session. */
    static void reset() {
        ANNOUNCED.clear();
        STREAM_IDS.clear();
        LiveViewerMutes.clearSession();
    }
}
