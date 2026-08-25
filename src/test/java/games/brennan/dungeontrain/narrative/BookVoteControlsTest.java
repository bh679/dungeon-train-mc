package games.brennan.dungeontrain.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which controls the train's vote page offers, as a pure state → controls mapping.
 *
 * <p>The page itself needs a screen to test, but the decision behind it does not — and the decision
 * is the part with teeth. Two of these three assertions exist because getting them wrong is a real
 * problem rather than a cosmetic one:</p>
 *
 * <ul>
 *   <li><b>No thumbs on your own book.</b> The relay weights which books get served by player votes
 *       and never checks authorship, so a shelf of your own writing plus a thumbs-up is a
 *       self-upvoting machine. This is the surface that closes it.</li>
 *   <li><b>No report on your own book.</b> Reporting yourself is nonsense; the slot carries the
 *       author's own controls instead.</li>
 * </ul>
 *
 * <p>Mirrors the branching in {@code BookVoteClientEvents.renderAction} and its click handler. If
 * those two ever disagree with this, the invisible-hitbox bug is back: a control that is not drawn
 * but is still clickable.</p>
 */
class BookVoteControlsTest {

    /** The page shows the thumbs only on a book somebody else wrote. */
    private static boolean showsThumbs(BookModerationState s) {
        return !s.isOwn();
    }

    /** Report is for other people's books; your own carry protest / withdraw instead. */
    private static boolean showsReport(BookModerationState s) {
        return !s.isOwn();
    }

    /** Protest stands where report stands, on a book of yours the train has JUDGED and held back. */
    private static boolean showsProtest(BookModerationState s) {
        return s.canProtest();
    }

    /** Withdraw stands there on a book of yours that IS out on the line. */
    private static boolean showsPrivate(BookModerationState s) {
        return s.isOwn() && !s.isWithheld();
    }

    @Test
    @DisplayName("Somebody else's book: the train's question, the thumbs, and report")
    void strangersBook() {
        BookModerationState s = BookModerationState.PUBLIC;
        assertTrue(showsThumbs(s));
        assertTrue(showsReport(s));
        assertFalse(showsProtest(s));
        assertFalse(showsPrivate(s));
    }

    @Test
    @DisplayName("Your own released book: no thumbs, no report — withdraw instead")
    void ownReleasedBook() {
        BookModerationState s = BookModerationState.APPROVED;
        assertFalse(showsThumbs(s), "voting on your own book is the self-upvote hole");
        assertFalse(showsReport(s), "reporting your own book is nonsense");
        assertFalse(showsProtest(s), "nothing to protest — it is out on the line");
        assertTrue(showsPrivate(s));
    }

    @Test
    @DisplayName("Your own JUDGED-and-withheld books: no thumbs, no report, no withdraw — protest instead")
    void ownJudgedWithheldBooks() {
        for (BookModerationState s : new BookModerationState[] {
                BookModerationState.UNDECIDED, BookModerationState.DISLIKED}) {
            assertFalse(showsThumbs(s), s + " must not be votable");
            assertFalse(showsReport(s), s + " must not be reportable");
            assertTrue(showsProtest(s), s + " is what protest is for");
            assertFalse(showsPrivate(s),
                s + " is already out of circulation — withdrawing it would be a control that does nothing");
        }
    }

    @Test
    @DisplayName("A book nothing has read yet offers NO action — there is no verdict to argue with")
    void unreadBookOffersNothing() {
        BookModerationState s = BookModerationState.READING;
        assertTrue(s.isWithheld());
        assertFalse(showsThumbs(s));
        assertFalse(showsReport(s));
        assertFalse(showsProtest(s), "protesting a decision nobody has made is an argument with nobody");
        assertFalse(showsPrivate(s));
    }

    @Test
    @DisplayName("The action slot never holds more than one control")
    void theActionSlotHoldsAtMostOneControl() {
        // They share one row and one hitbox, so two at once would be a click landing on whichever
        // branch happened to be tested first. Zero is legitimate (see READING above).
        for (BookModerationState s : BookModerationState.values()) {
            int offered = (showsReport(s) ? 1 : 0) + (showsProtest(s) ? 1 : 0) + (showsPrivate(s) ? 1 : 0);
            assertTrue(offered <= 1, s + " must offer at most one action, not " + offered);
            org.junit.jupiter.api.Assertions.assertEquals(
                s == BookModerationState.READING ? 0 : 1, offered,
                s + " offered " + offered + " actions");
        }
    }
}
