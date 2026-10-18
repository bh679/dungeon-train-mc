package games.brennan.dungeontrain.compat;

import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards {@link CreakingHeartSettings}: a heart with no DT tag behaves as before (awake day and
 * night, one Creaking, every level and band), the settings survive the block-entity NBT that
 * carries them into templates, and the day / night / gated-off decision.
 */
final class CreakingHeartSettingsTest {

    @Test
    void missingTagIsTheDefault() {
        assertEquals(CreakingHeartSettings.DEFAULT, CreakingHeartSettings.load(new CompoundTag()));
    }

    @Test
    void defaultIsAwakeDayAndNight() {
        assertTrue(CreakingHeartSettings.DEFAULT.awake(true));
        assertTrue(CreakingHeartSettings.DEFAULT.awake(false));
    }

    @Test
    void dayAndNightAreIndependent() {
        CreakingHeartSettings nightOnly = CreakingHeartSettings.DEFAULT.withDay(false);
        assertTrue(nightOnly.awake(true));
        assertFalse(nightOnly.awake(false));
        CreakingHeartSettings dayOnly = CreakingHeartSettings.DEFAULT.withNight(false);
        assertFalse(dayOnly.awake(true));
        assertTrue(dayOnly.awake(false));
    }

    @Test
    void gatedOffHeartNeverWakes() {
        CreakingHeartSettings off = CreakingHeartSettings.DEFAULT.withGatedOff(true);
        assertFalse(off.awake(true));
        assertFalse(off.awake(false));
    }

    @Test
    void countIsClamped() {
        assertEquals(CreakingHeartSettings.MIN_COUNT, CreakingHeartSettings.DEFAULT.withCount(0).count());
        assertEquals(CreakingHeartSettings.MAX_COUNT, CreakingHeartSettings.DEFAULT.withCount(99).count());
    }

    @Test
    void roundTripsThroughNbt() {
        CreakingHeartSettings s = CreakingHeartSettings.DEFAULT
            .withDay(false)
            .withCount(3)
            .withGate(new TemplateGate(5, 40, EnumSet.of(TrainPhase.NETHER, TrainPhase.END)))
            .withStage("desert")
            .withGatedOff(true);
        assertEquals(s, CreakingHeartSettings.load(s.save()));
    }

    @Test
    void customGateIsUsedWhenNoStageIsLinked() {
        TemplateGate gate = new TemplateGate(5, 40, EnumSet.of(TrainPhase.NETHER));
        assertEquals(gate, CreakingHeartStampGate.effectiveGate(CreakingHeartSettings.DEFAULT.withGate(gate)));
    }
}
