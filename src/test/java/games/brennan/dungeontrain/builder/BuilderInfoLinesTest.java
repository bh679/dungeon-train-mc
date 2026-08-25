package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.builder.BuilderInfoLines.Data;
import games.brennan.dungeontrain.builder.BuilderNewOptions.SubType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the info panel says.
 *
 * <p>An omitted line and a line that was never meant to exist look identical on screen, so which
 * lines appear for which build is pinned here rather than left to be noticed in a screenshot.</p>
 */
final class BuilderInfoLinesTest {

    /**
     * Stands in for the resource bundle: the two format keys return their real shape so the
     * placeholder substitution is exercised, everything else echoes its last key segment so
     * assertions read close to what a player sees.
     */
    private static final UnaryOperator<String> T = key -> switch (key) {
        case "gui.dungeontrain.builder.info.size" -> "%1$s x %2$s x %3$s";
        case "gui.dungeontrain.builder.info.weight" -> "weight %1$s";
        default -> key.substring(key.lastIndexOf('.') + 1);
    };

    private static Data carriage(String name, boolean dirty, String stage, int[] dims, int weight) {
        return new Data(name, dirty, BuilderMode.TRAIN_OUTSIDE, SubType.WHOLE_CARRIAGE, "",
                stage, dims, weight);
    }

    @Test
    @DisplayName("An unnamed build says so instead of showing a blank line")
    void draftIsNamed() {
        List<String> lines = BuilderInfoLines.of(carriage("", false, "", null, -1), T);
        assertEquals("draft", lines.get(0));
    }

    @Test
    @DisplayName("A named build shows its name as words, not as an id")
    void namedBuildIsPretty() {
        List<String> lines = BuilderInfoLines.of(carriage("crate_car", false, "", null, -1), T);
        assertEquals("Crate Car", lines.get(0));
    }

    @Test
    @DisplayName("Unsaved changes mark the name")
    void dirtyMarksTheName() {
        assertTrue(BuilderInfoLines.of(carriage("crate_car", true, "", null, -1), T).get(0)
                .endsWith(" *"));
        assertFalse(BuilderInfoLines.of(carriage("crate_car", false, "", null, -1), T).get(0)
                .endsWith(" *"));
    }

    @Test
    @DisplayName("No stage picked reads as Any rather than as an empty row")
    void noStageReadsAsAny() {
        assertEquals("stage_any", BuilderInfoLines.of(carriage("x", false, "", null, -1), T).get(2));
        assertEquals("Deep Nether",
                BuilderInfoLines.of(carriage("x", false, "deep_nether", null, -1), T).get(2));
    }

    @Test
    @DisplayName("A part's line carries its kind — that's what distinguishes two same-named parts")
    void partsLineCarriesTheKind() {
        Data data = new Data("sandstone", false, BuilderMode.TRAIN_OUTSIDE, SubType.PARTS,
                "walls", "", null, -1);
        assertEquals("parts — Walls", BuilderInfoLines.of(data, T).get(1));
    }

    @Test
    @DisplayName("A mode with no sub types shows just the mode, with no stage row")
    void trackModesAreTerse() {
        Data data = new Data("", false, BuilderMode.TRACKS_TUNNELS, SubType.WHOLE_CARRIAGE,
                "", "", null, -1);
        List<String> lines = BuilderInfoLines.of(data, T);
        assertEquals("tracks_tunnels", lines.get(1));
        assertEquals(2, lines.size(), "no stage and no size when nothing is parked");
    }

    @Test
    @DisplayName("Size only appears when there is something parked to measure")
    void sizeNeedsAVolume() {
        List<String> without = BuilderInfoLines.of(carriage("x", false, "", null, -1), T);
        assertEquals(3, without.size(), "no size row rather than a row of zeros");

        List<String> with = BuilderInfoLines.of(
                carriage("x", false, "", new int[] { 16, 6, 5 }, -1), T);
        assertEquals("16 x 6 x 5", with.get(3));
    }

    @Test
    @DisplayName("A zero-sized volume is treated as no volume at all")
    void degenerateVolumesAreDropped() {
        List<String> lines = BuilderInfoLines.of(
                carriage("x", false, "", new int[] { 0, 6, 5 }, -1), T);
        assertEquals(3, lines.size());
    }

    @Test
    @DisplayName("Weight shows for a saved carriage and is omitted when it doesn't apply")
    void weightIsConditional() {
        List<String> withWeight = BuilderInfoLines.of(
                carriage("x", false, "", new int[] { 16, 6, 5 }, 18), T);
        assertTrue(withWeight.get(3).contains("18"));

        List<String> withoutWeight = BuilderInfoLines.of(
                carriage("x", false, "", new int[] { 16, 6, 5 }, -1), T);
        assertFalse(withoutWeight.get(3).contains("weight"));
    }

    @Test
    @DisplayName("Weight alone still gets a row when there's no size to pair it with")
    void weightWithoutSize() {
        List<String> lines = BuilderInfoLines.of(carriage("x", false, "", null, 7), T);
        assertEquals(4, lines.size());
        assertTrue(lines.get(3).contains("7"));
    }

    // ---- the two halves are disjoint ----

    @Test
    @DisplayName("Identity and metrics don't overlap — two panels show them, and neither repeats")
    void theHalvesAreDisjoint() {
        // The whole reason they're separate calls: the pause menu draws identity over the main
        // column and metrics over the tools panel, so anything appearing in both is duplicated on
        // screen with no way to tell from either call site.
        Data data = carriage("crate_car", false, "desert", new int[] { 16, 6, 5 }, 18);
        List<String> identity = BuilderInfoLines.identity(data, T);
        List<String> metrics = BuilderInfoLines.metrics(data, T);

        for (String line : identity) {
            assertFalse(metrics.contains(line), line);
        }
        assertEquals(BuilderInfoLines.of(data, T).size(), identity.size() + metrics.size(),
                "together they are the whole read-out, with nothing dropped or doubled");
    }

    @Test
    @DisplayName("A build with nothing to measure yields no metrics rows at all")
    void metricsCanBeEmpty() {
        assertTrue(BuilderInfoLines.metrics(carriage("", false, "", null, -1), T).isEmpty());
    }

    @Test
    @DisplayName("Identity always leads with the name, so callers can colour row 0")
    void identityLeadsWithTheName() {
        assertEquals("draft", BuilderInfoLines.identity(carriage("", false, "", null, -1), T).get(0));
        assertEquals("Crate Car",
                BuilderInfoLines.identity(carriage("crate_car", false, "", null, -1), T).get(0));
    }
}
