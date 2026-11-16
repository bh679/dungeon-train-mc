package games.brennan.dungeontrain.client.survey;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * When to show the {@link KnownFeatureNoteScreen}: right after a {@code /feedback} answer that earns
 * a note, over the survey, which it returns to when closed. If that screen goes away first (the last
 * question), it waits until the player is back in the game or on the title screen. Same timing as
 * {@code ModOfTheYearPopupHandler}. The death screen shows these notes as pages instead, so it never
 * calls this.
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class KnownFeatureNoteHandler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** ~0.5 s after the answer is submitted — long enough to see it land. */
    private static final int ANSWER_DELAY_TICKS = 10;
    /** ~1.2 s once the player is free again, so the note doesn't land on the first frame back. */
    private static final int FREE_DELAY_TICKS = 24;

    /** The notes waiting to be shown; empty when nothing is scheduled. */
    private static List<KnownFeature> pendingNotes = List.of();
    /** The note lost its screen; show it once the player is free (see {@link #playerIsFree}). */
    private static boolean showWhenFree = false;
    /** Ticks until a scheduled open; {@code -1} when nothing is scheduled. */
    private static int openDelayRemaining = -1;
    /** The screen the scheduled note will sit over; the open is put off if the player has moved on. */
    private static Screen pendingParent = null;

    private KnownFeatureNoteHandler() {}

    /** A {@code /feedback} answer. {@code score} is the chosen rating, or -1 for a text question. Client thread. */
    public static void onAnswered(String formId, int score, String comment) {
        List<KnownFeature> notes = KnownFeatureNotes.forAnswer(formId, score, comment);
        if (notes.isEmpty()) return;
        LOGGER.info("KnownFeatureNote: {} — showing the note", notes);
        pendingNotes = notes;
        schedule(Minecraft.getInstance().screen, ANSWER_DELAY_TICKS);
    }

    @SubscribeEvent
    public static void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (showWhenFree && openDelayRemaining < 0 && playerIsFree(mc)) {
            showWhenFree = false;
            schedule(mc.screen, FREE_DELAY_TICKS);
        }
        if (openDelayRemaining < 0) return;
        if (mc.getOverlay() != null) return;
        if (mc.screen != pendingParent) {
            LOGGER.info("KnownFeatureNote: screen changed during open delay; showing later");
            showWhenFree = true;
            openDelayRemaining = -1;
            pendingParent = null;
            return;
        }
        if (--openDelayRemaining > 0) return;
        openDelayRemaining = -1;
        Screen parent = pendingParent;
        pendingParent = null;
        List<KnownFeature> notes = pendingNotes;
        pendingNotes = List.of();
        if (!notes.isEmpty()) mc.setScreen(new KnownFeatureNoteScreen(parent, notes));
    }

    private static void schedule(Screen parent, int delayTicks) {
        pendingParent = parent;
        openDelayRemaining = delayTicks;
    }

    /** In the game with no screen up, or on the title screen. */
    private static boolean playerIsFree(Minecraft mc) {
        if (mc.screen instanceof TitleScreen) return true;
        return mc.screen == null && mc.player != null && mc.player.isAlive();
    }
}
