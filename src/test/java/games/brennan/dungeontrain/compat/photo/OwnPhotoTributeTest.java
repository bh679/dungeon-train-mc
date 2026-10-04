package games.brennan.dungeontrain.compat.photo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a Tribute to your own photo costs, by how many you have paid this life. */
class OwnPhotoTributeTest {

    @Test
    @DisplayName("starts at 3 emeralds and triples with each Tribute this life")
    void triples() {
        assertEquals(3, OwnPhotoTribute.cost(0));
        assertEquals(9, OwnPhotoTribute.cost(1));
        assertEquals(27, OwnPhotoTribute.cost(2));
        assertEquals(81, OwnPhotoTribute.cost(3));
    }

    @Test
    @DisplayName("a negative count is treated as none")
    void negative() {
        assertEquals(3, OwnPhotoTribute.cost(-4));
    }

    @Test
    @DisplayName("never overflows: a huge count caps at Integer.MAX_VALUE")
    void caps() {
        assertEquals(Integer.MAX_VALUE, OwnPhotoTribute.cost(40));
        assertEquals(Integer.MAX_VALUE, OwnPhotoTribute.cost(Integer.MAX_VALUE));
    }
}
