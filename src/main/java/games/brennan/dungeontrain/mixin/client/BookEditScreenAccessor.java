package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.inventory.BookEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Read-only accessor for {@link BookEditScreen}'s private {@code pages} list — the live text the
 * player has typed, which the screen holds in a field and only hands to the server on a save.
 *
 * <p>The lectern-letter close handler
 * ({@link games.brennan.dungeontrain.client.LetterLecternClientEvents}) needs it because vanilla's
 * <b>Done</b> button runs {@code setScreen(null)} <em>before</em> {@code saveChanges(false)}, so the
 * screen is already closing when we have to tell the server what to park on the lectern — and the
 * edit packet that follows is dispatched asynchronously
 * ({@code ServerGamePacketListenerImpl#handleEditBook} → {@code thenAcceptAsync}), so its arrival
 * cannot be ordered against ours. Reading the pages here lets the draft packet carry the text itself
 * instead of racing vanilla for it.</p>
 */
@Mixin(BookEditScreen.class)
public interface BookEditScreenAccessor {

    @Accessor("pages")
    List<String> dungeontrain$getPages();
}
