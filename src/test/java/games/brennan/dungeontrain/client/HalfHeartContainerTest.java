package games.brennan.dungeontrain.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HalfHeartContainerTest {

    @Test
    void evenMaxHealthHasNoHalfContainer() {
        assertEquals(HalfHeartContainer.NONE, HalfHeartContainer.halfIndex(20f));
        assertEquals(HalfHeartContainer.NONE, HalfHeartContainer.halfIndex(22f));
    }

    @Test
    void oddMaxHealthHalvesTheLastContainer() {
        assertEquals(10, HalfHeartContainer.halfIndex(21f));
        assertEquals(0, HalfHeartContainer.halfIndex(1f));
        assertEquals(10, HalfHeartContainer.halfIndex(20.5f));
    }

    @Test
    void noHealthHasNoContainers() {
        assertEquals(HalfHeartContainer.NONE, HalfHeartContainer.halfIndex(0f));
    }
}
