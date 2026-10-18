package games.brennan.dungeontrain.net.relay;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of the relay's answers to a Priority Book spend — the pure half of
 * {@link PriorityBookResponses}. An upload that never asked, an older relay that sends no
 * {@code priority} block, and a network failure must all say nothing.
 */
class PriorityBookResponsesTest {

    private static final String HEX = "069a79f444e94726a5befca90e38aaf5";
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final String ASKED = "{\"uuid\":\"" + HEX + "\",\"title\":\"T\",\"priority\":true}";
    private static final String NOT_ASKED = "{\"uuid\":\"" + HEX + "\",\"title\":\"T\"}";

    @Test
    void aSignTimeSpendNamesThePlayerTheBookAndWhatIsLeft() {
        PriorityBookResponses.Outcome o = PriorityBookResponses.parseSubmit(ASKED, 200,
                "{\"ok\":true,\"id\":12,\"flag\":\"approved\",\"priority\":{\"spent\":true,\"balance\":2,\"held\":false}}");
        assertNotNull(o);
        assertEquals(PLAYER, o.player());
        assertEquals(12, o.bookId());
        assertTrue(o.spent());
        assertFalse(o.held());
        assertEquals(2L, o.balance());
    }

    @Test
    void aBookStillUnderReviewIsHeld() {
        PriorityBookResponses.Outcome o = PriorityBookResponses.parseSubmit(ASKED, 200,
                "{\"ok\":true,\"id\":12,\"flag\":\"pending\",\"priority\":{\"spent\":true,\"balance\":0,\"held\":true}}");
        assertTrue(o.held());
    }

    @Test
    void anUnspentAnswerIsReportedAsUnused() {
        PriorityBookResponses.Outcome o = PriorityBookResponses.parseSubmit(ASKED, 200,
                "{\"ok\":true,\"id\":12,\"priority\":{\"spent\":false,\"balance\":0,\"error\":\"no_credits\"}}");
        assertFalse(o.spent());
    }

    @Test
    void nothingIsSaidWhenTheUploadNeverAskedOrTheRelayIsOlder() {
        assertNull(PriorityBookResponses.parseSubmit(NOT_ASKED, 200,
                "{\"ok\":true,\"id\":12,\"priority\":{\"spent\":true,\"balance\":1}}"));
        assertNull(PriorityBookResponses.parseSubmit(ASKED, 200, "{\"ok\":true,\"id\":12,\"flag\":\"approved\"}"));
        assertNull(PriorityBookResponses.parseSubmit(ASKED, -1, null));
        assertNull(PriorityBookResponses.parseSubmit(ASKED, 409, "{\"error\":\"duplicate_book\"}"));
    }

    @Test
    void aVotePageSpendReadsTheBalanceAndTreatsARepeatAsDone() {
        String req = "{\"uuid\":\"" + HEX + "\",\"name\":\"Steve\",\"id\":\"7\"}";
        PriorityBookResponses.Outcome ok = PriorityBookResponses.parseSpend(req, 200, "{\"ok\":true,\"id\":7,\"balance\":1}");
        assertTrue(ok.spent());
        assertEquals(7, ok.bookId());
        assertEquals(1L, ok.balance());
        PriorityBookResponses.Outcome again = PriorityBookResponses.parseSpend(req, 409, "{\"error\":\"already_priority\"}");
        assertTrue(again.spent(), "an outbox re-send of a spend that landed");
        assertFalse(PriorityBookResponses.parseSpend(req, 402, "{\"error\":\"no_credits\"}").spent());
        assertNull(PriorityBookResponses.parseSpend(req, -1, null));
    }
}
