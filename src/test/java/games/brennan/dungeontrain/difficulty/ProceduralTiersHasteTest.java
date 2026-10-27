package games.brennan.dungeontrain.difficulty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Haste curve in {@link ProceduralTiers}: it holds off until 100 levels after the
 * first digging-tool mob, then climbs at 1/10 the rate of the other buffs.
 */
final class ProceduralTiersHasteTest {

    private static Optional<DifficultyTier.EffectSpec> haste(int level) {
        return ProceduralTiers.effectsFor(level).stream()
                .filter(spec -> spec.id().toString().equals("minecraft:haste"))
                .findFirst();
    }

    private static Optional<DifficultyTier.EffectSpec> speed(int level) {
        return ProceduralTiers.effectsFor(level).stream()
                .filter(spec -> spec.id().toString().equals("minecraft:speed"))
                .findFirst();
    }

    @Test
    @DisplayName("Haste starts 100 levels after the first digging-tool mob")
    void startsAfterTools() {
        assertEquals(ToolTiers.TOOL_START_LEVEL + 100, ProceduralTiers.HASTE_START_LEVEL);
        assertTrue(haste(ProceduralTiers.HASTE_START_LEVEL).isEmpty());
        assertTrue(haste(ProceduralTiers.HASTE_START_LEVEL + 1).isPresent());
    }

    @Test
    @DisplayName("Haste gains a level every 250 levels, 1/10 the rate of Speed")
    void climbsAtATenthTheRate() {
        int start = ProceduralTiers.HASTE_START_LEVEL;
        assertEquals(0, haste(start + 249).orElseThrow().amplifier());
        assertEquals(1, haste(start + 250).orElseThrow().amplifier());
        assertEquals(2, haste(start + 500).orElseThrow().amplifier());
        // Speed (start 15, every 25) climbs ten steps over the same 250 levels.
        assertEquals(10, speed(15 + 250).orElseThrow().amplifier());
    }
}
