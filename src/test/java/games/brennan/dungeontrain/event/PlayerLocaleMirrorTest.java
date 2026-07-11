package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link PlayerLocaleMirror}'s UUID-based seed core — the fail-open language store that
 * backs language stamping of player-written content. Exercises set/get, the blank/null → unknown
 * cleaning, and {@link PlayerLocaleMirror#forget}.
 */
final class PlayerLocaleMirrorTest {

    @Test
    @DisplayName("a synced language is returned for that player")
    void setThenGet() {
        UUID id = UUID.randomUUID();
        PlayerLocaleMirror.set(id, "de_de");
        assertEquals("de_de", PlayerLocaleMirror.get(id));
        PlayerLocaleMirror.forget(id);
    }

    @Test
    @DisplayName("an unknown player reads null (fail-open — never blocks an upload)")
    void unknownIsNull() {
        assertNull(PlayerLocaleMirror.get(UUID.randomUUID()));
    }

    @Test
    @DisplayName("a blank or null language is treated as unknown (stored as null, not an empty tag)")
    void blankBecomesUnknown() {
        UUID id = UUID.randomUUID();
        PlayerLocaleMirror.set(id, "en_us");
        PlayerLocaleMirror.set(id, "   ");
        assertNull(PlayerLocaleMirror.get(id), "blank language should clear to unknown");
        PlayerLocaleMirror.set(id, "en_us");
        PlayerLocaleMirror.set(id, null);
        assertNull(PlayerLocaleMirror.get(id), "null language should clear to unknown");
    }

    @Test
    @DisplayName("a surrounding-whitespace language is trimmed")
    void trims() {
        UUID id = UUID.randomUUID();
        PlayerLocaleMirror.set(id, "  fr_fr  ");
        assertEquals("fr_fr", PlayerLocaleMirror.get(id));
        PlayerLocaleMirror.forget(id);
    }

    @Test
    @DisplayName("forget drops a player's language")
    void forgetClears() {
        UUID id = UUID.randomUUID();
        PlayerLocaleMirror.set(id, "es_es");
        PlayerLocaleMirror.forget(id);
        assertNull(PlayerLocaleMirror.get(id));
    }
}
