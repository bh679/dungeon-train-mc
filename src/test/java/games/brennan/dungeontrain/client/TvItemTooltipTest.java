package games.brennan.dungeontrain.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a TV's inventory label says. */
final class TvItemTooltipTest {

    @Test
    @DisplayName("a TV with no remembered stream says only On or Off")
    void powerOnly() {
        assertEquals(List.of(TvItemTooltip.OFF_KEY), TvItemTooltip.labelKeys(false, 0));
        assertEquals(List.of(TvItemTooltip.ON_KEY), TvItemTooltip.labelKeys(true, 0));
    }

    @Test
    @DisplayName("a remembered stream follows the power state")
    void powerThenStream() {
        assertEquals(List.of(TvItemTooltip.OFF_KEY, TvItemTooltip.STREAM_1_KEY), TvItemTooltip.labelKeys(false, 1));
        assertEquals(List.of(TvItemTooltip.ON_KEY, TvItemTooltip.STREAM_2_KEY), TvItemTooltip.labelKeys(true, 2));
    }
}
