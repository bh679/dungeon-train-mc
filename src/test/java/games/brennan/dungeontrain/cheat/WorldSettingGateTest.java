package games.brennan.dungeontrain.cheat;

import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hold decision behind {@link WorldSettingGate}: a world setting that would make the run Free Play
 * waits for the host's answer, and nothing else does.
 */
class WorldSettingGateTest {

    @Test
    @DisplayName("A tainting setting on a clean host's run is held")
    void cleanHostIsAsked() {
        assertTrue(WorldSettingGate.shouldHold(true, true, false, false));
    }

    @Test
    @DisplayName("A setting that doesn't taint is never held")
    void nonTaintingPasses() {
        assertFalse(WorldSettingGate.shouldHold(false, true, false, false));
    }

    @Test
    @DisplayName("No host online (dedicated server, world still loading): nobody to ask")
    void noHostPasses() {
        assertFalse(WorldSettingGate.shouldHold(true, false, false, false));
    }

    @Test
    @DisplayName("A run that is already Free Play has nothing left to confirm")
    void cheatedHostPasses() {
        assertFalse(WorldSettingGate.shouldHold(true, true, true, false));
    }

    @Test
    @DisplayName("DT re-applying a confirmed change is not asked about again")
    void confirmedReapplyPasses() {
        assertFalse(WorldSettingGate.shouldHold(true, true, false, true));
    }

    @Test
    @DisplayName("Only creative and spectator make a world's game mode a Free Play cause")
    void taintingModes() {
        assertTrue(WorldSettingGate.isTaintingMode(GameType.CREATIVE));
        assertTrue(WorldSettingGate.isTaintingMode(GameType.SPECTATOR));
        assertFalse(WorldSettingGate.isTaintingMode(GameType.SURVIVAL));
        assertFalse(WorldSettingGate.isTaintingMode(GameType.ADVENTURE));
        assertFalse(WorldSettingGate.isTaintingMode(null));
    }

    @Test
    @DisplayName("The gate is closed outside applying() and reopens after it, even if the change throws")
    void applyingIsScoped() {
        assertFalse(WorldSettingGate.isApplying());
        WorldSettingGate.applying(() -> assertTrue(WorldSettingGate.isApplying()));
        assertFalse(WorldSettingGate.isApplying());
        try {
            WorldSettingGate.applying(() -> { throw new IllegalStateException("boom"); });
        } catch (IllegalStateException expected) {
            // fall through
        }
        assertFalse(WorldSettingGate.isApplying());
    }
}
