package games.brennan.dungeontrain.client.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clamp the tiles-per-row chip steps through.
 *
 * <p>The chip itself needs a running client to instantiate, so what is tested here is the pure
 * arithmetic it delegates to — the same split the other builder widget tests make. The property
 * that matters is that the number on the chip and the number of columns the grid lays out are
 * always the same number: they come from two different call sites (the button's label and
 * {@code BuilderTemplateGridLayout.of}) and a disagreement between them is a chip that lies.</p>
 */
final class BuilderTilesPerRowButtonTest {

    private static final int TOP = 120;
    private static final int BOTTOM = 400;

    private static final int[] WIDTHS = {3440, 1920, 960, 854, 640, 480, 320};

    /** What {@code effectiveColumns} does, minus the config read it can't do without a client. */
    private static int effective(int screenWidth, int stored) {
        return Math.max(BuilderTemplateGridLayout.MIN_COLUMNS,
                Math.min(BuilderTemplateGridLayout.maxColumnsFor(screenWidth), stored));
    }

    @Test
    @DisplayName("What the chip reads is what the grid lays out")
    void chipAgreesWithTheGrid() {
        for (int width : WIDTHS) {
            for (int stored = BuilderTemplateGridLayout.MIN_COLUMNS;
                 stored <= BuilderTemplateGridLayout.MAX_COLUMNS; stored++) {
                int shown = effective(width, stored);
                int laidOut = BuilderTemplateGridLayout.of(width, TOP, BOTTOM, 24, stored).columns();
                assertEquals(laidOut, shown,
                        "chip and grid disagree at width " + width + ", stored " + stored);
            }
        }
    }

    /** What {@code step} resolves to, minus the config write it can't do without a client. */
    private static int stepped(int screenWidth, int current, int delta) {
        int min = BuilderTemplateGridLayout.MIN_COLUMNS;
        int max = BuilderTemplateGridLayout.maxColumnsFor(screenWidth);
        return min + Math.floorMod(current - min + delta, max - min + 1);
    }

    @Test
    @DisplayName("The count cycles round at both ends")
    void steppingCyclesAtBothEnds() {
        int width = 1920;   // roomy enough to hold the whole range
        int min = BuilderTemplateGridLayout.MIN_COLUMNS;
        int max = BuilderTemplateGridLayout.MAX_COLUMNS;
        assertEquals(max, BuilderTemplateGridLayout.maxColumnsFor(width));

        assertEquals(min, stepped(width, max, +1), "past the top should wrap to the bottom");
        assertEquals(max, stepped(width, min, -1), "below the bottom should wrap to the top");

        // Every step in between is an ordinary one.
        for (int at = min; at < max; at++) {
            assertEquals(at + 1, stepped(width, at, +1));
            assertEquals(at, stepped(width, at + 1, -1));
        }
    }

    @Test
    @DisplayName("A full lap returns to where it started, both ways")
    void aFullLapIsIdentity() {
        for (int width : WIDTHS) {
            int span = BuilderTemplateGridLayout.maxColumnsFor(width)
                    - BuilderTemplateGridLayout.MIN_COLUMNS + 1;
            for (int start = BuilderTemplateGridLayout.MIN_COLUMNS;
                 start <= BuilderTemplateGridLayout.maxColumnsFor(width); start++) {
                int up = start;
                int down = start;
                for (int i = 0; i < span; i++) {
                    up = stepped(width, up, +1);
                    down = stepped(width, down, -1);
                }
                assertEquals(start, up, "a lap up from " + start + " at width " + width);
                assertEquals(start, down, "a lap down from " + start + " at width " + width);
            }
        }
    }

    @Test
    @DisplayName("Cycling stays inside what the window can actually draw")
    void cyclingNeverLeavesTheUsableRange() {
        for (int width : WIDTHS) {
            int max = BuilderTemplateGridLayout.maxColumnsFor(width);
            int at = BuilderTemplateGridLayout.MIN_COLUMNS;
            for (int i = 0; i < 40; i++) {
                at = stepped(width, at, i % 3 == 0 ? -1 : +1);
                assertTrue(at >= BuilderTemplateGridLayout.MIN_COLUMNS && at <= max,
                        "stepped to " + at + " at width " + width + ", which holds at most " + max);
            }
        }
    }

    @Test
    @DisplayName("A window too narrow for the stored count steps down from what is shown")
    void steppingDownFromASaturatedCountSkipsNothing() {
        // At 320 the range saturates at five, so a stored six shows as five. Stepping down from it
        // has to reach four — stepping down from the *stored* six would land on a five that draws
        // identically to what is already there, and the click would look like it did nothing.
        int width = 320;
        assertEquals(5, BuilderTemplateGridLayout.maxColumnsFor(width));
        int shown = effective(width, 6);
        assertEquals(5, shown);
        assertEquals(4, effective(width, shown - 1));
    }

    @Test
    @DisplayName("Every width has a usable count, however narrow")
    void everyWidthResolvesToSomethingUsable() {
        for (int width = 64; width <= 3440; width += 37) {
            int columns = BuilderTemplateGridLayout.maxColumnsFor(width);
            assertTrue(columns >= BuilderTemplateGridLayout.MIN_COLUMNS
                            && columns <= BuilderTemplateGridLayout.MAX_COLUMNS,
                    "column count out of range at width " + width + ": " + columns);
        }
    }
}
