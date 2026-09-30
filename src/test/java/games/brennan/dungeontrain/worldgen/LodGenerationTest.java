package games.brennan.dungeontrain.worldgen;

import net.minecraft.world.level.levelgen.GenerationStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Distant Horizons LOD-lite seams: the generator-thread name DH 2.4.3-b uses
 * ({@code DhThreadFactory}: {@code "DH-" + pool + " Thread[" + n + "]"}, pool {@code "World Gen"}),
 * the debug-override semantics, and the decoration steps an LOD can show.
 */
final class LodGenerationTest {

    @Test
    @DisplayName("DH's LOD generator workers are recognised by name; its other pools and MC threads are not")
    void threadNameMatcher() {
        assertTrue(LodGeneration.isLodThreadName("DH-World Gen Thread[0]"));
        assertTrue(LodGeneration.isLodThreadName("DH-World Gen Thread[17]"));
        assertFalse(LodGeneration.isLodThreadName("DH-LOD Builder Thread[0]"));
        assertFalse(LodGeneration.isLodThreadName("DH-Update Propagator Thread[1]"));
        assertFalse(LodGeneration.isLodThreadName("Server thread"));
        assertFalse(LodGeneration.isLodThreadName("worker-1"));
        assertFalse(LodGeneration.isLodThreadName(""));
        assertFalse(LodGeneration.isLodThreadName(null));
    }

    @Test
    @DisplayName("the current (JUnit) thread is not an LOD thread")
    void currentThreadIsNotLod() {
        assertFalse(LodGeneration.isLodThread());
    }

    @Test
    @DisplayName("AUTO follows the thread; FORCE_ON / FORCE_OFF ignore it")
    void overrideModes() {
        assertTrue(LodGeneration.liteRequested(LodGeneration.Mode.AUTO, true));
        assertFalse(LodGeneration.liteRequested(LodGeneration.Mode.AUTO, false));
        assertTrue(LodGeneration.liteRequested(LodGeneration.Mode.FORCE_ON, false));
        assertFalse(LodGeneration.liteRequested(LodGeneration.Mode.FORCE_OFF, true));
        assertEquals(LodGeneration.Mode.AUTO, LodGeneration.MODE, "ships in AUTO");
    }

    @Test
    @DisplayName("silhouette steps are kept; ores, underground decoration and springs are skipped")
    void lodVisibleSteps() {
        assertEquals(EnumSet.of(
                GenerationStep.Decoration.LOCAL_MODIFICATIONS,
                GenerationStep.Decoration.SURFACE_STRUCTURES,
                GenerationStep.Decoration.VEGETAL_DECORATION,
                GenerationStep.Decoration.TOP_LAYER_MODIFICATION), LodGeneration.LOD_VISIBLE_STEPS);
        assertTrue(LodGeneration.isLodVisibleStep(GenerationStep.Decoration.VEGETAL_DECORATION.ordinal()));
        assertTrue(LodGeneration.isLodVisibleStep(GenerationStep.Decoration.SURFACE_STRUCTURES.ordinal()));
        assertFalse(LodGeneration.isLodVisibleStep(GenerationStep.Decoration.UNDERGROUND_ORES.ordinal()));
        assertFalse(LodGeneration.isLodVisibleStep(GenerationStep.Decoration.UNDERGROUND_DECORATION.ordinal()));
        assertFalse(LodGeneration.isLodVisibleStep(GenerationStep.Decoration.FLUID_SPRINGS.ordinal()));
    }

    @Test
    @DisplayName("an unknown (modded) step index is kept — never drop a feature blind")
    void unknownStepKept() {
        assertTrue(LodGeneration.isLodVisibleStep(GenerationStep.Decoration.values().length));
        assertTrue(LodGeneration.isLodVisibleStep(-1));
    }
}
