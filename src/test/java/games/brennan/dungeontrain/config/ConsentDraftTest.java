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
        draft.touch(false);
        draft.commit(true);          // Adult default on, but the player turned it off
        draft.commit(true);          // next showing, no click -> default
        assertEquals(List.of(false, true), written);
    }

    @Test
    void resetDropsAClickMadeUnderAnotherMode() {
        List<Boolean> written = new ArrayList<>();
        ConsentDraft draft = new ConsentDraft(written::add);
        draft.touch(true);           // clicked on under Adult
        draft.reset();               // switched to Kid, card rebuilt its pills
        draft.commit(false);         // Kid default off
        assertEquals(List.of(false), written);
        assertFalse(draft.isTouched());
    }
}
