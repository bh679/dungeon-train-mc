package games.brennan.dungeontrain.discord;

import games.brennan.dungeontrain.net.DeathNarrative;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the manifest death-report formatting: title, narration body + de-duped strips, person rewrite. */
final class DeathManifestFormatTest {

    private static DeathNarrative narr(String fall, String deeds, String gear, String lives, String epitaph) {
        return new DeathNarrative("q", fall, "q", deeds, "q", gear, "q", "", lives, "q", "platform", epitaph);
    }

    private static String desc(DeathNarrative n, List<String> advs) {
        return DeathManifestFormat.description(n, "You were slain by a Zombie",
                1284.0, 15160L, 412.0, 196.0, 21, 4, advs, 612L, 18902.0, 21L, 47L);
    }

    @Test
    void titleIsPlayerAndCarriage() {
        assertEquals("Wanderer - Carriage 47", DeathManifestFormat.title("Wanderer", 47));
        assertEquals("Steve - Carriage 0", DeathManifestFormat.title("Steve", 0));
    }

    @Test
    void stripsNumberColourSentinels() {
        // The death screen wraps figures in U+0001 … U+0002; the report must not carry them.
        assertEquals("forty-seven", DeathManifestFormat.strip("forty-seven"));
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
    void descriptionLeadsWithThirdPersonNarrationThenSections() {
        DeathNarrative n = narr(
                "Carriage forty-seven. The dark reached you.",
                "You felled eighty-three things; nine met, three slain, two friends.",
                "Iron and edge, none of it enough.",
                "Across fourteen lives.",
                "the fourteenth to fall.");
        String d = desc(n, List.of("Strangers on a Train", "A Friend Defended"));

        // The fall narration opens the BODY, rewritten to third person ("you" → "them").
        assertTrue(d.startsWith("Carriage forty-seven. The dark reached them."), "fall narration leads the body, 3rd person");
        assertTrue(d.contains("They felled eighty-three things"), "deeds narration header, 3rd person");
        assertFalse(d.contains("You felled") || d.contains("reached you"), "no second-person left");
        // De-duped strips + advancements + epitaph.
        assertTrue(d.contains("1284 m") && d.contains("12:38"), "fall strip");
        assertTrue(d.contains("412") && d.contains("196"), "deeds strip damage");
        assertTrue(d.contains("21 loot") && d.contains("4 books"), "cargo strip");
        assertTrue(d.contains("612 carriages") && d.contains("47 books"), "lives strip");
        assertTrue(d.contains("Strangers on a Train, A Friend Defended"), "advancement names");
        assertTrue(d.contains("*the fourteenth to fall.*"), "italic epitaph");
    }

    @Test
    void omitsAdvancementSegmentAndEmptySections() {
        DeathNarrative n = narr("fall", "", "Iron and edge.", "", "");
        String d = desc(n, List.of());
        assertFalse(d.contains("🏆"), "no advancement segment when none earned");
        assertFalse(d.contains(" dealt "), "deeds section omitted when its narration is blank");
        assertTrue(d.contains("Iron and edge."), "cargo section still present");
    }
}
