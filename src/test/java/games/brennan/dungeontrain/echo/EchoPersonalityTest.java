package games.brennan.dungeontrain.echo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static games.brennan.dungeontrain.echo.EchoPersonality.Kind.BRUTE;
import static games.brennan.dungeontrain.echo.EchoPersonality.Kind.GENTLE_SOUL;
import static games.brennan.dungeontrain.echo.EchoPersonality.Kind.GUARDIAN;
import static games.brennan.dungeontrain.echo.EchoPersonality.Kind.RECLUSE;
import static games.brennan.dungeontrain.echo.EchoPersonality.Kind.SENTINEL;
import static games.brennan.dungeontrain.echo.EchoPersonality.Kind.SKEPTIC;
import static games.brennan.dungeontrain.echo.EchoPersonality.Trait.FIGHT_FLIGHT;
import static games.brennan.dungeontrain.echo.EchoPersonality.Trait.FRIENDLINESS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EchoPersonalityTest {

    @Test
    @DisplayName("a neutral 5/5 life fights and greets, as PlayerMob's AI does: Guardian")
    void neutralIsGuardian() {
        assertEquals(GUARDIAN, EchoPersonality.classify(5, 5));
    }

    @Test
    @DisplayName("fight/flight splits at 5, PlayerMob's FF_FIGHT")
    void fightThreshold() {
        assertEquals(GENTLE_SOUL, EchoPersonality.classify(4, 5));
        assertEquals(GUARDIAN, EchoPersonality.classify(5, 5));
        assertEquals(SKEPTIC, EchoPersonality.classify(4, 3));
        assertEquals(SENTINEL, EchoPersonality.classify(5, 3));
        assertEquals(RECLUSE, EchoPersonality.classify(4, 2));
        assertEquals(BRUTE, EchoPersonality.classify(5, 2));
    }

    @Test
    @DisplayName("friendliness: 5+ greets, 3–4 watches, 0–2 is hostile up close")
    void friendlinessBands() {
        assertEquals(GUARDIAN, EchoPersonality.classify(8, 5));
        assertEquals(SENTINEL, EchoPersonality.classify(8, 4));
        assertEquals(SENTINEL, EchoPersonality.classify(8, 3));
        assertEquals(BRUTE, EchoPersonality.classify(8, 2));
        assertEquals(BRUTE, EchoPersonality.classify(8, 0));
    }

    @Test
    @DisplayName("out-of-range traits clamp to the ends of the scale")
    void clamps() {
        assertEquals(RECLUSE, EchoPersonality.classify(-3, -1));
        assertEquals(GUARDIAN, EchoPersonality.classify(42, 99));
    }

    @Test
    @DisplayName("trait words: three per score, keyed by trait, clamped score and wrapped variant")
    void words() {
        assertEquals("gui.dungeontrain.death.echo.word.fight.0.0", EchoPersonality.wordKey(FIGHT_FLIGHT, 0, 0));
        assertEquals("gui.dungeontrain.death.echo.word.friend.10.2", EchoPersonality.wordKey(FRIENDLINESS, 10, 2));
        assertEquals("gui.dungeontrain.death.echo.word.reaction.7.1",
                EchoPersonality.wordKey(EchoPersonality.Trait.REACTION, 7, 4));
        assertEquals("gui.dungeontrain.death.echo.word.fight.10.0", EchoPersonality.wordKey(FIGHT_FLIGHT, 99, 3));
        assertEquals("gui.dungeontrain.death.echo.word.friend.0.2", EchoPersonality.wordKey(FRIENDLINESS, -5, -1));
    }

    @Test
    @DisplayName("personality: friendliness word then fight word; the middle of both is Passive")
    void personality() {
        assertArrayEquals(new String[] {"gui.dungeontrain.death.echo.mood.passive"},
                EchoPersonality.personalityKeys(5, 5));
        assertArrayEquals(new String[] {"gui.dungeontrain.death.echo.mood.passive"},
                EchoPersonality.personalityKeys(4, 6));
        assertArrayEquals(new String[] {"gui.dungeontrain.death.echo.mood.very_friendly",
                "gui.dungeontrain.death.echo.mood.very_shy"}, EchoPersonality.personalityKeys(0, 10));
        assertArrayEquals(new String[] {"gui.dungeontrain.death.echo.mood.very_skeptical",
                "gui.dungeontrain.death.echo.mood.very_aggressive"}, EchoPersonality.personalityKeys(9, 1));
        assertArrayEquals(new String[] {"gui.dungeontrain.death.echo.mood.skeptical",
                "gui.dungeontrain.death.echo.mood.aggressive"}, EchoPersonality.personalityKeys(7, 3));
        assertArrayEquals(new String[] {"gui.dungeontrain.death.echo.mood.friendly",
                "gui.dungeontrain.death.echo.mood.shy"}, EchoPersonality.personalityKeys(3, 7));
    }

    @Test
    @DisplayName("every word and personality key the page can ask for exists in en_us")
    void keysExist() throws Exception {
        String lang = java.nio.file.Files.readString(games.brennan.dungeontrain.RepoPaths.langFile("en_us"));
        for (EchoPersonality.Trait t : EchoPersonality.Trait.values()) {
            for (int v = 0; v <= 10; v++) {
                for (int n = 0; n < EchoPersonality.WORDS_PER_SCORE; n++) {
                    String key = EchoPersonality.wordKey(t, v, n);
                    assertTrue(lang.contains("\"" + key + "\""), key);
                }
            }
        }
        for (int ff = 0; ff <= 10; ff++) {
            for (int fr = 0; fr <= 10; fr++) {
                for (String key : EchoPersonality.personalityKeys(ff, fr)) {
                    assertTrue(lang.contains("\"" + key + "\""), key);
                }
            }
        }
        assertTrue(lang.contains("\"" + EchoPersonality.PAIR_KEY + "\""));
    }
}
