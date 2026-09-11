package games.brennan.dungeontrain.client.credits;

import games.brennan.dungeontrain.template.BuilderCredit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fold from one credit per template to one line per person on the Credits page. */
final class TemplateBuilderCreditsTest {

    private static final String MIKA = "380df991f603344ca090369bad2a924a";
    private static final String ARLO = "11111111222233334444555555555555";

    @Test
    @DisplayName("one line per person: a uuid is one person under any name, most templates first, then by name")
    void aggregatesByPersonAndOrders() {
        List<TemplateBuilderCredits.Builder> out = TemplateBuilderCredits.aggregate(List.of(
            new BuilderCredit(ARLO, "Arlo"),
            new BuilderCredit(MIKA, "Mika"),
            new BuilderCredit(MIKA, ""),            // same person, name not cached this time
            new BuilderCredit("", "Old Friend"),    // no uuid — one person per name
            new BuilderCredit("", "old friend"),
            new BuilderCredit(ARLO, "Arlo Renamed")));
        assertEquals(3, out.size());
        assertEquals(List.of("Arlo", "Mika", "Old Friend"),
            out.stream().map(TemplateBuilderCredits.Builder::display).toList());
        assertEquals(List.of(2, 2, 2), out.stream().map(TemplateBuilderCredits.Builder::templates).toList());
        assertEquals(MIKA, out.get(1).uuid());
    }

    @Test
    @DisplayName("credits naming nobody are dropped, and an empty input is an empty (skippable) card")
    void dropsEmpty() {
        assertTrue(TemplateBuilderCredits.aggregate(List.of()).isEmpty());
        assertTrue(TemplateBuilderCredits.aggregate(Arrays.asList(null, new BuilderCredit("", " "))).isEmpty());
    }

    @Test
    @DisplayName("the shipped weights files load without error (nobody credited is a valid answer)")
    void bundledLoads() {
        TemplateBuilderCredits.reset();
        List<TemplateBuilderCredits.Builder> all = TemplateBuilderCredits.all();
        for (TemplateBuilderCredits.Builder b : all) {
            assertTrue(b.templates() >= 1);
            assertTrue(!b.display().isEmpty());
        }
    }
}
