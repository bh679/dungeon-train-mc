package games.brennan.dungeontrain.worldgen.structure;

import games.brennan.dungeontrain.worldgen.EndIslandGeometry;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-math tests for {@link EndCitySiting} — where the band will and won't stand an End city. Both the
 * cycle and the island density are built by hand (no config, no End dimension), so no NeoForge bootstrap
 * is needed; the same convention as {@code WorldGenCycleTest}.
 *
 * <p>Cycle geometry matches {@code WorldGenCycleTest}'s: anchor 1000, period 1940, End segment at offset
 * [1260, 1940) → world X [2260, 2940), whose core (fade 100 / void 40 either side) is X [2400, 2800).</p>
 */
final class EndCitySitingTest {

    private static final WorldGenCycle CYCLE =
            new WorldGenCycle(1000L, 300, 40, new int[] {1, 5, 20}, 0, 60, 50, 200, 100, 40, 200, 0, 0, 0, 0);

    private static final int BED_Y = 64;
    private static final int SEA_LEVEL = 63;
    private static final int WORLD_MIN_Y = -64;
    private static final int WORLD_MAX_Y = 319;

    /** A world X well inside the End core (island fill ramp 1). */
    private static final int CORE_X = 2600;
    /** A world X in the plain overworld stretch between bands. */
    private static final int OVERWORLD_X = 2000;

    /**
     * Island density that is solid below {@code endTopY} in End space and open void at or above it, and
     * void entirely for {@code z >= voidFromZ}. Linear in Y so the geometry's trilinear interpolation
     * reproduces the step exactly; the surface therefore lands on End-space Y {@code endTopY - 1}.
     */
    private static DensityFunction islandBelow(int endTopY, int voidFromZ) {
        return new StubDensity((x, y, z) -> z >= voidFromZ ? -1.0 : (double) (endTopY - y));
    }

    private static EndIslandGeometry geometry(DensityFunction density) {
        return new EndIslandGeometry.Source(density, 8, 4, BED_Y).open(WORLD_MIN_Y, WORLD_MAX_Y);
    }

    /** World Y the island surface lands on for a given End-space cut-off. */
    private static int expectedTop(int endTopY) {
        return BED_Y + (endTopY - 1) - EndIslandGeometry.END_ISLAND_CENTER_Y;
    }

    @Test
    @DisplayName("flat island in the core: city sits on the island surface")
    void flatIsland() {
        // End-space top 80 → world surface 64 + 79 - 56 = 87, comfortably above sea level.
        EndIslandGeometry g = geometry(islandBelow(80, Integer.MAX_VALUE));
        assertEquals(expectedTop(80), EndCitySiting.siteY(CYCLE, g, SEA_LEVEL, CORE_X, 40, 5, 5));
    }

    @Test
    @DisplayName("open void: no city")
    void noIsland() {
        EndIslandGeometry g = geometry(new StubDensity((x, y, z) -> -1.0));
        assertEquals(Integer.MIN_VALUE, EndCitySiting.siteY(CYCLE, g, SEA_LEVEL, CORE_X, 40, 5, 5));
    }

    @Test
    @DisplayName("island below sea level: no city (the band's analogue of vanilla's y < 60 floor)")
    void islandTooLow() {
        // End-space top 40 → world surface 64 + 39 - 56 = 47, below sea level.
        EndIslandGeometry g = geometry(islandBelow(40, Integer.MAX_VALUE));
        assertEquals(Integer.MIN_VALUE, EndCitySiting.siteY(CYCLE, g, SEA_LEVEL, CORE_X, 40, 5, 5));
    }

    @Test
    @DisplayName("outside the End core: no city, however solid the terrain")
    void outsideBand() {
        EndIslandGeometry g = geometry(islandBelow(80, Integer.MAX_VALUE));
        assertEquals(Integer.MIN_VALUE, EndCitySiting.siteY(CYCLE, g, SEA_LEVEL, OVERWORLD_X, 40, 5, 5));
    }

    @Test
    @DisplayName("one probe corner hanging over the void: no city")
    void cornerOverVoid() {
        // Void from z = 64 on. Probe corners sit at z = 60 and z = 65; the far one samples cell corners
        // 64 and 72 — both in the void — so that corner has no surface at all.
        EndIslandGeometry g = geometry(islandBelow(80, 64));
        assertEquals(Integer.MIN_VALUE, EndCitySiting.siteY(CYCLE, g, SEA_LEVEL, CORE_X, 60, 5, 5));
    }

    @Test
    @DisplayName("null inputs are declined rather than thrown")
    void nullSafe() {
        assertEquals(Integer.MIN_VALUE, EndCitySiting.siteY(null, geometry(islandBelow(80, Integer.MAX_VALUE)),
                SEA_LEVEL, CORE_X, 40, 5, 5));
        assertEquals(Integer.MIN_VALUE, EndCitySiting.siteY(CYCLE, null, SEA_LEVEL, CORE_X, 40, 5, 5));
    }

    /** Minimal {@link DensityFunction} over a plain {@code (x, y, z) → density} lambda. */
    private record StubDensity(Sampler sampler) implements DensityFunction {

        interface Sampler {
            double at(int x, int y, int z);
        }

        @Override
        public double compute(FunctionContext ctx) {
            return sampler.at(ctx.blockX(), ctx.blockY(), ctx.blockZ());
        }

        @Override
        public void fillArray(double[] values, ContextProvider provider) {
            for (int i = 0; i < values.length; i++) {
                values[i] = compute(provider.forIndex(i));
            }
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return this;
        }

        @Override
        public double minValue() {
            return -1.0;
        }

        @Override
        public double maxValue() {
            return 1.0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            throw new UnsupportedOperationException("test stub is never serialised");
        }
    }
}
