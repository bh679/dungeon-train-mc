package games.brennan.dungeontrain.client.localization;

import games.brennan.dungeontrain.client.localization.edit.TranslationCoverageClient.Credit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    @DisplayName("the baked order is kept; relay-only people join the end")
    void bakedOrderIsStable() {
        List<TranslationContributor> out = TranslationCreditsMerge.merge(
            List.of(baked("Ada", "de_de", 1, 10), baked("Bo", "fr_fr", 1, 10)),
            Map.of("pl_pl", List.of(new Credit("Cy", 5))), (l) -> 10);
        assertEquals(List.of("Ada", "Bo", "Cy"), out.stream().map(TranslationContributor::name).toList());
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
}
