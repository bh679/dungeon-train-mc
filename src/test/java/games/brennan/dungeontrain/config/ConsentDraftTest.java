package games.brennan.dungeontrain.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsentDraftTest {

    @Test
    void untouchedTakesTheModeDefault() {
        assertTrue(ConsentDraft.resolve(null, true));
        assertFalse(ConsentDraft.resolve(null, false));
    }

    @Test
    void aClickBeatsTheDefaultEitherWay() {
        assertFalse(ConsentDraft.resolve(false, true));
        assertTrue(ConsentDraft.resolve(true, false));
    }

    @Test
    void commitWritesOnceAndForgetsTheClick() {
        List<Boolean> written = new ArrayList<>();
        ConsentDraft draft = new ConsentDraft(written::add);
        draft.touch(ContentMode.ADULT, false);
        draft.commit(ContentMode.ADULT, true);   // Adult default on, but the player turned it off
        draft.commit(ContentMode.ADULT, true);   // next showing, no report -> fallback
        assertEquals(List.of(false, true), written);
    }

    @Test
    void aReportUnderAnotherModeIsIgnored() {
        List<Boolean> written = new ArrayList<>();
        ConsentDraft draft = new ConsentDraft(written::add);
        draft.touch(ContentMode.ADULT, true);    // clicked on under Adult
        draft.commit(ContentMode.KID, false);    // confirmed Kid: its own fallback, off
        assertEquals(List.of(false), written);
    }

    @Test
    void aCardRebuildDoesNotLoseTheClick() {
        // DP rebuilds the card (re-asking DT for its bullets) after every pill click; nothing resets.
        List<Boolean> written = new ArrayList<>();
        ConsentDraft draft = new ConsentDraft(written::add);
        draft.touch(ContentMode.ADULT, false);
        draft.commit(ContentMode.ADULT, true);
        assertEquals(List.of(false), written);
    }
}
