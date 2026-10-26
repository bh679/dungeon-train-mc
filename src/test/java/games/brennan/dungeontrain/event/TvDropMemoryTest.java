package games.brennan.dungeontrain.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TvDropMemoryTest {

    @Test
    @DisplayName("a switched-on, tuned TV remembers its stream when broken; an off or untuned one remembers nothing")
    void brokenTvRemembersItsStream() {
        assertEquals(1, TvDropMemory.streamOf(true, true, false));
        assertEquals(2, TvDropMemory.streamOf(true, true, true));
        assertEquals(0, TvDropMemory.streamOf(false, true, true), "off: both next time");
        assertEquals(0, TvDropMemory.streamOf(true, false, false), "showing something else");
    }
}
