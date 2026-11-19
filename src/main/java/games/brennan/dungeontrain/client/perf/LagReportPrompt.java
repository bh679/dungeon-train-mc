package games.brennan.dungeontrain.client.perf;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.chat.ChatClick;
import games.brennan.dungeontrain.client.ChatClickActions;
import games.brennan.dungeontrain.client.ChatClickTokens;
import games.brennan.dungeontrain.client.LogCollector;
import games.brennan.dungeontrain.client.SystemSpecCollector;
import games.brennan.dungeontrain.net.BugReportLogsPacket;
import games.brennan.dungeontrain.net.DungeonTrainNet;
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
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.List;

/**
 * "Lag detected - send log to developer? [Yes] [No]": the chat line {@link ClientStallWatchdog}
 * prints once the render thread comes back from a freeze it dumped.
 *
 * <p>The dump is in {@code latest.log} by then, but a log on the player's disk helps nobody. Until
 * this, a freeze only reached Brennan if the player later died and picked "Lag" on the death screen.
 * [Yes] sends exactly what that answer sends ({@code BugLogReporter}): the capped, home-redacted log
 * set from {@link LogCollector} plus the {@link SystemSpecCollector} summary, as a "Lag"
 * {@link BugReportLogsPacket}. The spec's first line names the freeze, so a prompted report can be
 * told from a death-screen one. The server side ({@code BugReportSink}) is unchanged.</p>
 *
 * <p>It asks sparingly ({@link #shouldPrompt}): only for a freeze that began in a world the player
 * has been in for {@link #JOIN_GRACE_MS} (ten minutes: joining hitches are the norm, not news), at most once per
 * {@link #PROMPT_COOLDOWN_MS}, and never again this session after a [No]. The buttons are
 * {@link ChatClick} actions, so there is no command to type. Old builds are never asked
 * ({@link LagReportVersionGate}).</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LagReportPrompt {

    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * The buttons' action ({@link ChatClick}): {@code lag-log/send|decline/<prompt id>/<token>}. Any sender can print
     * a {@code dungeontrain:} click and the prompt id is a small count, so the buttons carry a {@link ChatClickTokens}
     * token minted when this client printed the line: a copy from anyone else sends nothing.
     */
    static final String CLICK_ACTION = "lag-log";
    /** The buttons' {@link ChatClickTokens} scope. */
    static final String TOKEN_SCOPE = CLICK_ACTION;
    /** How long the buttons work. */
    static final long TOKEN_TTL_MS = 10L * 60L * 1000L;
    /** No prompt until the player has been in the world this long: a short visit is not asked about. */
    static final long JOIN_GRACE_MS = 10 * 60_000;
    /** At most one prompt this often. */
    static final long PROMPT_COOLDOWN_MS = 30 * 60_000;
    /** {@code lastPromptAt} / {@code joinedAt} before the first. */
    static final long NEVER = -1;
    /** The upload's label; "Lag" so the server folds the spec in, as for a death-screen Lag answer. */
    private static final String UPLOAD_LABEL = "Lag";
    /** Headless seam ({@code -PlagReportClick=send|decline}, dev builds): answer the prompt at once. */
    private static final String AUTO_CLICK_PROPERTY = "dungeontrain.lagReportClick";

    // Client thread only.
    private static long joinedAt = NEVER;
    private static long lastPromptAt = NEVER;
    private static boolean declined;
    private static int nextId = 1;
    /** The prompt whose buttons still work, or -1. */
    private static int pendingId = -1;
    private static long pendingFreezeMs;

    private LagReportPrompt() {}

    /**
     * Whether a freeze that just ended may be offered for sending: it began in a world
     * ({@code freezeStartedAt} is when, {@code joinedAt} when that world was joined, {@link #NEVER}
     * if it was not), at least {@link #JOIN_GRACE_MS} after joining; nothing was asked in the last
     * {@link #PROMPT_COOLDOWN_MS}; and the player has not said no this session. Pure, unit-tested.
     */
    static boolean shouldPrompt(boolean startedInWorld, long freezeStartedAt, long joinedAt,
                                long lastPromptAt, boolean declined, long now) {
        if (declined || !startedInWorld || joinedAt == NEVER) return false;
        if (freezeStartedAt - joinedAt < JOIN_GRACE_MS) return false;
        return lastPromptAt == NEVER || now - lastPromptAt >= PROMPT_COOLDOWN_MS;
    }

    /**
     * The render thread is back after a dumped freeze of {@code freezeMs} that began at
     * {@code freezeStartedAt} (wall clock). Called from the watchdog's thread; hops to the client.
     */
    static void onFreezeRecovered(long freezeMs, long freezeStartedAt, boolean startedInWorld) {
        Minecraft.getInstance().execute(() -> maybePrompt(freezeMs, freezeStartedAt, startedInWorld));
    }

    private static void maybePrompt(long freezeMs, long freezeStartedAt, boolean startedInWorld) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (mc.player == null || mc.getConnection() == null) return;
        if (!LagReportVersionGate.enabled()) return;
        if (!shouldPrompt(startedInWorld, freezeStartedAt, joinedAt, lastPromptAt, declined, now)) return;
        lastPromptAt = now;
        pendingId = nextId++;
        pendingFreezeMs = freezeMs;
        ChatClickTokens.clear(TOKEN_SCOPE); // an earlier prompt's buttons stop working
        mc.gui.getChat().addMessage(promptLine(pendingId));
        String autoClick = DungeonTrain.isDevBuild() ? System.getProperty(AUTO_CLICK_PROPERTY) : null;
        if (autoClick != null) {
            LOGGER.info("[DungeonTrain] -PlagReportClick seam: answering the lag prompt with {}", autoClick);
            respond(pendingId, "send".equals(autoClick));
        }
    }

    /** Wire the [Yes] / [No] buttons ({@code DungeonTrainClient.clientSetup}). */
    public static void registerClicks() {
        ChatClickActions.register(CLICK_ACTION, args -> {
            int id = idOf(args);
            if (id < 1) return;
            boolean send = "send".equals(args.get(0));
            if (!send && !"decline".equals(args.get(0))) return;
            // The token before anything else: a refused click leaves the prompt as it was.
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
     * Whether a click on prompt {@code id}'s buttons may answer it: still the open prompt, and {@code token} is the
     * one its line was printed with. Uses the token up. Changes nothing else.
     */
    static boolean clickAllowed(int id, int pending, @Nullable String token) {
        return id >= 1 && id == pending && ChatClickTokens.consume(TOKEN_SCOPE, id, token);
    }

    private static void expired() {
        Minecraft.getInstance().gui.getChat().addMessage(Component.translatable("chat.dungeontrain.lag_report.expired")
            .withStyle(ChatFormatting.GRAY));
    }

    @SubscribeEvent
    public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        joinedAt = System.currentTimeMillis();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        joinedAt = NEVER;
        pendingId = -1;
        ChatClickTokens.clear(TOKEN_SCOPE);
    }

    private static void respond(int id, boolean send) {
        Minecraft mc = Minecraft.getInstance();
        if (id < 1 || id != pendingId) {
            mc.gui.getChat().addMessage(Component.translatable("chat.dungeontrain.lag_report.expired")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        pendingId = -1;
        if (!send) {
            declined = true;
            mc.gui.getChat().addMessage(Component.translatable("chat.dungeontrain.lag_report.declined")
                .withStyle(ChatFormatting.GRAY));
            return;
        }
        mc.gui.getChat().addMessage(Component.translatable("chat.dungeontrain.lag_report.sent")
            .withStyle(ChatFormatting.GRAY));
        // The spec reads GL state: render thread, here.
        upload("Detected client freeze: ~" + pendingFreezeMs + " ms\n" + SystemSpecCollector.collect());
    }

    private static void upload(String spec) {
        LogCollector.collectAsync().thenAccept(files -> {
            List<BugReportLogsPacket.LogBlob> blobs = files == null ? List.of() : files;
            Minecraft.getInstance().execute(() -> {
                try {
                    DungeonTrainNet.sendToServer(new BugReportLogsPacket(UPLOAD_LABEL, spec, blobs));
                    LOGGER.info("[DungeonTrain] Lag report sent ({} log files)", blobs.size());
                } catch (Exception ex) {
                    LOGGER.warn("[DungeonTrain] Failed to send lag report: {}", ex.toString());
                }
            });
        });
    }

    static Component promptLine(int id) {
        String token = ChatClickTokens.mint(TOKEN_SCOPE, id, TOKEN_TTL_MS);
        MutableComponent line = Component.translatable("chat.dungeontrain.lag_report.prompt")
            .withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(" "));
        line.append(button("chat.dungeontrain.lag_report.send", ChatClick.event(CLICK_ACTION, "send", Integer.toString(id), token), ChatFormatting.GREEN));
        line.append(Component.literal(" "));
        line.append(button("chat.dungeontrain.lag_report.decline", ChatClick.event(CLICK_ACTION, "decline", Integer.toString(id), token), ChatFormatting.GRAY));
        return line;
    }

    private static Component button(String key, ClickEvent click, ChatFormatting colour) {
        return Component.literal("[").append(Component.translatable(key)).append("]")
            .withStyle(s -> s.withColor(colour).withBold(true)
                .withClickEvent(click)
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(key + ".tip"))));
    }
}
