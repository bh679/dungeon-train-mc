package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ChatClickActions#dispatch}: which clicks are DT's, and what the handler receives. */
class ChatClickActionsTest {

    @AfterEach
    void reset() {
        ChatClickActions.clear();
    }

    @Test
    @DisplayName("A registered action gets the segments after its name, on the scheduler")
    void dispatchesToHandler() {
        List<List<String>> got = new ArrayList<>();
        ChatClickActions.register("live-gift", got::add);
        List<Runnable> queued = new ArrayList<>();
        assertTrue(ChatClickActions.dispatch("dungeontrain:live-gift/accept/abc", queued::add));
        assertTrue(got.isEmpty(), "runs on the scheduler, not inline");
        queued.forEach(Runnable::run);
        assertEquals(List.of(List.of("accept", "abc")), got);
    }

    @Test
    @DisplayName("A DT click with no handler is swallowed, never handed back to vanilla")
    void unknownActionSwallowed() {
        List<Runnable> queued = new ArrayList<>();
        assertTrue(ChatClickActions.dispatch("dungeontrain:nothing-here/x", queued::add));
        assertTrue(ChatClickActions.dispatch("dungeontrain:", queued::add));
        assertTrue(queued.isEmpty());
    }

    @Test
    @DisplayName("Commands and other values are not DT clicks")
    void commandsPassThrough() {
        ChatClickActions.register("consent", args -> { });
        List<Runnable> queued = new ArrayList<>();
        assertFalse(ChatClickActions.dispatch("/give @s stone", queued::add));
        assertFalse(ChatClickActions.dispatch("/dungeontrain-consent", queued::add));
        assertFalse(ChatClickActions.dispatch(null, queued::add));
        assertTrue(queued.isEmpty());
    }

    @Test
    @DisplayName("A handler that throws is contained")
    void handlerExceptionContained() {
        ChatClickActions.register("boom", args -> { throw new IllegalStateException("x"); });
        List<Runnable> queued = new ArrayList<>();
        assertTrue(ChatClickActions.dispatch("dungeontrain:boom", queued::add));
        queued.forEach(Runnable::run);
    }
}
