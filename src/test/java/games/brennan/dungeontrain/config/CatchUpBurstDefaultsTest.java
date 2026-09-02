package games.brennan.dungeontrain.config;

import games.brennan.dungeontrain.train.CatchUpBurstMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("catch-up spawning ships as AUTO")
    void defaultIsAuto() {
        assertEquals(CatchUpBurstMode.AUTO, DungeonTrainCommonConfig.DEFAULT_CATCH_UP_BURST_MODE,
                "one pacing cannot suit both a desktop and a thin laptop — AUTO picks per machine");
        assertNotNull(DungeonTrainCommonConfig.getCatchUpBurstMode(),
                "the getter is read by the title-screen Options row, where a null would take the "
                        + "screen down");
    }

    @Test
    @DisplayName("FILL is remembered as the legacy default, so the migration can spot it")
    void legacyDefaultIsFill() {
        assertEquals(CatchUpBurstMode.FILL, DungeonTrainCommonConfig.LEGACY_CATCH_UP_BURST_MODE,
                "the v0 -> v1 migration moves exactly this value to AUTO; change it and every "
                        + "existing install is either missed or has a deliberate choice overwritten");
    }

    /**
     * Flipping a shipped default only ever reaches installs with no config file yet. Everyone
     * already playing has FILL written to disk, so without a version bump the migration never runs
     * and AUTO reaches almost nobody — the failure mode is total silence.
     */
    @Test
    @DisplayName("a config migration ships to carry AUTO to existing installs")
    void aMigrationShipsForTheNewDefault() {
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION
                        > DungeonTrainCommonConfig.DEFAULT_CONFIG_VERSION,
                "CURRENT_CONFIG_VERSION must exceed the pre-versioning default, or "
                        + "runPendingMigrations() returns immediately and every existing "
                        + "dungeontrain-common.toml keeps FILL forever");
        assertTrue(DungeonTrainCommonConfig.CURRENT_CONFIG_VERSION
                        <= DungeonTrainCommonConfig.MAX_CONFIG_VERSION,
                "outside the spec's range the value fails validation and NeoForge silently resets "
                        + "it to the default, re-running every migration on every launch");
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
