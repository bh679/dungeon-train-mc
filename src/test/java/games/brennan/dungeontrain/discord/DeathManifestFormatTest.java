package games.brennan.dungeontrain.discord;

import games.brennan.discordpresence.discord.DeathField;
import games.brennan.dungeontrain.net.DeathNarrative;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the manifest death-report formatting: title, prose-only body, the three stat columns, person rewrite. */
final class DeathManifestFormatTest {

    private static DeathNarrative narr(String fall, String deeds, String gear, String lives, String epitaph) {
        return new DeathNarrative("q", fall, "q", deeds, "q", gear, "q", "", lives, "q", "platform", epitaph);
    }

    private static String desc(DeathNarrative n) {
        return DeathManifestFormat.description(n);
    }

    private static List<DeathField> fields(List<String> advs) {
        return DeathManifestFormat.fields("You were slain by a Zombie",
                1284.0, 15160L, 412.0, 196.0, 21, 4, advs, 9, 2, 5);
    }

    @Test
    void titleIsPlayerAndCarriage() {
        assertEquals("Wanderer - Carriage 47", DeathManifestFormat.title("Wanderer", 47));
        assertEquals("Steve - Carriage 0", DeathManifestFormat.title("Steve", 0));
    }

    @Test
    void stripsNumberColourSentinels() {
        // The death screen wraps figures in U+0001 … U+0002; the report must not carry them.
        assertEquals("forty-seven", DeathManifestFormat.strip("forty-seven"));
    }

    @Test
    void thirdPersonHandlesSubjectObjectAndPossessive() {
        // subject "you" → they
        assertEquals("They felled three things.", DeathManifestFormat.thirdPerson("You felled three things."));
        assertEquals("here they stand", DeathManifestFormat.thirdPerson("here you stand"));
        // object "you" → them (preposition / transitive verb, or clause end / conjunction)
        assertEquals("the dark reached them.", DeathManifestFormat.thirdPerson("the dark reached you."));
        assertEquals("It is not waiting for them", DeathManifestFormat.thirdPerson("It is not waiting for you"));
        assertEquals("the whole of them: gone", DeathManifestFormat.thirdPerson("the whole of you: gone"));
        assertEquals("the line took them and still they returned",
                DeathManifestFormat.thirdPerson("the line took you and still you returned"));
        assertEquals("the dark had them.", DeathManifestFormat.thirdPerson("the dark had you."));
        assertEquals("The line lets them keep it", DeathManifestFormat.thirdPerson("The line lets you keep it"));
        // modal keeps "you" subject even at a clause end
        assertEquals("So should they.", DeathManifestFormat.thirdPerson("So should you."));
        // possessive / reflexive
        assertEquals("matched their pace", DeathManifestFormat.thirdPerson("matched your pace"));
        assertEquals("ever theirs to keep", DeathManifestFormat.thirdPerson("ever yours to keep"));
        assertEquals("of themselves they spent", DeathManifestFormat.thirdPerson("of yourself you spent"));
        // capitalised cause
        assertEquals("They were slain by a Zombie", DeathManifestFormat.thirdPerson("You were slain by a Zombie"));
    }

    @Test
    void descriptionIsThirdPersonProseWithoutLivesOrStrips() {
        DeathNarrative n = narr(
                "Carriage forty-seven. The dark reached you.",
                "You felled eighty-three things; nine met, three slain, two friends.",
                "Iron and edge, none of it enough.",
                "Across fourteen lives.",
                "the fourteenth to fall.");
        String d = desc(n);

        // The fall narration opens the BODY, rewritten to third person ("you" → "them").
        assertTrue(d.startsWith("Carriage forty-seven. The dark reached them."), "fall narration leads the body, 3rd person");
        assertTrue(d.contains("They felled eighty-three things"), "deeds narration header, 3rd person");
        assertFalse(d.contains("You felled") || d.contains("reached you"), "no second-person left");
        // The lives narration is intentionally dropped from the embed body.
        assertFalse(d.contains("Across fourteen lives"), "lives narration omitted from the embed");
        // The body is prose only — the numbers live in fields, not the description.
        assertFalse(d.contains("1284 m") || d.contains("21 loot"), "no stat strips in the description");
        // Epitaph closes it, italic.
        assertTrue(d.contains("*the fourteenth to fall.*"), "italic epitaph");
    }

    @Test
    void descriptionOmitsEmptyNarrationSections() {
        DeathNarrative n = narr("fall", "", "Iron and edge.", "", "");
        String d = desc(n);
        assertTrue(d.contains("fall"), "fall section present");
        assertTrue(d.contains("Iron and edge."), "cargo section present");
        assertFalse(d.contains("\n\n\n"), "empty deeds/lives sections leave no doubled blank lines");
    }

    @Test
    void fieldsCarryRunStatsInThreeColumns() {
        List<DeathField> f = fields(List.of("Strangers on a Train", "A Friend Defended"));
        assertEquals(3, f.size(), "three inline columns");
        assertEquals("The fall", f.get(0).name());
        assertEquals("This run", f.get(1).name());
        assertEquals("The souls", f.get(2).name());

        String fall = f.get(0).value();
        assertTrue(fall.contains("They were slain by a Zombie"), "death cause, 3rd person");
        assertTrue(fall.contains("1284 m") && fall.contains("12:38"), "distance + time");

        String run = f.get(1).value();
        assertTrue(run.contains("412") && run.contains("196"), "damage dealt + taken");
        assertTrue(run.contains("21 loot") && run.contains("4 books"), "loot + books");
        assertTrue(run.contains("Strangers on a Train, A Friend Defended"), "advancement names");

        String souls = f.get(2).value();
        assertTrue(souls.contains("9 met") && souls.contains("2 befriended") && souls.contains("5 slain"),
                "souls met / befriended / slain");
    }

    @Test
    void fieldsOmitAdvancementLineWhenNoneEarned() {
        List<DeathField> f = fields(List.of());
        assertFalse(f.get(1).value().contains("🏆"), "no advancement line when none earned");
    }
}
