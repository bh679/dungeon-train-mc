package games.brennan.dungeontrain.discord;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogPingsTest {

    private static final UUID A = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void oneMarkerPerPlayer() {
        assertEquals("<dt-ping:069a79f4-44e9-4726-a5be-fca90e38aaf5> <dt-ping:00000000-0000-0000-0000-00000000000b>",
                LogPings.markers(List.of(A, B)));
    }

    @Test
    void nullsSkippedAndDuplicatesCollapsed() {
        assertEquals("<dt-ping:" + A + ">", LogPings.markers(Arrays.asList(A, null, A)));
    }

    @Test
    void nothingToTagIsNull() {
        assertNull(LogPings.markers(Arrays.asList(null, null)));
        assertNull(LogPings.markers(List.of()));
    }

    @Test
    void cappedAtTheRelaysPerPostLimit() {
        List<UUID> many = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertEquals(LogPings.MAX_PER_POST, LogPings.markers(many).split(" ").length);
    }
}
