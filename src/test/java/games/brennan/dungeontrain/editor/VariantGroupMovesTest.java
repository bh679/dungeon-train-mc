package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.worldgen.TrainPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VariantGroupMoves} is the pure half of {@code group move}: the thing that must hold is that
 * a re-parented member keeps its weight, gate and Stage links, and that the refusals match the
 * ones {@code group add} makes. No filesystem, no Forge.
 */
final class VariantGroupMovesTest {

    private static final TemplateGate GATE = new TemplateGate(5, 30, EnumSet.of(TrainPhase.NETHER));

    private static TrackVariantGroup trackGroup(TrackVariantGroup.Member... members) {
        return new TrackVariantGroup(2, List.of(members));
    }

    @Test
    @DisplayName("track: the member record travels whole — weight, gate, stages — and leaves the source")
    void trackMoveCarriesTheMember() {
        TrackVariantGroup.Member copper = new TrackVariantGroup.Member("copper", 7, GATE, List.of("late", "deep"));
        TrackVariantGroup house = trackGroup(copper, new TrackVariantGroup.Member("stone", 1));
        TrackVariantGroup book = trackGroup(new TrackVariantGroup.Member("tome", 3));

        VariantGroupMoves.TrackMove m = VariantGroupMoves.move(
            "house", house, "book", Optional.of(book), "copper", false, false);

        assertTrue(m.ok());
        assertEquals(-1, m.from().indexOf("copper"));
        assertEquals(1, m.from().members().size());
        assertEquals(2, m.from().selfWeight(), "the source group's own weight is untouched");
        TrackVariantGroup.Member moved = m.to().member("copper").orElseThrow();
        assertEquals(7, moved.weight());
        assertEquals(GATE, moved.gate());
        assertEquals(List.of("late", "deep"), moved.stageIds());
        assertEquals(List.of("tome", "copper"), m.to().members().stream().map(TrackVariantGroup.Member::id).toList(),
            "appended after the target's existing members");
    }

    @Test
    @DisplayName("track: a target with no group yet gets a fresh one holding just the member")
    void trackMoveIntoLeafCreatesGroup() {
        TrackVariantGroup house = trackGroup(new TrackVariantGroup.Member("copper", 4));
        VariantGroupMoves.TrackMove m = VariantGroupMoves.move(
            "house", house, "beam", Optional.empty(), "copper", false, false);
        assertTrue(m.ok());
        assertTrue(m.from().isEmpty(), "last member out — caller deletes the empty sidecar");
        assertEquals(1, m.to().members().size());
        assertEquals(4, m.to().member("copper").orElseThrow().weight());
    }

    @Test
    @DisplayName("refusals: not a member / same parent / self / target is a child / child is a parent")
    void trackRefusals() {
        TrackVariantGroup house = trackGroup(new TrackVariantGroup.Member("copper", 1));
        assertEquals(VariantGroupMoves.Refusal.NOT_A_MEMBER,
            VariantGroupMoves.move("house", house, "book", Optional.empty(), "granite", false, false).refusal());
        assertEquals(VariantGroupMoves.Refusal.NOT_A_MEMBER,
            VariantGroupMoves.move("house", (TrackVariantGroup) null, "book", Optional.empty(), "copper", false, false).refusal());
        assertEquals(VariantGroupMoves.Refusal.SAME_PARENT,
            VariantGroupMoves.move("house", house, "HOUSE", Optional.of(house), "copper", false, false).refusal());
        assertEquals(VariantGroupMoves.Refusal.SELF,
            VariantGroupMoves.move("house", house, "copper", Optional.empty(), "copper", false, false).refusal());
        assertEquals(VariantGroupMoves.Refusal.TARGET_IS_CHILD,
            VariantGroupMoves.move("house", house, "stone", Optional.empty(), "copper", true, false).refusal());
        assertEquals(VariantGroupMoves.Refusal.CHILD_IS_PARENT,
            VariantGroupMoves.move("house", house, "book", Optional.empty(), "copper", false, true).refusal());
        VariantGroupMoves.TrackMove refused =
            VariantGroupMoves.move("house", house, "book", Optional.empty(), "granite", false, false);
        assertFalse(refused.ok());
        assertNull(refused.from());
        assertNull(refused.to());
    }

    @Test
    @DisplayName("contents: same contract on the contents group record")
    void contentsMoveCarriesTheMember() {
        CarriageContentsGroup.Member copper = new CarriageContentsGroup.Member("copper", 9, GATE, List.of("late"));
        CarriageContentsGroup maze = new CarriageContentsGroup(3, List.of(copper,
            new CarriageContentsGroup.Member("desert", 1)));
        VariantGroupMoves.ContentsMove m = VariantGroupMoves.move(
            "maze", maze, "shop", Optional.empty(), "copper", false, false);
        assertTrue(m.ok());
        assertTrue(m.from().member("copper").isEmpty());
        assertEquals(3, m.from().selfWeight());
        CarriageContentsGroup.Member moved = m.to().member("copper").orElseThrow();
        assertEquals(9, moved.weight());
        assertEquals(GATE, moved.gate());
        assertEquals(List.of("late"), moved.stageIds());
        assertEquals(VariantGroupMoves.Refusal.SELF,
            VariantGroupMoves.move("maze", maze, "copper", Optional.empty(), "copper", false, false).refusal());
    }
}
