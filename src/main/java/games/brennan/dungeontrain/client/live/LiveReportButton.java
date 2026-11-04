package games.brennan.dungeontrain.client.live;

import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.net.relay.LiveFeedClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The {@code [Report streamer]} button on the Live chat's "connected" and "now chatting in Stream N" lines
 * ({@link LiveChatBridge}). A {@link ChatClick} action, {@code live-report[/<n>]}: the handler runs the server's
 * {@code /livereport} (or {@code /livereport stream <n>} for the room the reader is in), which picks the stream,
 * asks for a reason and talks to the relay. Shown only while someone other than the reader is on air.
 */
public final class LiveReportButton {

    /** The button's action ({@link ChatClick}). */
    static final String CLICK_ACTION = "live-report";
    private static final String KEY = "command.dungeontrain.live_report.";

    private LiveReportButton() {}

    /** Wire the button ({@code DungeonTrainClient.clientSetup}). */
    public static void registerClicks() {
        ChatClickActions.register(CLICK_ACTION, args -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getConnection() == null) return;
            mc.getConnection().sendCommand(command(args));
        });
    }

    /** The server command a click runs: {@code livereport}, or {@code livereport stream <n>} for a valid n. Pure. */
    static String command(List<String> args) {
        if (!args.isEmpty() && ("1".equals(args.get(0)) || "2".equals(args.get(0)))) {
            return "livereport stream " + args.get(0);
        }
        return "livereport";
    }

    /**
     * Whether the button shows: a live status, and a reader who is not the only one streaming. A streamer
     * with a second stream up may report the other one. Pure.
     */
    static boolean shows(LiveFeedClient.Status status, LiveChatRoom.Role role) {
        if (!status.live()) return false;
        return role != LiveChatRoom.Role.STREAMER || status.second() != null;
    }

    /**
     * Which stream a click names: the reader's room when two are up, none otherwise (the server then takes
     * the one on air). A streamer's room is their own stream, so they name none. Pure.
     */
    @Nullable
    static Integer streamOf(@Nullable LiveChatRoom.Pick pick, LiveChatRoom.Role role) {
        return pick == null || role == LiveChatRoom.Role.STREAMER ? null : pick.stream();
    }

    /** {@code line} with the button on the end, when it {@link #shows}. */
    static Component append(Component line, LiveFeedClient.Status status, LiveChatRoom.Role role,
                            @Nullable LiveChatRoom.Pick pick) {
        if (!shows(status, role)) return line;
        Integer stream = streamOf(pick, role);
        MutableComponent out = line.copy().append(Component.literal(" "));
        return out.append(Component.literal("[").append(Component.translatable(KEY + "chat_button")).append("]")
            .withStyle(s -> s.withColor(ChatFormatting.RED).withBold(true)
                .withClickEvent(stream == null ? ChatClick.event(CLICK_ACTION) : ChatClick.event(CLICK_ACTION, stream.toString()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(KEY + "chat_button.tip")))));
    }
}
