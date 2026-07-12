package games.brennan.dungeontrain.client;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.LetterDraftToLecternPacket;
import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Client-side close detection for the lectern-letter sign screen. When a {@link BookEditScreen} that
 * was opened from a lectern (via {@link LetterEditorClient}) closes WITHOUT signing — Esc or the
 * "Done" draft-save — this tells the server to leave the unsigned book &amp; quill on that lectern as
 * a "Letter X" draft ({@link LetterDraftToLecternPacket}). A signed close sends nothing: the server's
 * sign interception has already consumed + burned the book.
 *
 * <p>The sign-vs-close distinction is set by
 * {@link games.brennan.dungeontrain.mixin.client.BookEditScreenSignMixin} at
 * {@code saveChanges(true)} time (before this close event fires), so it is race-free against the
 * server's async sign handling.</p>
 */
public final class LetterLecternClientEvents {

    private LetterLecternClientEvents() {}

    public static void onScreenClosing(net.minecraft.client.gui.screens.Screen screen) {
        if (!(screen instanceof BookEditScreen)) return;
        BlockPos pos = LetterEditorClient.onEditScreenClosing();
        if (pos != null) {
            DungeonTrainNet.sendToServer(new LetterDraftToLecternPacket(pos));
        }
    }
}
