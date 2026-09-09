package games.brennan.dungeontrain.client.localization;

import games.brennan.dungeontrain.client.localization.edit.TranslationCoverageClient.Credit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import games.brennan.dungeontrain.client.localization.edit.TranslatorRenames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merging the build-time translator list with the relay's live one. Pure — no relay, no
 * ResourceManager, no running client.
 */
class TranslationCreditsMergeTest {

    private static TranslationContributor baked(String name, String locale, int done, int total) {
        return new TranslationContributor(name, Optional.of("https://example.test/" + name),
            List.of(new TranslationContributor.LanguageShare(locale, done, total)));
    }

    private static TranslationContributor find(List<TranslationContributor> all, String name) {
        return all.stream().filter((c) -> c.name().equals(name)).findFirst().orElse(null);
    }

    private static List<String> names(List<TranslationContributor> all) {
        return all.stream().map(TranslationContributor::name).toList();
    }

    @Test
    @DisplayName("somebody only the relay knows about is added, with a percentage")
    void relayOnlyPersonIsAdded() {
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(), Map.of("de_de", List.of(new Credit("Ada", 120))), (l) -> 1200);
        assertEquals(1, out.size());
        TranslationContributor ada = out.get(0);
        assertEquals("Ada", ada.name());
        assertEquals("de_de", ada.languages().get(0).locale());
        assertEquals(0.1, ada.languages().get(0).fraction(), 1e-9);
    }

    @Test
    @DisplayName("somebody in both lists is one person, and the baked share wins")
    void overlappingPersonIsNotDuplicated() {
        // The baked entry counts authorship, not just approvals. Letting the relay's smaller number
        // overwrite it would shrink a translator's credit every time their language was released.
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Ada", "de_de", 900, 1200)),
            Map.of("de_de", List.of(new Credit("Ada", 12))), (l) -> 1200);
        assertEquals(1, out.size(), "one person, one line");
        assertEquals(900, find(out, "Ada").languages().get(0).contributed());
        assertTrue(find(out, "Ada").url().isPresent(), "and keeps their link");
    }

    @Test
    @DisplayName("a second language from the relay joins an existing person")
    void newLanguageJoinsExistingPerson() {
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Ada", "de_de", 900, 1200)),
            Map.of("fr_fr", List.of(new Credit("Ada", 30))), (l) -> 1200);
        assertEquals(1, out.size());
        assertEquals(2, find(out, "Ada").languages().size());
        assertTrue(find(out, "Ada").url().isPresent(), "the link survives the rewrite");
    }

    @Test
    @DisplayName("a relay-only translator who did the most work ranks first, above the baked list")
    void biggestContributionRanksFirst() {
        // The shape of the real data: ru_ru reached the page entirely through the relay, and is
        // larger than anything in the jar. Ranking the merged list as a whole is the point --
        // appending relay-only people put the mod's biggest translator last.
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Ada", "de_de", 900, 1200), baked("Bo", "fr_fr", 40, 1200)),
            Map.of("ru_ru", List.of(new Credit("Cy", 1683))), (l) -> 1854);
        assertEquals(List.of("Cy", "Ada", "Bo"), names(out));
    }

    @Test
    @DisplayName("a translator is ranked on their total across languages, not their largest share")
    void rankIsTheSumNotTheStrongestShare() {
        // Ada holds the bigger single share (75% vs 60%), Bo the bigger body of work (1200 keys
        // over two languages vs 900). Bo ranks first.
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Ada", "de_de", 900, 1200), baked("Bo", "fr_fr", 600, 1000)),
            Map.of("pl_pl", List.of(new Credit("Bo", 600))), (l) -> 1000);
        assertEquals(List.of("Bo", "Ada"), names(out));
        assertEquals(2, find(out, "Bo").languages().size());
    }

    @Test
    @DisplayName("equal totals fall back to name order, so the page never reshuffles itself")
    void equalTotalsAreOrderedByName() {
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Zed", "de_de", 5, 10), baked("Ada", "fr_fr", 5, 10)),
            Map.of("pl_pl", List.of(new Credit("Mo", 5))), (l) -> 10);
        assertEquals(List.of("Ada", "Mo", "Zed"), names(out));
    }

    @Test
    @DisplayName("a credit with no known denominator is still ranked, on its key count")
    void unknownTotalStillRanks() {
        // hu_hu has no baked totals, so the screen prints no percentage for Ada -- but she still
        // did more work than Bo, and being unmeasurable must not cost her the position.
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Bo", "fr_fr", 3, 10)),
            Map.of("hu_hu", List.of(new Credit("Ada", 7))), (l) -> "hu_hu".equals(l) ? 0 : 10);
        assertEquals(List.of("Ada", "Bo"), names(out));
        assertEquals(0, find(out, "Ada").languages().get(0).total());
    }

    @Test
    @DisplayName("a merged person's languages come back strongest-share-first")
    void languagesAreOrderedByShare() {
        // withShare appends the relay language to the end; de_de (75%) must still lead pl_pl (5%).
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Ada", "de_de", 900, 1200)),
            Map.of("pl_pl", List.of(new Credit("Ada", 50))), (l) -> 1000);
        assertEquals(List.of("de_de", "pl_pl"), find(out, "Ada").languages().stream()
            .map(TranslationContributor.LanguageShare::locale).toList());
    }

    @Test
    @DisplayName("a blank name is never credited, at this layer either")
    void blankNamesAreDropped() {
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(), Map.of("de_de", List.of(new Credit("  ", 5), new Credit("Ada", 5))),
            (l) -> 100);
        assertEquals(1, out.size());
        assertEquals("Ada", out.get(0).name());
    }

    @Test
    @DisplayName("an unknown denominator yields a nameable credit with no percentage")
    void unknownTotalIsTolerated() {
        // The name and the language are real; the percentage would be invented. fraction() returns
        // 0 and the screen omits the figure rather than printing a made-up one.
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(), Map.of("hu_hu", List.of(new Credit("Ada", 7))), (l) -> 0);
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).languages().get(0).total());
        assertEquals(0.0, out.get(0).languages().get(0).fraction(), 1e-9);
    }

    @Test
    @DisplayName("no relay data at all leaves the baked list exactly as it was")
    void noRelayDataIsIdentity() {
        List<TranslationContributor> baked = List.of(baked("Ada", "de_de", 1, 10));
        assertEquals(baked, TranslationCreditsMerge.merge(baked, Map.of(), (l) -> 10));
        assertEquals(baked, TranslationCreditsMerge.merge(baked, null, (l) -> 10));
    }

    @Test
    @DisplayName("a renamed translator is one line under the new name, from both sources")
    void aliasFoldsOldNameIntoNew() {
        // The jar and the cached relay credits both still carry the old name after a rename; the
        // alias folds them into the new one so the page never thanks the same person twice.
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Old", "de_de", 900, 1200)),
            Map.of("de_de", List.of(new Credit("Old", 12)), "fr_fr", List.of(new Credit("New", 30))),
            (l) -> 1200, Map.of("Old", "New"));
        assertEquals(List.of("New"), names(out));
        TranslationContributor person = find(out, "New");
        assertEquals(900, person.languages().get(0).contributed(), "the baked share still wins");
        assertEquals(2, person.languages().size(), "and the relay-only language joins them");
        assertTrue(person.url().isPresent(), "keeping the link the old entry had");
    }

    @Test
    @DisplayName("a chain of renames resolves to the last name, and a cycle stops")
    void aliasChains() {
        Map<String, String> chain = Map.of("A", "B", "B", "C");
        assertEquals("C", TranslatorRenames.resolve(chain, "A"));
        assertEquals("C", TranslatorRenames.resolve(chain, "C"));
        assertEquals("Z", TranslatorRenames.resolve(chain, "Z"));
        // A cycle terminates and comes back to where it started (record() never stores one).
        assertEquals("A", TranslatorRenames.resolve(Map.of("A", "B", "B", "A"), "A"));
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("A", "de_de", 10, 100), baked("B", "fr_fr", 5, 100)),
            Map.of(), (l) -> 100, chain);
        assertEquals(List.of("C"), names(out));
        assertEquals(2, find(out, "C").languages().size(), "both old entries' languages fold in");
    }

    @Test
    @DisplayName("without aliases the three-argument merge is unchanged")
    void noAliasesIsIdentity() {
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Ada", "de_de", 900, 1200)), Map.of(), (l) -> 1200);
        assertEquals(List.of("Ada"), names(out));
    }
}
