package games.brennan.dungeontrain.echo;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly tests for the encounter journal shipped to the relay against a landed Death Note curse.
 * The shape here is the contract the author's story book is rendered from, and the caps are what stop
 * a long fight from turning into a large upload.
 */
final class CursedEncounterPayloadTest {

    @Test
    @DisplayName("carries beats, gear, ending and duration in the shape the relay stores")
    void buildsTheJournal() {
        JsonObject out = CursedEncounterPayload.build(
            List.of("SPAWNED", "MET"), List.of("a netherite axe"), List.of("a golden apple"),
            EndReason.ECHO_SLAIN_BY_YOU, 61L);
        assertEquals(2, out.getAsJsonArray("beats").size());
        assertEquals("SPAWNED", out.getAsJsonArray("beats").get(0).getAsString());
        assertEquals("a netherite axe", out.getAsJsonArray("items").get(0).getAsString());
        assertEquals("a golden apple", out.getAsJsonArray("acquired").get(0).getAsString());
        assertEquals("ECHO_SLAIN_BY_YOU", out.get("end").getAsString());
        assertEquals(61L, out.get("sec").getAsLong());
    }

    @Test
    @DisplayName("blank entries are dropped, long descriptors clamped, and lists capped")
    void clampsAndCaps() {
        List<String> manyBeats = new ArrayList<>();
        for (int i = 0; i < 40; i++) manyBeats.add("BEAT_" + i);
        JsonObject out = CursedEncounterPayload.build(manyBeats,
            Arrays.asList(null, "  ", "x".repeat(500)), List.of(),
            EndReason.LEFT_BEHIND, -5L);
        assertEquals(CursedEncounterPayload.MAX_BEATS, out.getAsJsonArray("beats").size());
        assertEquals(1, out.getAsJsonArray("items").size(), "null + blank descriptors are dropped");
        assertEquals(CursedEncounterPayload.MAX_DESCRIPTOR_CHARS,
            out.getAsJsonArray("items").get(0).getAsString().length());
        assertEquals(0L, out.get("sec").getAsLong(), "a negative duration is never sent");
    }

    @Test
    @DisplayName("null lists and a null ending build an empty-but-valid journal, not a crash")
    void toleratesNothing() {
        JsonObject out = CursedEncounterPayload.build(null, null, null, null, 0L);
        assertTrue(out.getAsJsonArray("beats").isEmpty());
        assertEquals("", out.get("end").getAsString());
    }

    @Test
    @DisplayName("only a decisive ending claims an outcome; the rest ship a story with no verdict")
    void outcomeMapping() {
        assertEquals("target_killed_echo", CursedEncounterPayload.outcomeFor(EndReason.ECHO_SLAIN_BY_YOU));
        assertEquals("target_killed_echo", CursedEncounterPayload.outcomeFor(EndReason.ECHO_SLAIN));
        assertEquals("echo_killed_target", CursedEncounterPayload.outcomeFor(EndReason.YOU_SLAIN_BY_ECHO));
        // The curse neither succeeded nor was defeated — the book tells the story without an ending.
        assertNull(CursedEncounterPayload.outcomeFor(EndReason.YOU_DIED));
        assertNull(CursedEncounterPayload.outcomeFor(EndReason.LEFT_BEHIND));
        assertNull(CursedEncounterPayload.outcomeFor(null));
    }
}
