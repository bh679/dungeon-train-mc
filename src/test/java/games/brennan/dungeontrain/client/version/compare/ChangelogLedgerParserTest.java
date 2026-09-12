package games.brennan.dungeontrain.client.version.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangelogLedgerParserTest {

    private static final String LEDGER = """
            {"entries": [
              {"id": "a", "version": "0.850.0", "type": "feat", "tags": ["feature", "editor"],
               "title": "A", "summary": "Sa", "highlights": ["h1", ""], "pr": 1, "date": "2026-09-01",
               "released": true, "released_in": "v0.851.0", "released_at": "2026-09-01T00:00:00Z"},
              {"id": "b", "version": "0.851.0", "type": "fix", "tags": ["fix", "someday-tag"],
               "title": "B", "summary": "Sb", "highlights": [], "date": "2026-09-01",
               "released": true, "released_in": "v0.851.0", "released_at": "2026-09-01T00:00:00Z"},
              {"id": "c", "version": "0.852.0", "type": "feat", "tags": ["feature"],
               "title": "C", "summary": "Sc", "date": "2026-09-02",
               "released": false, "released_in": null, "released_at": null},
              {"id": "d", "version": "0.840.0", "type": "chore", "title": "Old", "summary": "no tags key",
               "date": "2026-08-01", "released": true, "released_in": "v0.841.0", "released_at": "x"},
              {"id": "e", "version": "not-a-version", "type": "feat", "tags": [],
               "title": "E", "summary": "", "date": "x", "released": true, "released_in": "v0.841.0"},
              "not an object"
            ]}
            """;

    private static FullSemver v(String s) {
        return FullSemver.parse(s).orElseThrow();
    }

    @Test
    @DisplayName("released entries are grouped by the release they shipped in; unreleased and malformed rows are dropped")
    void parse() {
        ChangelogLedger ledger = ChangelogLedgerParser.parse(LEDGER);
        assertEquals(3, ledger.size());
        assertTrue(ledger.hasRelease(v("0.851.0")));
        assertTrue(ledger.hasRelease(v("0.841.0")));
        assertFalse(ledger.hasRelease(v("0.852.0")), "unreleased entry must not appear");
        List<LedgerEntry> in851 = ledger.entriesReleasedIn(v("0.851.0"));
        assertEquals(List.of("a", "b"), in851.stream().map(LedgerEntry::id).toList());
        assertEquals(List.of(), ledger.entriesReleasedIn(v("0.1.0")));
    }

    @Test
    @DisplayName("tags the client does not know are ignored; a missing tags key is an empty set")
    void tags() {
        ChangelogLedger ledger = ChangelogLedgerParser.parse(LEDGER);
        List<LedgerEntry> in851 = ledger.entriesReleasedIn(v("0.851.0"));
        assertEquals(Set.of(ChangelogTag.FEATURE, ChangelogTag.EDITOR), in851.get(0).tags());
        assertEquals(Set.of(ChangelogTag.FIX), in851.get(1).tags());
        assertEquals(Set.of(), ledger.entriesReleasedIn(v("0.841.0")).get(0).tags());
        assertEquals(List.of("h1"), in851.get(0).highlights(), "blank highlights dropped");
        assertEquals(v("0.850.0"), in851.get(0).version());
    }

    @Test
    @DisplayName("a body that is not the ledger shape is an error, not an empty ledger")
    void shape() {
        assertThrows(IllegalArgumentException.class, () -> ChangelogLedgerParser.parse("[]"));
        assertThrows(IllegalArgumentException.class, () -> ChangelogLedgerParser.parse("{\"entries\": 3}"));
    }

    @Test
    @DisplayName("every tag id round-trips and matches the ledger's canonical spelling")
    void tagIds() {
        for (ChangelogTag tag : ChangelogTag.values()) {
            assertEquals(tag, ChangelogTag.fromJson(tag.jsonId()).orElseThrow());
            assertEquals(tag.jsonId(), tag.jsonId().toLowerCase());
        }
        assertTrue(ChangelogTag.fromJson("nope").isEmpty());
        assertTrue(ChangelogTag.fromJson(null).isEmpty());
        assertEquals(15, ChangelogTag.values().length);
    }
}
