package games.brennan.dungeontrain.logrequest;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
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
 * spec, and ships it as a bug report labelled {@link LogRequestManager#UPLOAD_LABEL}.</p>
 *
 * <p>Livestream requests come from {@code LiveChatBridge}: a relay chat line flagged as a Developer
 * log request for this player is handed to {@link #onLiveRequest}, which asks the server to start
 * the same flow (deduped by the relay's request id).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LogRequestClient {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** The buttons' action ({@link ChatClick}): {@code logs/send|decline/<request id>}. */
    static final String CLICK_ACTION = "logs";
    private static final int SEEN_LIVE_CAP = 64;

    /** The request awaiting an answer, or -1. Client thread only. */
    private static int pendingId = -1;
    private static final Set<String> SEEN_LIVE = new LinkedHashSet<>();

    private LogRequestClient() {}

    /** The server is asking for this player's logs. Client thread. */
    public static void onPrompt(int id) {
        if (!ClientDisplayConfig.isDevChatEnabled()) {
            answer(id, Answer.BLOCKED);
            return;
        }
        pendingId = id;
        Minecraft.getInstance().gui.getChat().addMessage(promptLine(id));
    }

    /** A livestream-chat log request aimed at this client. Deduped by the relay's id. Client thread. */
    public static void onLiveRequest(String reqId) {
        if (reqId == null || reqId.isBlank() || !SEEN_LIVE.add(reqId)) return;
        if (SEEN_LIVE.size() > SEEN_LIVE_CAP) SEEN_LIVE.remove(SEEN_LIVE.iterator().next());
        if (Minecraft.getInstance().getConnection() == null) return;
        DungeonTrainNet.sendToServer(new LogRequestFromLivePacket());
    }

    /** Wire the [Send logs] / [No thanks] buttons ({@code DungeonTrainClient.clientSetup}). */
    public static void registerClicks() {
        ChatClickActions.register(CLICK_ACTION, args -> {
            if (args.size() < 2) return;
            int id;
            try {
                id = Integer.parseInt(args.get(1));
            } catch (NumberFormatException ex) {
                return;
            }
            if (id < 1) return;
            switch (args.get(0)) {
                case "send" -> respond(id, true);
                case "decline" -> respond(id, false);
                default -> { }
            }
        });
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
    }

    private static int respond(int id, boolean send) {
        Minecraft mc = Minecraft.getInstance();
        if (id != pendingId) {
            mc.gui.getChat().addMessage(Component.translatable("chat.dungeontrain.log_request.expired")
                .withStyle(ChatFormatting.GRAY));
            return 0;
        }
        pendingId = -1;
        answer(id, send ? Answer.SEND : Answer.DECLINE);
        if (!send) {
            mc.gui.getChat().addMessage(Component.translatable("chat.dungeontrain.log_request.declined")
                .withStyle(ChatFormatting.GRAY));
            return 1;
        }
        mc.gui.getChat().addMessage(Component.translatable("chat.dungeontrain.log_request.sent")
            .withStyle(ChatFormatting.GRAY));
        upload(SystemSpecCollector.collect()); // the spec reads GL state: render thread, here
        return 1;
    }

    private static void upload(String spec) {
        LogCollector.collectAsync().thenAccept(files -> {
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

    private static Component promptLine(int id) {
        MutableComponent line = Component.translatable("chat.dungeontrain.dev_message.prefix").withStyle(ChatFormatting.AQUA)
            .append(Component.translatable("chat.dungeontrain.log_request.prompt").withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" "));
        line.append(button("chat.dungeontrain.log_request.send", ChatClick.event(CLICK_ACTION, "send", Integer.toString(id)), ChatFormatting.GREEN));
        line.append(Component.literal(" "));
        line.append(button("chat.dungeontrain.log_request.decline", ChatClick.event(CLICK_ACTION, "decline", Integer.toString(id)), ChatFormatting.GRAY));
        return line;
    }

    private static Component button(String key, ClickEvent click, ChatFormatting colour) {
        return Component.literal("[").append(Component.translatable(key)).append("]")
            .withStyle(s -> s.withColor(colour).withBold(true)
                .withClickEvent(click)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(key + ".tip"))));
    }
}
