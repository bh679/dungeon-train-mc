package games.brennan.dungeontrain.client.bugresponse;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.client.SurveyScreen;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.BugLogReporter;
import games.brennan.dungeontrain.client.ClientLanguage;
import games.brennan.dungeontrain.client.version.compare.Platform;
import games.brennan.dungeontrain.client.version.compare.VersionCompareScreen;
import games.brennan.dungeontrain.client.version.compare.VersionCompareState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Answers a bug report sent from Discord Presence's on-demand survey ({@code /bug}, {@code /feedback})
 * in chat, the way the death screen answers one with {@link BugResponseCard}. The survey submit hook
 * ({@code SurveySubmitClientHook}, wired in {@code DungeonTrainClient}) hands the answer to
 * {@link #onSurveySubmit}; the response is posted once the survey has closed and the modpack listings
 * and changelog have arrived (or {@link #SETTLE_TIMEOUT_TICKS} has passed, so an offline report still
 * gets an answer, with fewer facts). The death screen never fires that hook, so it never doubles up.
 *
 * <p>The links in the response run {@code /dt-bug-response}, a client-only command: it never reaches
 * the server, so it works on any server and never touches run integrity.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class BugResponseChatNotifier {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Longest wait for listings after the survey closes before answering with what has arrived. */
    static final int SETTLE_TIMEOUT_TICKS = 200;

    /** The survey's comment field; DP's hook does not pass the comment, so it is read off the screen. */
    private static final String COMMENT_FIELD = "commentBox";

    private record Pending(BugIssue issue, boolean multiplayer) {}

    /** Render thread only. */
    private static Pending pending;
    private static int waitedTicks;
    private static boolean commentFieldWarned;

    private BugResponseChatNotifier() {}

    /**
     * Survey submit hook: queue a chat response when {@code e} is the bug-report question answered
     * with a real bug (anything but "No").
     */
    public static void onSurveySubmit(SurveyQuestionPayload.Entry e, int score) {
        if (e == null || !BugLogReporter.BUG_REPORT_ID.equals(e.id())) return;
        if (score < 0 || score >= e.options().size()) return;
        String option = e.options().get(score);
        if (option.equalsIgnoreCase("No")) return;

        Minecraft mc = Minecraft.getInstance();
        BugIssue issue = BugIssueClassifier.classify(option, surveyComment(mc));
        // Same rule as the death screen: our own world (LAN host included) is singleplayer.
        boolean multiplayer = mc.getSingleplayerServer() == null;
        VersionCompareState.ensureFetched();
        pending = new Pending(issue, multiplayer);
        waitedTicks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (pending == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            pending = null; // left the world before it could be answered
            return;
        }
        if (mc.screen instanceof SurveyScreen) return; // answer once the player is back in the game
        if (!settled() && ++waitedTicks < SETTLE_TIMEOUT_TICKS) return;

        Pending p = pending;
        pending = null;
        try {
            post(mc, p);
        } catch (Exception ex) {
            LOGGER.warn("[DungeonTrain] Could not post the bug report response: {}", ex.toString());
        }
    }

    private static boolean settled() {
        return VersionCompareState.status(Platform.MODRINTH) != VersionCompareState.Status.LOADING
                && VersionCompareState.status(Platform.CURSEFORGE) != VersionCompareState.Status.LOADING
                && VersionCompareState.ledgerStatus() != VersionCompareState.Status.LOADING;
    }

    private static void post(Minecraft mc, Pending p) {
        BugResponse.Result r = BugResponseCard.decideNow(p.issue(), p.multiplayer());
        List<BugResponseChat.TipLine> tips = r.kind() == BugResponse.Kind.LAG_TIPS
                ? BugResponseChat.tipLines(LagTips.applicable(null))
                : List.of();
        for (Component line : BugResponseChat.lines(r, tips, ClientLanguage.selected())) {
            mc.player.displayClientMessage(line, false);
        }
    }

    /** The comment typed into the open survey, or "" when it cannot be read. */
    private static String surveyComment(Minecraft mc) {
        if (!(mc.screen instanceof SurveyScreen screen)) return "";
        try {
            Field f = SurveyScreen.class.getDeclaredField(COMMENT_FIELD);
            f.setAccessible(true);
            return f.get(screen) instanceof EditBox box ? box.getValue() : "";
        } catch (ReflectiveOperationException | RuntimeException ex) {
            if (!commentFieldWarned) {
                commentFieldWarned = true;
                LOGGER.warn("[DungeonTrain] Survey comment unreadable ({}); bug reports from /bug and /feedback "
                        + "are classified by their option only", ex.toString());
            }
            return "";
        }
    }

    // ---- /dt-bug-response ----

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(BugResponseChat.COMMAND)
                .then(Commands.literal("changes").executes(ctx -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.tell(() -> mc.setScreen(new VersionCompareScreen(null)));
                    return 1;
                }))
                .then(Commands.literal("tip")
                        .then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
                            String id = StringArgumentType.getString(ctx, "id");
                            // Re-checked at click time: a tip that no longer applies does nothing.
                            Minecraft.getInstance().tell(() -> runTip(id));
                            return 1;
                        }))));
    }

    private static void runTip(String id) {
        for (LagTips.Tip tip : LagTips.applicable(null)) {
            if (tip.id().equals(id) && tip.action() != null) {
                tip.action().run();
                return;
            }
        }
    }
}
