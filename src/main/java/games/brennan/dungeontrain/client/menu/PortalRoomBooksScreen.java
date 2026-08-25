package games.brennan.dungeontrain.client.menu;

import games.brennan.dungeontrain.portal.PortalRoomBooks;
import games.brennan.dungeontrain.portal.PortalRoomSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The dials behind a library room's Books row: how its author is drawn, and which authors qualify.
 *
 * <h2>Why a drilldown rather than five more rows</h2>
 * <p>Five steppers inline would be five rows an author scrolls past on every room they edit, for a
 * setting most rooms never touch — and the Books row itself already says the useful part at a glance.
 * Everything here only means something once a room stocks an author at all, which is exactly the
 * condition the Edit button appears under.</p>
 *
 * <h2>The three shares</h2>
 * <p>Self, Player and Signature are the three ways to name an author, and the room rolls between them
 * once. Zero takes one out entirely, so {@code 1/0/0} is a room that always shows the reader their own
 * writing — which is why there is no separate setting for that.</p>
 *
 * <h2>The band</h2>
 * <p>Min keeps a room from locking to somebody with two books and standing nearly empty. Max is the
 * opposite request: a modest private collection wants a modest author, and without a ceiling it would
 * always draw the most prolific writer on the server. Max {@code 0} means no ceiling.</p>
 */
public final class PortalRoomBooksScreen implements MenuScreen {

    private final String modeTag;

    /** @param modeTag the room's whole settings tag, as the panel already carries it */
    public PortalRoomBooksScreen(String modeTag) {
        this.modeTag = modeTag;
    }

    @Override public String title() {
        return "Books — author mix";
    }

    @Override public List<CommandMenuEntry> entries() {
        PortalRoomBooks books = PortalRoomSettings.parse(modeTag).books();
        List<CommandMenuEntry> out = new ArrayList<>();
        for (PortalRoomBooks.Share share : PortalRoomBooks.Share.values()) {
            out.add(stepper(share.displayName(), commandFor(share),
                books.weightFor(share), "0-" + PortalRoomBooks.MAX_WEIGHT));
        }
        out.add(stepper("Min books", "booksmin", books.minBooks(),
            PortalRoomBooks.MIN_BOOK_BOUND + "-" + PortalRoomBooks.MAX_BOOK_BOUND));
        // Zero reads as "no ceiling", so it is spelled out rather than shown as a bare 0 the author
        // has to guess the meaning of.
        out.add(stepper(books.maxBooks() == PortalRoomBooks.NO_MAXIMUM ? "Max books (any)" : "Max books",
            "booksmax", books.maxBooks(),
            PortalRoomBooks.MIN_BOOK_BOUND + "-" + PortalRoomBooks.MAX_BOOK_BOUND));
        out.add(new CommandMenuEntry.Back("< Back"));
        return out;
    }

    /** The {@code /dt editor portals <token>} subcommand that edits one share's weight. */
    static String commandFor(PortalRoomBooks.Share share) {
        return "books" + share.id();
    }

    /** One {@code [-] Label: N [+]} row, the same geometry every other portal stepper uses. */
    private static CommandMenuEntry stepper(String label, String token, int value, String hint) {
        String prefix = "dungeontrain editor portals " + token;
        return new CommandMenuEntry.Triple(
            new CommandMenuEntry.Stay("-", prefix + " dec"),
            new CommandMenuEntry.TypeArg(label + ": " + value, hint, prefix),
            new CommandMenuEntry.Stay("+", prefix + " inc"),
            0.10, 0.90);
    }
}
