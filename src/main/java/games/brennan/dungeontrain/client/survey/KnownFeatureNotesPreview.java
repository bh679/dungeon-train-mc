package games.brennan.dungeontrain.client.survey;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.client.SurveyClientState;
import games.brennan.discordpresence.network.SurveyQuestionPayload;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.NarrativeDeathScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.commands.Commands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev builds only: {@code /dungeontrain-debug-known-notes} opens the death screen and shows every
 * {@link KnownFeature} note page in turn, then the {@code /feedback} card for each, saving a window
 * screenshot of each to {@code screenshots/}: Gate 2 evidence without any desktop capture.
 *
 * <p>Headless runs, where nobody can type the command, drop a {@code known-notes-preview.flag} file
 * into the game directory: the preview starts once a world has been joined for
 * {@link #FLAG_DELAY_TICKS} ticks, and the flag is deleted so it runs once. Same shape as
 * {@code LiveDeathPagesPreview}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class KnownFeatureNotesPreview {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks to let a page's fade settle before the shot. */
    private static final int SETTLE_TICKS = 50;
    private static final int FLAG_DELAY_TICKS = 200;
    private static final String FLAG_FILE = "known-notes-preview.flag";
    private static int joinedTicks;

    /** One shot: a death-screen page ({@code card} false) or the {@code /feedback} card. */
    private record Shot(KnownFeature feature, boolean card) {}

    /**
     * Stands in when the dev world's survey sent no questions, so the death screen has a question to
     * hang its note pages on. Cleared again once the preview ends.
     */
    private static final SurveyQuestionPayload.Entry STAND_IN = new SurveyQuestionPayload.Entry(
            "dungeontrain:change_one_thing", "If you could change one thing about the mod, what would it be?", 0, -1, true);
    private static boolean seededQuestions;

    private static List<Shot> queue = List.of();
    private static NarrativeDeathScreen deathScreen;
    private static Shot current;
    private static int ticks;
    private static String grabName;

    private KnownFeatureNotesPreview() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        if (!DungeonTrain.isDevBuild()) return;
        event.getDispatcher().register(Commands.literal("dungeontrain-debug-known-notes").executes(ctx -> {
            Minecraft.getInstance().execute(KnownFeatureNotesPreview::start);
            return 1;
        }));
    }

    private static void start() {
        List<Shot> shots = new ArrayList<>();
        for (KnownFeature f : KnownFeature.values()) shots.add(new Shot(f, false));
        for (KnownFeature f : KnownFeature.values()) shots.add(new Shot(f, true));
        queue = shots;
        seededQuestions = !SurveyClientState.hasQuestions();
        if (seededQuestions) SurveyClientState.set(List.of(STAND_IN));
        deathScreen = new NarrativeDeathScreen();
        current = null;
        ticks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (deathScreen == null) {
            pollFlag(mc);
            return;
        }
        if (++ticks < SETTLE_TICKS) return;
        ticks = 0;
        if (current != null) {
            grabName = "known-note-" + (current.card() ? "card-" : "page-") + current.feature().id() + ".png";
            return;
        }
        advance(mc);
    }

    private static void advance(Minecraft mc) {
        if (queue.isEmpty()) {
            deathScreen = null;
            mc.setScreen(null);
            if (seededQuestions) SurveyClientState.clear();
            seededQuestions = false;
            LOGGER.info("[DungeonTrain] known-notes preview done");
            return;
        }
        current = queue.get(0);
        queue = queue.subList(1, queue.size());
        if (current.card()) {
            mc.setScreen(new KnownFeatureNoteScreen(null, List.of(current.feature())));
        } else {
            if (mc.screen != deathScreen) {
                // The screen builds its pages when it opens: try this shot again next round.
                mc.setScreen(deathScreen);
                queue = prepend(current, queue);
                current = null;
                return;
            }
            if (!deathScreen.previewNote(current.feature())) {
                LOGGER.warn("[DungeonTrain] known-notes preview: no survey question to hang a note on");
                current = null;
            }
        }
    }

    private static List<Shot> prepend(Shot shot, List<Shot> rest) {
        List<Shot> out = new ArrayList<>(rest.size() + 1);
        out.add(shot);
        out.addAll(rest);
        return out;
    }

    private static void pollFlag(Minecraft mc) {
        if (!DungeonTrain.isDevBuild() || mc.player == null) { joinedTicks = 0; return; }
        if (++joinedTicks != FLAG_DELAY_TICKS) return;
        Path flag = mc.gameDirectory.toPath().resolve(FLAG_FILE);
        if (!Files.isRegularFile(flag)) return;
        try {
            Files.deleteIfExists(flag);
            LOGGER.info("[DungeonTrain] known-notes preview from flag");
            start();
        } catch (java.io.IOException e) {
            LOGGER.warn("[DungeonTrain] known-notes preview flag unreadable: {}", e.toString());
        }
    }

    /** Shots are taken after a full frame so the page is on the buffer, not mid-draw. */
    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Post event) {
        String name = grabName;
        if (name == null) return;
        grabName = null;
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(),
            msg -> LOGGER.info("[DungeonTrain] known-notes preview {} -> {}", name, msg.getString()));
        current = null;
    }
}
