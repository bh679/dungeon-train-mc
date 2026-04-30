package games.brennan.dungeontrain.train;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TrainCarriageAppender#computeIndicesToSpawn} —
 * the pure decision helper that decides which carriage indices to
 * materialize this tick. The helper is JOML- / Forge-free so tests run
 * without a Minecraft bootstrap.
 *
 * <p>With the per-carriage architecture, the appender's "current high /
 * current low" are the train's max / min pIdx aggregated across its
 * carriage sub-levels (via {@link Trains#maxPIdx(java.util.List)} /
 * {@link Trains#minPIdx(java.util.List)}). The pure helper takes those
 * aggregated values directly, so the test covers the same decision
 * surface as before.</p>
 *
 * <p>Default fixture: {@code count = 10} → {@code halfBack = 4}, {@code halfFront = 5}.
 * Initial spawn at {@code pIdx = 0} produces a train whose
 * {@code (maxPIdx, minPIdx)} = (5, -4) — the same range
 * {@code TrainAssembler.spawnTrain} places carriages for.</p>
 */
final class TrainCarriageAppenderTest {

    private static final int HALF_BACK = 4;
    private static final int HALF_FRONT = 5;
    private static final int INITIAL_HIGH = 5;
    private static final int INITIAL_LOW = -4;

    @Test
    @DisplayName("no near players → empty list")
    void noPlayers_returnsEmpty() {
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            INITIAL_HIGH, INITIAL_LOW, HALF_BACK, HALF_FRONT, List.of());
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("player at spawn pIdx → no append (already covered)")
    void playerAtSpawnPidx_returnsEmpty() {
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            INITIAL_HIGH, INITIAL_LOW, HALF_BACK, HALF_FRONT, List.of(0));
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("player walks one boundary forward → spawn one new index ahead")
    void walkForwardOne_spawnsOneAhead() {
        // pIdx = 1 → needs [-3, 6]. Watermarks already cover [-4, 5]. New: 6.
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            INITIAL_HIGH, INITIAL_LOW, HALF_BACK, HALF_FRONT, List.of(1));
        assertEquals(List.of(6), out);
    }

    @Test
    @DisplayName("player walks one boundary backward → spawn one new index behind")
    void walkBackwardOne_spawnsOneBehind() {
        // pIdx = -1 → needs [-5, 4]. Watermarks cover [-4, 5]. New: -5.
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            INITIAL_HIGH, INITIAL_LOW, HALF_BACK, HALF_FRONT, List.of(-1));
        assertEquals(List.of(-5), out);
    }

    @Test
    @DisplayName("player jumps forward 10 → spawns ten indices ascending")
    void jumpForward10_spawnsTenAscending() {
        // pIdx = 10 → needs [6, 15]. From high=5, append 6..15 in order.
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            INITIAL_HIGH, INITIAL_LOW, HALF_BACK, HALF_FRONT, List.of(10));
        assertEquals(List.of(6, 7, 8, 9, 10, 11, 12, 13, 14, 15), out);
    }

    @Test
    @DisplayName("player jumps backward 10 → spawns ten indices descending")
    void jumpBackward10_spawnsTenDescending() {
        // pIdx = -10 → needs [-14, -5]. From low=-4, append -5..-14 descending.
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            INITIAL_HIGH, INITIAL_LOW, HALF_BACK, HALF_FRONT, List.of(-10));
        assertEquals(List.of(-5, -6, -7, -8, -9, -10, -11, -12, -13, -14), out);
    }

    @Test
    @DisplayName("two players, one ahead one behind → spawn both frontiers (forward first, then backward)")
    void twoPlayersBothFrontiers_spawnsBothInOrder() {
        // p1 at pIdx=3 needs [-1, 8] → forward append 6, 7, 8.
        // p2 at pIdx=-3 needs [-7, 2] → backward append -5, -6, -7.
        // Output: forward ascending, then backward descending.
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            INITIAL_HIGH, INITIAL_LOW, HALF_BACK, HALF_FRONT, List.of(3, -3));
        assertEquals(List.of(6, 7, 8, -5, -6, -7), out);
    }

    @Test
    @DisplayName("multiple players, one farthest forward → only its needed range matters")
    void multipleForward_outermostWins() {
        // p1 at pIdx=2 needs [-2, 7]. p2 at pIdx=12 needs [8, 17].
        // Outermost forward = 17. Backward needs union: min(-2, 8) = -2, no
        // backward append (low watermark already at -4 ≤ -2).
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            INITIAL_HIGH, INITIAL_LOW, HALF_BACK, HALF_FRONT, List.of(2, 12));
        assertEquals(List.of(6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17), out);
    }

    @Test
    @DisplayName("watermarks already past needed range → no spawn (monotonicity)")
    void watermarksAhead_noSpawn() {
        // Pretend we've already spawned out to 100 and back to -100.
        // Player retreats to pIdx = 0 → needs [-4, 5], well inside [-100, 100].
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            100, -100, HALF_BACK, HALF_FRONT, List.of(0));
        assertTrue(out.isEmpty());
    }

    @Test
    @DisplayName("count = 1 → half-back/front collapse, single-carriage windows still work")
    void countOne_singleCarriageWindow() {
        // count=1 → halfBack=0, halfFront=0. Player at pIdx=3 needs [3, 3].
        // From watermarks high=0, low=0 (a single-carriage spawn), append 1, 2, 3.
        List<Integer> out = TrainCarriageAppender.computeIndicesToSpawn(
            0, 0, 0, 0, List.of(3));
        assertEquals(List.of(1, 2, 3), out);
    }
}
