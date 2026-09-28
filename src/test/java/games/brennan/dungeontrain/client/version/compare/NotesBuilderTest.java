package games.brennan.dungeontrain.client.version.compare;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesBuilderTest {

    private static FullSemver v(String s) {
        return FullSemver.parse(s).orElseThrow();
    }

    private static LedgerEntry entry(String id, String releasedIn, ChangelogTag... tags) {
        return new LedgerEntry(id, v(releasedIn), "feat", "Title " + id, "Summary " + id,
                List.of("bullet " + id), Set.of(tags), v(releasedIn));
    }

    /** 0.853.0 has two curated entries, 0.852.0 is a cascade tick with markdown only, 0.851.0 has one entry. */
    private static final List<ReleaseEntry> RELEASES = List.of(
            new ReleaseEntry(v("0.853.0"), "### 0.853.0\n\n**md 853**", ""),
            new ReleaseEntry(v("0.852.0"), "### 0.852.0\n\nBumped AIN", ""),
            new ReleaseEntry(v("0.851.0"), "### 0.851.0\n\n**md 851**", ""));

    private static final ChangelogLedger LEDGER = new ChangelogLedger(List.of(
            entry("e1", "0.853.0", ChangelogTag.FEATURE, ChangelogTag.EDITOR),
            entry("e2", "0.853.0", ChangelogTag.FIX),
            entry("e3", "0.851.0", ChangelogTag.FEATURE, ChangelogTag.UI)));

    private static List<String> titles(List<NotesSection> sections) {
        return sections.stream().map(s -> s.title().getString()).toList();
    }

    private static List<String> texts(NotesSection section) {
        return section.lines().stream().map(l -> l.text().getString()).toList();
    }

    @Test
    @DisplayName("without a ledger every release shows its markdown — the page's pre-tag behaviour")
    void noLedger() {
        List<NotesSection> sections = NotesBuilder.sections(RELEASES, null, Set.of());
        assertEquals(List.of("v0.853.0", "v0.852.0", "v0.851.0"), titles(sections));
        assertEquals(List.of("v0.853.0", "md 853"), texts(sections.get(0)));
        assertTrue(NotesBuilder.tagCounts(RELEASES, null).isEmpty());
    }

    @Test
    @DisplayName("with the ledger, curated releases render from it and a cascade tick keeps its markdown")
    void ledgerNoFilter() {
        List<NotesSection> sections = NotesBuilder.sections(RELEASES, LEDGER, Set.of());
        assertEquals(List.of("v0.853.0", "v0.852.0", "v0.851.0"), titles(sections));
        List<String> first = texts(sections.get(0));
        assertEquals("v0.853.0", first.get(0));
        assertEquals("Title e1", first.get(1));
        assertEquals("Summary e1", first.get(3), "tag line sits between title and summary");
        assertEquals("• bullet e1", first.get(4));
        assertEquals("Title e2", first.get(5));
        assertEquals(List.of("v0.852.0", "Bumped AIN"), texts(sections.get(1)));
    }

    @Test
    @DisplayName("a filter keeps matching entries only, drops releases with none, and hides markdown-only ticks")
    void filter() {
        List<NotesSection> editor = NotesBuilder.sections(RELEASES, LEDGER, Set.of(ChangelogTag.EDITOR));
        assertEquals(List.of("v0.853.0"), titles(editor));
        List<String> lines = texts(editor.get(0));
        assertTrue(lines.contains("Title e1"));
        assertTrue(!lines.contains("Title e2"), "non-matching entry in a kept release is dropped");

        List<NotesSection> either = NotesBuilder.sections(RELEASES, LEDGER,
                Set.of(ChangelogTag.EDITOR, ChangelogTag.UI));
        assertEquals(List.of("v0.853.0", "v0.851.0"), titles(either), "selection is OR across tags");

        assertTrue(NotesBuilder.sections(RELEASES, LEDGER, Set.of(ChangelogTag.MOBS)).isEmpty());
    }

    @Test
    @DisplayName("the fullscreen tab for a release the filter empties keeps its heading and says so")
    void notice() {
        NotesSection kept = NotesBuilder.sectionOrNotice(RELEASES.get(0), LEDGER, Set.of(ChangelogTag.EDITOR));
        assertTrue(texts(kept).contains("Title e1"));
        NotesSection emptied = NotesBuilder.sectionOrNotice(RELEASES.get(0), LEDGER, Set.of(ChangelogTag.MOBS));
        assertEquals("v0.853.0", emptied.title().getString());
        assertEquals(2, emptied.lines().size(), "heading + notice line");
        assertEquals(ChangelogLines.COLOUR_MUTED, emptied.lines().get(1).colour());
        // A markdown-only tick under a filter gets the same notice rather than vanishing.
        assertEquals(2, NotesBuilder.sectionOrNotice(RELEASES.get(1), LEDGER, Set.of(ChangelogTag.FIX)).lines().size());
    }

    @Test
    @DisplayName("tag counts cover the releases given, and omit tags with nothing")
    void counts() {
        Map<ChangelogTag, Integer> counts = NotesBuilder.tagCounts(RELEASES, LEDGER);
        assertEquals(2, counts.get(ChangelogTag.FEATURE));
        assertEquals(1, counts.get(ChangelogTag.EDITOR));
        assertEquals(1, counts.get(ChangelogTag.FIX));
        assertEquals(1, counts.get(ChangelogTag.UI));
        assertTrue(!counts.containsKey(ChangelogTag.MOBS));
        assertEquals(Map.of(ChangelogTag.FEATURE, 1, ChangelogTag.UI, 1),
                NotesBuilder.tagCounts(RELEASES.subList(2, 3), LEDGER));
    }

    @Test
    @DisplayName("chips run most to least, ties in taxonomy order, zero-count tags left out")
    void chipOrder() {
        Map<ChangelogTag, Integer> counts = new java.util.EnumMap<>(ChangelogTag.class);
        counts.put(ChangelogTag.UI, 2);
        counts.put(ChangelogTag.FEATURE, 5);
        counts.put(ChangelogTag.EDITOR, 2);
        counts.put(ChangelogTag.MOBS, 0);
        assertEquals(List.of(ChangelogTag.FEATURE, ChangelogTag.EDITOR, ChangelogTag.UI), TagFilterBar.byCount(counts));
    }

    @Test
    @DisplayName("toggling a chip flips that tag; the All chip clears")
    void toggle() {
        Set<ChangelogTag> one = TagFilterBar.toggle(Set.of(), ChangelogTag.FIX);
        assertEquals(Set.of(ChangelogTag.FIX), one);
        Set<ChangelogTag> two = TagFilterBar.toggle(one, ChangelogTag.UI);
        assertEquals(Set.of(ChangelogTag.FIX, ChangelogTag.UI), two);
        assertEquals(Set.of(ChangelogTag.UI), TagFilterBar.toggle(two, ChangelogTag.FIX));
        assertEquals(Set.of(), TagFilterBar.toggle(one, ChangelogTag.FIX));
        assertEquals(Set.of(), TagFilterBar.toggle(two, null));
        assertEquals(Set.of(ChangelogTag.FIX), one, "inputs are never mutated");
    }
}
