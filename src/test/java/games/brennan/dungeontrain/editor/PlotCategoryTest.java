package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PlotCategory} — the type that replaces the raw category
 * strings the editor used to compare across roughly thirty files.
 *
 * <p>{@code capabilities_pinTheAllowlistsTheyReplaced} is the important one: each
 * predicate reproduces the exact string allowlist it was extracted from, so the
 * extraction can be reviewed against the old code without reading the whole
 * refactor.</p>
 */
final class PlotCategoryTest {

    @Test
    @DisplayName("fromId: accepts all three case conventions the concept travelled in")
    void fromId_isCaseInsensitive() {
        // "CARRIAGES" on the wire, "carriages" in commands, "Carriages" in the status HUD.
        assertEquals(Optional.of(PlotCategory.CARRIAGES), PlotCategory.fromId("CARRIAGES"));
        assertEquals(Optional.of(PlotCategory.CARRIAGES), PlotCategory.fromId("carriages"));
        assertEquals(Optional.of(PlotCategory.CARRIAGES), PlotCategory.fromId("Carriages"));
        assertEquals(Optional.of(PlotCategory.PARTS), PlotCategory.fromId("PARTS"));
        assertEquals(Optional.of(PlotCategory.PARTS), PlotCategory.fromId("parts"));
        assertEquals(Optional.of(PlotCategory.PARTS), PlotCategory.fromId("Parts"));
        assertEquals(Optional.of(PlotCategory.PORTALS), PlotCategory.fromId("  portals  "));
    }

    @Test
    @DisplayName("fromId: null, blank and unknown all yield empty rather than throwing")
    void fromId_isLenient() {
        assertFalse(PlotCategory.fromId(null).isPresent());
        assertFalse(PlotCategory.fromId("").isPresent());
        assertFalse(PlotCategory.fromId("   ").isPresent());
        assertFalse(PlotCategory.fromId("nonsense").isPresent());
    }

    @Test
    @DisplayName("the stages and builder sentinels are not categories")
    void fromId_rejectsTheSentinels() {
        // EditorTypeMenus writes "stages" into Variant.category for the Stages panel's rows,
        // which are not plots. It is filtered ahead of any category logic by isStagesMenu();
        // this pins that it would fall out harmlessly even if that check moved.
        assertFalse(PlotCategory.fromId("stages").isPresent(),
            "the Stages panel sentinel must never resolve to a category");
        // BuilderDirtyCheck uses "builder" as a snapshot-key namespace, never a category.
        assertFalse(PlotCategory.fromId("builder").isPresent());
    }

    @Test
    @DisplayName("id round-trips for every constant")
    void id_roundTrips() {
        for (PlotCategory c : PlotCategory.values()) {
            assertEquals(Optional.of(c), PlotCategory.fromId(c.id()),
                c + " does not round-trip through its own id");
            assertEquals(c.name().toLowerCase(java.util.Locale.ROOT), c.id());
        }
    }

    @Test
    @DisplayName("owner: parts are stamped as carriages, everything else is itself")
    void owner_partsBelongToCarriages() {
        // Matches EditorCategory.locate(), which reports CARRIAGES for a part plot.
        assertSame(EditorCategory.CARRIAGES, PlotCategory.PARTS.owner());
        for (PlotCategory c : PlotCategory.values()) {
            if (c == PlotCategory.PARTS) continue;
            assertEquals(c.name(), c.owner().name(), c + " should own itself");
        }
    }

    @Test
    @DisplayName("of: every stamping category widens, and never lands on PARTS")
    void of_isTotalOverEditorCategory() {
        for (EditorCategory e : EditorCategory.values()) {
            PlotCategory widened = PlotCategory.of(e);
            assertSame(e, widened.owner());
            assertFalse(widened == PlotCategory.PARTS);
        }
        // PARTS is exactly the one addressable value with no stamping counterpart.
        assertEquals(EditorCategory.values().length + 1, PlotCategory.values().length);
    }

    @Test
    @DisplayName("displayName stays in step with EditorCategory, plus Parts")
    void displayName_matchesEditorCategory() {
        for (EditorCategory e : EditorCategory.values()) {
            assertEquals(e.displayName(), PlotCategory.of(e).displayName());
        }
        assertEquals("Parts", PlotCategory.PARTS.displayName());
    }

    @Test
    @DisplayName("capability predicates reproduce the string allowlists they replaced")
    void capabilities_pinTheAllowlistsTheyReplaced() {
        // EditorPlotLabelsRenderer.hasActionRow was literally:
        //   "CARRIAGES".equals(c) || "CONTENTS".equals(c) || "TRACKS".equals(c) || "PORTALS".equals(c)
        assertEquals(
            EnumSet.of(PlotCategory.CARRIAGES, PlotCategory.CONTENTS,
                PlotCategory.TRACKS, PlotCategory.PORTALS),
            matching(PlotCategory::hasActionRow));

        // EditorPlotTeleport.weightCommandFor had arms for those same four, default -> null.
        assertEquals(
            EnumSet.of(PlotCategory.CARRIAGES, PlotCategory.CONTENTS,
                PlotCategory.TRACKS, PlotCategory.PORTALS),
            matching(PlotCategory::hasWeightPool));

        // levelCommandFor / phaseCommandFor / stageApplyCommandFor: same four arms.
        assertEquals(
            EnumSet.of(PlotCategory.CARRIAGES, PlotCategory.CONTENTS,
                PlotCategory.TRACKS, PlotCategory.PORTALS),
            matching(PlotCategory::hasGate));

        // The twelve !"PORTALS".equals(category) guards in EditorPlotTeleport.
        assertEquals(EnumSet.of(PlotCategory.PORTALS), matching(PlotCategory::hasRoomBox));

        // The five "PARTS".equals(variant.category()) tests in EditorTypeMenuRenderer.
        assertEquals(EnumSet.of(PlotCategory.PARTS), matching(PlotCategory::hasVisibilityToggle));
    }

    @Test
    @DisplayName("parts are addressable but pool-less — the shape that caused the dropped action")
    void parts_areAddressableButHaveNoPools() {
        assertFalse(PlotCategory.PARTS.hasActionRow());
        assertFalse(PlotCategory.PARTS.hasWeightPool());
        assertFalse(PlotCategory.PARTS.hasGate());
        assertTrue(PlotCategory.PARTS.hasVisibilityToggle());
    }

    private static Set<PlotCategory> matching(java.util.function.Predicate<PlotCategory> p) {
        EnumSet<PlotCategory> out = EnumSet.noneOf(PlotCategory.class);
        for (PlotCategory c : PlotCategory.values()) if (p.test(c)) out.add(c);
        return out;
    }
}
