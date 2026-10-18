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
    void countRangeIsClampedAndOrdered() {
        CreakingHeartSettings s = CreakingHeartSettings.DEFAULT.withMinCount(0).withMaxCount(99);
        assertEquals(CreakingHeartSettings.MIN_COUNT, s.minCount());
        assertEquals(CreakingHeartSettings.MAX_COUNT, s.maxCount());
        // Raising min past max drags max up; max never drops below min.
        CreakingHeartSettings dragged = CreakingHeartSettings.DEFAULT.withMinCount(5);
        assertEquals(5, dragged.minCount());
        assertEquals(5, dragged.maxCount());
        assertEquals(5, dragged.withMaxCount(2).maxCount());
    }

    @Test
    void pickStaysInRange() {
        CreakingHeartSettings s = CreakingHeartSettings.DEFAULT.withMaxCount(4).withMinCount(2);
        for (int roll = 0; roll < 50; roll++) {
            int n = s.pickCount(roll);
            assertTrue(n >= 2 && n <= 4, "pick " + n);
        }
    }

    @Test
    void targetIsTheRollOnceMadeElseTheTopOfTheRange() {
        CreakingHeartSettings s = CreakingHeartSettings.DEFAULT.withMaxCount(4).withMinCount(2);
        assertEquals(4, s.targetCount());
        assertEquals(3, s.withRolledCount(3).targetCount());
    }

    @Test
    void legacySingleCountLoadsAsAFixedRange() {
        CompoundTag legacy = new CompoundTag();
        legacy.putInt("count", 3);
        CreakingHeartSettings s = CreakingHeartSettings.load(legacy);
        assertEquals(3, s.minCount());
        assertEquals(3, s.maxCount());
    }

    @Test
    void roundTripsThroughNbt() {
        CreakingHeartSettings s = CreakingHeartSettings.DEFAULT
            .withDay(false)
            .withMaxCount(5)
            .withMinCount(2)
            .withRolledCount(4)
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
