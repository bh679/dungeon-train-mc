package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.mixin.client.BookEditScreenAccessor;
import games.brennan.dungeontrain.net.LetterDraftToLecternPacket;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;

/**
 * Client-side close detection for the lectern-letter sign screen. When a {@link BookEditScreen} that
 * was opened from a lectern (via {@link LetterEditorClient}) closes WITHOUT signing — Esc or the
 * "Done" draft-save — this tells the server to leave the unsigned book &amp; quill on that lectern as
 * an unsigned draft ({@link LetterDraftToLecternPacket}). A signed close sends nothing: the server's
 * sign interception has already consumed + burned the book.
 *
 * <p>The sign-vs-close distinction is set by
 * {@link games.brennan.dungeontrain.mixin.client.BookEditScreenSignMixin} at
 * {@code saveChanges(true)} time (before this close event fires), so it is race-free against the
 * server's async sign handling.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID, value = Dist.CLIENT)
public final class LetterLecternClientEvents {

    private LetterLecternClientEvents() {}

    @SubscribeEvent
    public static void onScreenClosing(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof BookEditScreen screen)) return;
        BlockPos pos = LetterEditorClient.onEditScreenClosing();
        if (pos == null) return;

        // Send the text along with the lectern. Vanilla's Done button closes the screen BEFORE
        // calling saveChanges(false), and the server applies that edit asynchronously, so we cannot
        // order our packet against it — the draft has to carry its own pages or the writing is lost.
        List<String> pages = List.copyOf(((BookEditScreenAccessor) screen).dungeontrain$getPages());
        DungeonTrainNet.sendToServer(new LetterDraftToLecternPacket(pos, pages));
    }
}
