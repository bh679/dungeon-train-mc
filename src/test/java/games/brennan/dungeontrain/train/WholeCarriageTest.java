package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What can become a whole-carriage id.
 *
 * <p>The id is the filename under {@code wholecarriages/}, so the rules are the same ones
 * {@link CarriageVariant} enforces on custom shells — deliberately, because the builder's Save
 * writes both under one name and a name only one of them accepts would half-succeed.</p>
 */
final class WholeCarriageTest {

    @Test
    @DisplayName("A valid name survives becoming a filename")
    void validNames() {
        assertTrue(WholeCarriage.isValidName("crate_car"));
        assertTrue(WholeCarriage.isValidName("car2"));
        assertEquals("crate_car", WholeCarriage.of("crate_car").id());
    }

    @Test
    @DisplayName("Names are lower-cased rather than rejected for case alone")
    void namesAreLowerCased() {
        assertEquals("cratecar", WholeCarriage.of("CrateCar").id());
        assertTrue(WholeCarriage.isValidName("CrateCar"));
    }

    @Test
    @DisplayName("Anything that couldn't be a filename is refused")
    void invalidNames() {
        assertFalse(WholeCarriage.isValidName(null));
        assertFalse(WholeCarriage.isValidName(""));
        assertFalse(WholeCarriage.isValidName("my car"), "a space breaks the id");
        assertFalse(WholeCarriage.isValidName("car!"), "punctuation");
        assertFalse(WholeCarriage.isValidName("a".repeat(33)), "over the length cap");
        // Matches CarriageVariant, which also excludes '-'. The builder's name field is more
        // permissive than both; a hyphenated name already fails at the carriage-variant write,
        // which happens first, so the two stores can't diverge over it.
        assertFalse(WholeCarriage.isValidName("car-2"));
    }

    @Test
    @DisplayName("The validating factory throws rather than writing a bad filename")
    void factoryThrowsOnInvalid() {
        assertThrows(IllegalArgumentException.class, () -> WholeCarriage.of("my car"));
        assertThrows(IllegalArgumentException.class, () -> WholeCarriage.of(""));
        assertThrows(IllegalArgumentException.class, () -> WholeCarriage.of(null));
    }
}
