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
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    @DisplayName("trait words bucket 0–2, 3–4, 5–7, 8–10")
    void words() {
        assertEquals("gui.dungeontrain.death.echo.word.measured", EchoPersonality.wordKey(FIGHT_FLIGHT, 0));
        assertEquals("gui.dungeontrain.death.echo.word.measured", EchoPersonality.wordKey(FIGHT_FLIGHT, 2));
        assertEquals("gui.dungeontrain.death.echo.word.willing", EchoPersonality.wordKey(FIGHT_FLIGHT, 3));
        assertEquals("gui.dungeontrain.death.echo.word.willing", EchoPersonality.wordKey(FIGHT_FLIGHT, 4));
        assertEquals("gui.dungeontrain.death.echo.word.aggressive", EchoPersonality.wordKey(FIGHT_FLIGHT, 5));
        assertEquals("gui.dungeontrain.death.echo.word.aggressive", EchoPersonality.wordKey(FIGHT_FLIGHT, 7));
        assertEquals("gui.dungeontrain.death.echo.word.ferocious", EchoPersonality.wordKey(FIGHT_FLIGHT, 8));
        assertEquals("gui.dungeontrain.death.echo.word.ferocious", EchoPersonality.wordKey(FIGHT_FLIGHT, 10));
        assertEquals("gui.dungeontrain.death.echo.word.hostile", EchoPersonality.wordKey(FRIENDLINESS, 1));
        assertEquals("gui.dungeontrain.death.echo.word.wary", EchoPersonality.wordKey(FRIENDLINESS, 4));
        assertEquals("gui.dungeontrain.death.echo.word.amiable", EchoPersonality.wordKey(FRIENDLINESS, 6));
        assertEquals("gui.dungeontrain.death.echo.word.warm", EchoPersonality.wordKey(FRIENDLINESS, 9));
        assertEquals("gui.dungeontrain.death.echo.word.sluggish",
                EchoPersonality.wordKey(EchoPersonality.Trait.REACTION, 0));
        assertEquals("gui.dungeontrain.death.echo.word.lightning",
                EchoPersonality.wordKey(EchoPersonality.Trait.REACTION, 9));
    }
}
