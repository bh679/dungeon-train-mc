package games.brennan.dungeontrain.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Table-driven tests for {@link SecondPersonDeathMessage#rewrite}. Locks down the
 * fall-page title rewrite: leading display-name → "You", the {@code "was" → "were"}
 * verb-agreement fix, and the pass-through fallbacks. Pure logic — no game bootstrap.
 */
final class SecondPersonDeathMessageTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                // (message, displayName, expected)
                // Verbs identical in 2nd person — only the name is swapped.
                Arguments.of("Brennan fell from a high place", "Brennan", "You fell from a high place"),
                Arguments.of("Brennan drowned", "Brennan", "You drowned"),
                Arguments.of("Brennan blew up", "Brennan", "You blew up"),
                Arguments.of("Brennan burned to death", "Brennan", "You burned to death"),
                Arguments.of("Brennan went up in flames", "Brennan", "You went up in flames"),
                Arguments.of("Brennan tried to swim in lava", "Brennan", "You tried to swim in lava"),
                Arguments.of("Brennan hit the ground too hard", "Brennan", "You hit the ground too hard"),
                Arguments.of("Brennan starved to death", "Brennan", "You starved to death"),
                Arguments.of("Brennan suffocated in a wall", "Brennan", "You suffocated in a wall"),
                Arguments.of("Brennan froze to death", "Brennan", "You froze to death"),
                Arguments.of("Brennan fell out of the world", "Brennan", "You fell out of the world"),
                // "was …" family → "were …".
                Arguments.of("Brennan was slain by Zombie", "Brennan", "You were slain by Zombie"),
                Arguments.of("Brennan was shot by Skeleton", "Brennan", "You were shot by Skeleton"),
                Arguments.of("Brennan was blown up by Creeper", "Brennan", "You were blown up by Creeper"),
                Arguments.of("Brennan was pricked to death", "Brennan", "You were pricked to death"),
                Arguments.of("Brennan was impaled by a stalactite", "Brennan", "You were impaled by a stalactite"),
                Arguments.of("Brennan was struck by lightning", "Brennan", "You were struck by lightning"),
                // Inner "was" must survive — only a leading "You was" is rewritten.
                Arguments.of("Brennan discovered the floor was lava", "Brennan",
                        "You discovered the floor was lava"),
                // Leading name swapped only once even if it recurs later.
                Arguments.of("Brennan was slain by Brennan", "Brennan", "You were slain by Brennan"),
                // Multi-word display name.
                Arguments.of("Captain Hook didn't want to live in the same world as Tick Tock",
                        "Captain Hook", "You didn't want to live in the same world as Tick Tock"),
                // Doesn't start with the name (unusual modded format) → unchanged.
                Arguments.of("Something strange consumed Brennan", "Brennan",
                        "Something strange consumed Brennan"));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" → \"{2}\"")
    @MethodSource("cases")
    @DisplayName("rewrites death messages into the second person")
    void rewrites(String message, String name, String expected) {
        assertEquals(expected, SecondPersonDeathMessage.rewrite(message, name));
    }

    @Test
    @DisplayName("null / empty message → empty string")
    void emptyMessage() {
        assertEquals("", SecondPersonDeathMessage.rewrite("", "Brennan"));
        assertEquals("", SecondPersonDeathMessage.rewrite(null, "Brennan"));
    }

    @Test
    @DisplayName("empty / null name → message returned unchanged")
    void noName() {
        assertEquals("Brennan fell from a high place",
                SecondPersonDeathMessage.rewrite("Brennan fell from a high place", ""));
        assertEquals("Brennan fell from a high place",
                SecondPersonDeathMessage.rewrite("Brennan fell from a high place", null));
    }
}
