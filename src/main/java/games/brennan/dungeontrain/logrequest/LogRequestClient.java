package games.brennan.dungeontrain.logrequest;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.client.ChatClickTokens;
import games.brennan.dungeontrain.client.LogCollector;
import games.brennan.dungeontrain.client.SystemSpecCollector;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.logrequest.LogRequestManager.Answer;
import games.brennan.dungeontrain.net.BugReportLogsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.LogRequestAnswerPacket;
import games.brennan.dungeontrain.net.LogRequestFromLivePacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Client side of the Developer log request (see {@link LogRequestManager}).
 *
 * <p>A request arrives as {@code LogRequestPromptPacket}. With Developer chat switched off the client
 * answers BLOCKED without showing anything (Brennan hears why). Otherwise it prints the developer line
 * with clickable <b>[Send logs]</b> / <b>[No thanks]</b> (chat-click actions, {@link ChatClick}, so
 * there is no command to type); typing {@code @Dev} while a request is waiting also sends. On
 * send it collects the same capped, home-redacted log set as the {@code /bug} survey plus the system
 * spec, and ships it as a bug report labelled {@link LogRequestManager#UPLOAD_LABEL}. A
 * {@code /bug full} request says so in its line and sends the whole session's logs instead.</p>
 *
 * <p>Livestream requests come from {@code LiveChatBridge}: a relay chat line flagged as a Developer
 * log request for this player is handed to {@link #onLiveRequest}, which asks the server to start
 * the same flow (deduped by the relay's request id).</p>
 *
 * <p>Any sender can print a {@code dungeontrain:} click, and the request id is the server's. So the buttons carry
 * a {@link ChatClickTokens} token minted when this client printed the line, good for
 * {@link LogRequestManager#ANSWER_WINDOW_MS}: a copy from anyone else sends nothing. Typing {@code @Dev} needs none.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LogRequestClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The buttons' action ({@link ChatClick}): {@code logs/send|decline/<request id>/<token>}. */
    static final String CLICK_ACTION = "logs";
    /** The buttons' {@link ChatClickTokens} scope. */
    static final String TOKEN_SCOPE = CLICK_ACTION;
    private static final int SEEN_LIVE_CAP = 64;

    /** The request awaiting an answer, or -1. Client thread only. */
    private static int pendingId = -1;
    /** Whether {@link #pendingId} is a {@code /bug full}. Client thread only. */
    private static boolean pendingFull;
    private static final Set<String> SEEN_LIVE = new LinkedHashSet<>();

    private LogRequestClient() {}

    /** The server is asking for this player's logs. Client thread. */
    public static void onPrompt(int id, boolean full) {
        if (!ClientDisplayConfig.isDevChatEnabled()) {
            answer(id, Answer.BLOCKED);
            return;
        }
        pendingId = id;
        pendingFull = full;
        ChatClickTokens.clear(TOKEN_SCOPE); // an earlier request's buttons stop working
        Minecraft.getInstance().gui.getChat().addMessage(promptLine(id, full));
    }

    /** A livestream-chat log request aimed at this client. Deduped by the relay's id. Client thread. */
    public static void onLiveRequest(String reqId, boolean full) {
        if (reqId == null || reqId.isBlank() || !SEEN_LIVE.add(reqId)) return;
        if (SEEN_LIVE.size() > SEEN_LIVE_CAP) SEEN_LIVE.remove(SEEN_LIVE.iterator().next());
        if (Minecraft.getInstance().getConnection() == null) return;
        DungeonTrainNet.sendToServer(new LogRequestFromLivePacket(full));
    }

    /** Wire the [Send logs] / [No thanks] buttons ({@code DungeonTrainClient.clientSetup}). */
    public static void registerClicks() {
        ChatClickActions.register(CLICK_ACTION, args -> {
            int id = idOf(args);
            if (id < 1) return;
            boolean send = "send".equals(args.get(0));
            if (!send && !"decline".equals(args.get(0))) return;
            // The token before anything else: a refused click leaves the request waiting, unanswered.
            if (!clickAllowed(id, pendingId, args.get(2))) {
                expired();
                return;
            }
            respond(id, send);
        });
    }

    /** {@code [step, id, token]} → the id, or -1 for any other shape (an older click among them). Pure. */
    static int idOf(List<String> args) {
        if (args.size() != 3 || args.get(2).isEmpty()) return -1;
        try {
            int id = Integer.parseInt(args.get(1));
            return id >= 1 ? id : -1;
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * Whether a click on request {@code id}'s buttons may answer it: {@code id} is still the waiting request and
     * {@code token} is the one its line was printed with. Uses the token up. Changes nothing else.
     */
    static boolean clickAllowed(int id, int pending, @Nullable String token) {
        return id >= 1 && id == pending && ChatClickTokens.consume(TOKEN_SCOPE, id, token);
    }

    /** Typing {@code @Dev} while a request waits sends the logs. The line itself still goes out. */
    @SubscribeEvent
    public static void onClientChat(ClientChatEvent event) {
        if (pendingId < 0) return;
        String text = event.getMessage().toLowerCase(Locale.ROOT);
        if (DungeonTrain.MENTION_TOKENS.stream().anyMatch(text::contains)) {
            respond(pendingId, true);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        pendingId = -1;
        pendingFull = false;
        ChatClickTokens.clear(TOKEN_SCOPE);
    }

    private static void expired() {
        Minecraft.getInstance().gui.getChat().addMessage(Component.translatable("chat.dungeontrain.log_request.expired")
            .withStyle(ChatFormatting.GRAY));
    }

    private static int respond(int id, boolean send) {
        Minecraft mc = Minecraft.getInstance();
        if (id != pendingId) {
            expired();
            return 0;
        }
        pendingId = -1;
        boolean full = pendingFull;
        pendingFull = false;
        answer(id, send ? Answer.SEND : Answer.DECLINE);
        if (!send) {
            mc.gui.getChat().addMessage(Component.translatable("chat.dungeontrain.log_request.declined")
                .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        mc.gui.getChat().addMessage(Component.translatable("chat.dungeontrain.log_request.sent")
            .withStyle(ChatFormatting.GRAY));
        upload(SystemSpecCollector.collect(), full); // the spec reads GL state: render thread, here
        return 1;
    }

    private static void upload(String spec, boolean full) {
        LogCollector.collectAsync(full).thenAccept(files -> {
            List<BugReportLogsPacket.LogBlob> blobs = files == null ? List.of() : files;
            Minecraft.getInstance().execute(() -> {
                try {
                    DungeonTrainNet.sendToServer(new BugReportLogsPacket(LogRequestManager.UPLOAD_LABEL, spec, blobs));
                } catch (Exception ex) {
                    LOGGER.warn("[DungeonTrain] Failed to send requested logs: {}", ex.toString());
                }
            });
        });
    }

    private static void answer(int id, Answer answer) {
        try {
            DungeonTrainNet.sendToServer(new LogRequestAnswerPacket(id, answer));
        } catch (Exception ex) {
            LOGGER.warn("[DungeonTrain] Failed to answer log request: {}", ex.toString());
        }
    }

    static Component promptLine(int id, boolean full) {
        String token = ChatClickTokens.mint(TOKEN_SCOPE, id, LogRequestManager.ANSWER_WINDOW_MS);
        String prompt = full ? "chat.dungeontrain.log_request.prompt_full" : "chat.dungeontrain.log_request.prompt";
        MutableComponent line = Component.translatable("chat.dungeontrain.dev_message.prefix").withStyle(ChatFormatting.AQUA)
            .append(Component.translatable(prompt).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" "));
        line.append(button("chat.dungeontrain.log_request.send", ChatClick.event(CLICK_ACTION, "send", Integer.toString(id), token), ChatFormatting.GREEN));
        line.append(Component.literal(" "));
        line.append(button("chat.dungeontrain.log_request.decline", ChatClick.event(CLICK_ACTION, "decline", Integer.toString(id), token), ChatFormatting.GRAY));
        return line;
    }

    private static Component button(String key, ClickEvent click, ChatFormatting colour) {
        return Component.literal("[").append(Component.translatable(key)).append("]")
            .withStyle(s -> s.withColor(colour).withBold(true)
                .withClickEvent(click)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(key + ".tip"))));
    }
}
