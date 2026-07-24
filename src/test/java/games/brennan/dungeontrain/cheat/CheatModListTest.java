package games.brennan.dungeontrain.cheat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validation, baked ∪ relay merge, and JSON round-trip for {@link CheatModList}. No live
 * {@code ModList} / config dir needed — the relay overlay is injected via the test seam.
 */
class CheatModListTest {

    @AfterEach
    void reset() {
        CheatModList.setRelayForTest(null);
    }

    @Test
    @DisplayName("sanitize lowercases, trims, and drops malformed IDs")
    void sanitizeDropsJunk() {
        Set<String> clean = CheatModList.sanitize(List.of(
            "XRay",            // uppercased -> xray
            "  freecam  ",     // padded -> freecam
            "ok_mod-1.2",      // valid charset
            "bad id!",         // space + '!' -> dropped
            "",                // empty -> dropped
            "with space"));    // dropped

        assertTrue(clean.contains("xray"));
        assertTrue(clean.contains("freecam"));
        assertTrue(clean.contains("ok_mod-1.2"));
        assertFalse(clean.contains("bad id!"));
        assertEquals(3, clean.size());
    }

    @Test
    @DisplayName("isValidModId accepts [a-z0-9_.-], rejects spaces/symbols/overlong")
    void validModId() {
        assertTrue(CheatModList.isValidModId("xray"));
        assertTrue(CheatModList.isValidModId("Some-Mod_1.2"));   // lowercased internally
        assertFalse(CheatModList.isValidModId("bad id"));
        assertFalse(CheatModList.isValidModId("nope!"));
        assertFalse(CheatModList.isValidModId(""));
        assertFalse(CheatModList.isValidModId(null));
        assertFalse(CheatModList.isValidModId("x".repeat(65)));
    }

    @Test
    @DisplayName("effective() merges baked with the relay overlay")
    void effectiveMerges() {
        // A baked entry is always present.
        assertTrue(CheatModList.effective().contains("xray"));

        CheatModList.setRelayForTest(List.of("mycustomcheat"));
        Set<String> eff = CheatModList.effective();
        assertTrue(eff.contains("mycustomcheat"), "relay id present");
        assertTrue(eff.contains("xray"), "baked id still present");
    }

    @Test
    @DisplayName("toJson -> parse round-trips a mod-ID set")
    void jsonRoundTrip() {
        Set<String> ids = Set.of("xray", "freecam", "baritone");
        Set<String> back = CheatModList.parse(CheatModList.toJson(ids));
        assertEquals(ids, back);
    }

    @Test
    @DisplayName("parse tolerates malformed bodies and drops junk entries")
    void parseIsDefensive() {
        assertTrue(CheatModList.parse("not json").isEmpty());
        assertTrue(CheatModList.parse("{}").isEmpty());
        assertTrue(CheatModList.parse("{\"mods\":\"nope\"}").isEmpty());
        assertEquals(Set.of("xray"),
            CheatModList.parse("{\"ok\":true,\"mods\":[\"XRay\",\"bad id!\",123]}"));
    }
}
