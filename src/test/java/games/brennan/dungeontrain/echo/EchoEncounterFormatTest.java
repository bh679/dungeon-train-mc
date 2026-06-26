package games.brennan.dungeontrain.echo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for {@link EchoEncounterFormat} and {@link EchoEncounter#log} — the
 * encounter-story prose and beat de-duplication. No Minecraft bootstrap: {@link EchoEncounter} is
 * built directly (its {@code dimension} is unused by the formatter, so {@code null} is fine), the way
 * {@code DeathManifestFormatTest} exercises its formatter without a game.
 */
class EchoEncounterFormatTest {

    private static EchoEncounter encounter(String source, int carriage, EchoEvent... beats) {
        return encounter(source, carriage, List.of(), beats);
    }

    private static EchoEncounter encounter(String source, int carriage, List<String> bestItems,
                                           EchoEvent... beats) {
        EchoEncounter enc = new EchoEncounter(UUID.randomUUID(), null, UUID.randomUUID(), source,
                UUID.randomUUID(), carriage, 0L, bestItems);
        for (EchoEvent b : beats) {
            enc.log(b);
        }
        return enc;
    }

    @Test
    @DisplayName("log() records each beat once, in first-seen order")
    void logDeduplicates() {
        EchoEncounter enc = encounter("Steve", 3);
        assertTrue(enc.log(EchoEvent.MET));
        assertFalse(enc.log(EchoEvent.MET), "second MET is ignored");
        assertTrue(enc.log(EchoEvent.EYE_CONTACT));
        assertEquals(2, enc.beats().size());
        assertEquals(EchoEvent.MET, enc.beats().get(0));
        assertEquals(EchoEvent.EYE_CONTACT, enc.beats().get(1));
    }

    @Test
    @DisplayName("title names the player and the source")
    void title() {
        assertEquals("👁 Brennan met the echo of Steve", EchoEncounterFormat.title("Brennan", "Steve"));
    }

    @Test
    @DisplayName("story opens on the spawn, lists beats in order, and closes on the outcome")
    void storyStructure() {
        EchoEncounter enc = encounter("Steve", 7,
                EchoEvent.SPAWNED, EchoEvent.MET, EchoEvent.EYE_CONTACT,
                EchoEvent.PLAYER_STRUCK_ECHO, EchoEvent.PUSHED_OFF_TRAIN);
        String story = EchoEncounterFormat.story("Brennan", enc, EndReason.ECHO_SLAIN_BY_YOU);

        // Opener names the source, the carriage depth, and the player's train.
        assertTrue(story.startsWith("A remote echo of Steve"), story);
        assertTrue(story.contains("carriage 7"), story);
        assertTrue(story.contains("Brennan's train"), story);

        // Beats appear in the order they were logged.
        int met = story.indexOf("crossed paths");
        int eyes = story.indexOf("eyes met");
        int struck = story.indexOf("drew steel");
        int shoved = story.indexOf("shoved it");
        assertTrue(met > 0 && eyes > met && struck > eyes && shoved > struck, story);

        // Closer reflects the end reason.
        assertTrue(story.contains("by Brennan's hand, the echo of Steve fell"), story);
    }

    @Test
    @DisplayName("a carriage depth below zero is omitted from the opener")
    void unknownCarriageOmitted() {
        EchoEncounter enc = encounter("Alex", -1, EchoEvent.SPAWNED, EchoEvent.MET);
        String story = EchoEncounterFormat.story("Brennan", enc, EndReason.LEFT_BEHIND);
        assertFalse(story.contains("carriage"), story);
        assertTrue(story.contains("left behind"), story);
    }

    @Test
    @DisplayName("two best items are named after the opener, before the beats")
    void bestItemsBoth() {
        EchoEncounter enc = encounter("Steve", 2,
                List.of("a Netherite Sword (8 attack · Sharpness V)", "a Diamond Chestplate (8 armor)"),
                EchoEvent.SPAWNED, EchoEvent.MET);
        String story = EchoEncounterFormat.story("Brennan", enc, EndReason.LEFT_BEHIND);

        assertTrue(story.contains("It still bore a Netherite Sword (8 attack · Sharpness V) "
                + "and a Diamond Chestplate (8 armor)."), story);
        // The gear line sits between the opener and the first beat.
        int opener = story.indexOf("stepped aboard");
        int gear = story.indexOf("It still bore");
        int beat = story.indexOf("crossed paths");
        assertTrue(opener >= 0 && gear > opener && beat > gear, story);
    }

    @Test
    @DisplayName("a single best item uses the singular phrasing")
    void bestItemsSingle() {
        EchoEncounter enc = encounter("Steve", 2, List.of("a Bow (Power III)"), EchoEvent.SPAWNED);
        String story = EchoEncounterFormat.story("Brennan", enc, EndReason.LEFT_BEHIND);
        assertTrue(story.contains("It still bore a Bow (Power III)."), story);
        // Singular phrasing: the gear line names one item, with no "... and ..." join.
        assertFalse(story.contains("(Power III) and"), story);
    }

    @Test
    @DisplayName("an empty-handed echo gets no gear line")
    void bestItemsNone() {
        EchoEncounter enc = encounter("Steve", 2, EchoEvent.SPAWNED, EchoEvent.MET);
        String story = EchoEncounterFormat.story("Brennan", enc, EndReason.LEFT_BEHIND);
        assertFalse(story.contains("It still bore"), story);
    }

    @Test
    @DisplayName("each end reason yields its own closing line")
    void closers() {
        EchoEncounter enc = encounter("Steve", 1, EchoEvent.SPAWNED);
        assertTrue(EchoEncounterFormat.story("B", enc, EndReason.ECHO_SLAIN).contains("The echo of Steve fell."));
        assertTrue(EchoEncounterFormat.story("B", enc, EndReason.YOU_SLAIN_BY_ECHO).contains("struck B down"));
        assertTrue(EchoEncounterFormat.story("B", enc, EndReason.YOU_DIED).contains("B fell, and the echo"));
        assertTrue(EchoEncounterFormat.story("B", enc, EndReason.LEFT_BEHIND).contains("left behind"));
    }
}
