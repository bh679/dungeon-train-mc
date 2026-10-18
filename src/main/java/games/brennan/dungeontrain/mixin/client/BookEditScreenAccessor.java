package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.font.TextFieldHelper;
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

    /**
     * Whether the screen is in its sign-and-title mode. Read by
     * {@link games.brennan.dungeontrain.client.BookFilterInfoEvents} to hide the content-filter info
     * button there, alongside Scribble's formatting column.
     */
    @Accessor("isSigning")
    boolean dungeontrain$isSigning();

    /** Vanilla's Sign and Close button — the Priority Book star sits beside it (PriorityBookSignToggle). */
    @Accessor("finalizeButton")
    net.minecraft.client.gui.components.Button dungeontrain$getFinalizeButton();

    // The Live Eye text filter (client/live/LiveTextCensorClient) rewrites the page or title being
    // typed: which page is open, the title, and the two cursors to park at the end afterwards.

    @Accessor("currentPage")
    int dungeontrain$getCurrentPage();

    @Accessor("title")
    String dungeontrain$getTitle();

    @Accessor("title")
    void dungeontrain$setTitle(String title);

    @Accessor("pageEdit")
    TextFieldHelper dungeontrain$getPageEdit();

    @Accessor("titleEdit")
    TextFieldHelper dungeontrain$getTitleEdit();
}
