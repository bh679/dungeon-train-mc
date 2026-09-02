package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.train.CatchUpBurstMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins where catch-up spawning lives and what it ships as.
 *
 * <p>The setting began life in {@link DungeonTrainConfig} — a SERVER spec, and therefore stored
 * per-save and loaded only while a world of ours is running. That is why the Options row was absent
 * at the title screen and on multiplayer clients, which is how a player found it missing. It now
 * lives in {@link DungeonTrainCommonConfig}, which loads at mod construction on both sides, so there
 * is one global value editable with no world open. Moving it back to a per-save spec would make the
 * row disappear again in exactly the same silent way — no test would fail and no log would say so —
 * so the location is pinned here rather than left to review.</p>
 */
class CatchUpBurstDefaultsTest {

    @Test
    @DisplayName("catch-up spawning ships as FILL")
    void defaultIsFill() {
        assertEquals(CatchUpBurstMode.FILL, DungeonTrainCommonConfig.DEFAULT_CATCH_UP_BURST_MODE,
                "FILL is the only mode measured to actually close a deficit at speed — BURST_TWO "
                        + "held a ~8-group shortfall steady without ever catching up");
        assertNotNull(DungeonTrainCommonConfig.getCatchUpBurstMode(),
                "the getter is read by the title-screen Options row, where a null would take the "
                        + "screen down");
    }

    @Test
    @DisplayName("the key lives in the COMMON spec, under [train]")
    void livesInTheCommonSpecUnderTrain() {
        assertEquals(List.of("train", "catchUpBurstMode"),
                DungeonTrainCommonConfig.CATCH_UP_BURST_MODE.getPath(),
                "config/dungeontrain-common.toml is what makes this editable with no world open");
    }

    /**
     * The old SERVER accessors are gone, not merely unused. Left in place they would be the obvious
     * thing for a future caller to reach for, and that caller would silently be reading a per-save
     * value again.
     */
    @Test
    @DisplayName("the per-save SERVER accessors are gone")
    void serverAccessorsAreGone() {
        assertThrows(NoSuchMethodException.class,
                () -> DungeonTrainConfig.class.getMethod("getCatchUpBurstMode"));
        assertThrows(NoSuchMethodException.class,
                () -> DungeonTrainConfig.class.getMethod("setCatchUpBurstMode", CatchUpBurstMode.class));
    }
}
