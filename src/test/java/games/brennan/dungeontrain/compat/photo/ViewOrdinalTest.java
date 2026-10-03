package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViewOrdinalTest {

    @Test
    @DisplayName("English ordinal endings, including the teens")
    void endings() {
        assertEquals("st", ViewOrdinal.ending(1));
        assertEquals("nd", ViewOrdinal.ending(2));
        assertEquals("rd", ViewOrdinal.ending(3));
        assertEquals("th", ViewOrdinal.ending(4));
        assertEquals("th", ViewOrdinal.ending(11));
        assertEquals("th", ViewOrdinal.ending(12));
        assertEquals("th", ViewOrdinal.ending(13));
        assertEquals("st", ViewOrdinal.ending(21));
        assertEquals("nd", ViewOrdinal.ending(102));
        assertEquals("th", ViewOrdinal.ending(111));
    }
}
