package games.brennan.dungeontrain.client.chat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class MenuChatMergeTest {

    private static ChatHistory.Message msg(String id, String content) {
        return new ChatHistory.Message(id, "", "Dungeon Train", false, false, content,
            List.of(), List.of(), "2026-11-03T00:00:00Z", false, false, false);
    }

    @Test
    void nothingOnEitherSideKeepsTheHistoryAsIs() {
        assertNull(MenuChatMerge.merge(null, null));
        assertNull(MenuChatMerge.merge(null, new ChatInbox(null, 0, List.of())));
        ChatHistory empty = new ChatHistory("t1", List.of(), false);
        assertSame(empty, MenuChatMerge.merge(empty, null));
    }

    @Test
    void noThreadButDrainedNotesShowsTheNotes() {
        ChatInbox inbox = new ChatInbox(null, 1, List.of(msg("mod-approved-sodiumextras", "approved")));
        ChatHistory out = MenuChatMerge.merge(null, inbox);
        assertEquals(1, out.messages().size());
        assertEquals("mod-approved-sodiumextras", out.messages().get(0).id());
        assertNull(out.threadId());
        assertFalse(out.hasMore());
    }

    @Test
    void drainedRowsAlreadyInHistoryAreNotDuplicated() {
        ChatHistory history = new ChatHistory("t1", List.of(msg("a", "hi"), msg("b", "reply")), true);
        ChatInbox inbox = new ChatInbox("t1", 2, List.of(msg("b", "reply"), msg("c", "note")));
        ChatHistory out = MenuChatMerge.merge(history, inbox);
        assertEquals(List.of("a", "b", "c"), out.messages().stream().map(ChatHistory.Message::id).toList());
        assertEquals("t1", out.threadId());
        assertEquals(true, out.hasMore());
    }

    @Test
    void historyWithoutAnInboxIsUnchanged() {
        ChatHistory history = new ChatHistory("t1", List.of(msg("a", "hi")), false);
        ChatHistory out = MenuChatMerge.merge(history, null);
        assertEquals(List.of("a"), out.messages().stream().map(ChatHistory.Message::id).toList());
    }
}
