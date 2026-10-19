package games.brennan.dungeontrain.mixin.client;

import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessors for two private {@link BookViewScreen} fields DT cannot otherwise reach.
 *
 * <ul>
 *   <li>{@code currentPage}, so the book-read telemetry
 *       ({@link games.brennan.dungeontrain.client.BookReadClientEvents}) can sample which page the
 *       player is on each client tick, and so
 *       {@link games.brennan.dungeontrain.client.ClientActivityReporter} can renew a reader's
 *       presence on each new page;</li>
 *   <li>{@code backButton}, so the vote page
 *       ({@link games.brennan.dungeontrain.client.BookVoteClientEvents}) can find out where the book
 *       is <em>actually</em> being drawn — see that class's {@code bookTop()};</li>
 *   <li>{@code bookAccess}, so the supporters' Copy button
 *       ({@link games.brennan.dungeontrain.client.BookCopyButton}) copies the pages as shown.</li>
 * </ul>
 *
 * <p>Applies to {@code LecternScreen} too (it extends {@code BookViewScreen}), though the telemetry
 * only tracks held-book reads and the vote page is held-book only as well.</p>
 */
@Mixin(BookViewScreen.class)
public interface BookViewScreenAccessor {

    @Accessor("currentPage")
    int dungeontrain$getCurrentPage();

    /**
     * The back page-turn button. Vanilla nails it to a constant y in
     * {@code createPageControlButtons()}, which makes it a reliable probe for any mod that has moved
     * the book — see {@code BookVoteClientEvents.VANILLA_PAGE_BUTTON_Y}.
     */
    @Accessor("backButton")
    PageButton dungeontrain$getBackButton();

    /** The pages the screen is showing, including any DT has appended (the vote page). */
    @Accessor("bookAccess")
    BookViewScreen.BookAccess dungeontrain$getBookAccess();
}
