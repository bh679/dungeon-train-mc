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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ParentDeletes} is the pure half of deleting a group parent. What must hold: unparenting
 * carries each member record to the top level (first Stage link only, and it says how many were
 * dropped), and promotion hands the first member the rest of the group with its own former weight
 * as the new {@code selfWeight}. No filesystem, no Forge.
 */
final class ParentDeletesTest {

    private static final TemplateGate GATE = new TemplateGate(5, 30, EnumSet.of(TrainPhase.NETHER));

    @Test
    @DisplayName("mode literals round-trip and anything else is empty")
    void modeParse() {
        assertEquals(Optional.of(ParentDeletes.Mode.ALL), ParentDeletes.Mode.parse("all"));
        assertEquals(Optional.of(ParentDeletes.Mode.UNPARENT), ParentDeletes.Mode.parse(" Unparent "));
        assertEquals(Optional.of(ParentDeletes.Mode.PROMOTE_FIRST), ParentDeletes.Mode.parse("promote"));
        assertEquals(Optional.empty(), ParentDeletes.Mode.parse("first"));
        assertEquals(Optional.empty(), ParentDeletes.Mode.parse(null));
        assertEquals("all|unparent|promote", ParentDeletes.Mode.literals());
    }

    @Test
    @DisplayName("contents unparent: weight + gate + first stage travel, extra stages are counted")
    void contentsUnparentCarriesTheRecord() {
        CarriageContentsGroup g = new CarriageContentsGroup(3, List.of(
            new CarriageContentsGroup.Member("copper", 7, GATE, List.of("late", "deep")),
            new CarriageContentsGroup.Member("stone", 2)));

        List<ParentDeletes.TopLevel> out = ParentDeletes.unparentContents(g);

        assertEquals(2, out.size());
        ParentDeletes.TopLevel copper = out.get(0);
        assertEquals("copper", copper.id());
        assertEquals(7, copper.weight());
        assertEquals(GATE, copper.gate());
        assertEquals("late", copper.stageId());
        assertEquals(1, copper.droppedStages());
        ParentDeletes.TopLevel stone = out.get(1);
        assertEquals(TemplateGate.DEFAULT, stone.gate());
        assertNull(stone.stageId());
        assertEquals(0, stone.droppedStages());
    }

    @Test
    @DisplayName("track unparent: same shape as contents")
    void trackUnparentCarriesTheRecord() {
        TrackVariantGroup g = new TrackVariantGroup(1, List.of(
            new TrackVariantGroup.Member("hall", 4, GATE, List.of("early"))));

        List<ParentDeletes.TopLevel> out = ParentDeletes.unparentTrack(g);

        assertEquals(1, out.size());
        assertEquals("hall", out.get(0).id());
        assertEquals(4, out.get(0).weight());
        assertEquals("early", out.get(0).stageId());
        assertEquals(0, out.get(0).droppedStages());
    }

    @Test
    @DisplayName("contents promote: first member heads the rest, its weight becomes selfWeight")
    void contentsPromoteFirst() {
        CarriageContentsGroup.Member copper = new CarriageContentsGroup.Member("copper", 7, GATE, List.of("late"));
        CarriageContentsGroup.Member stone = new CarriageContentsGroup.Member("stone", 2);
        CarriageContentsGroup.Member quartz = new CarriageContentsGroup.Member("quartz", 5);
        CarriageContentsGroup g = new CarriageContentsGroup(3, List.of(copper, stone, quartz));

        ParentDeletes.ContentsPromotion p = ParentDeletes.promoteContents(g).orElseThrow();

        assertEquals("copper", p.newParent());
        CarriageContentsGroup rest = p.group().orElseThrow();
        assertEquals(7, rest.selfWeight());
        assertEquals(List.of(stone, quartz), rest.members());
    }

    @Test
    @DisplayName("promote a single-member group: the member becomes a plain leaf")
    void promoteSingleMemberLeavesNoGroup() {
        CarriageContentsGroup g = new CarriageContentsGroup(1, List.of(new CarriageContentsGroup.Member("only", 3)));
        ParentDeletes.ContentsPromotion p = ParentDeletes.promoteContents(g).orElseThrow();
        assertEquals("only", p.newParent());
        assertTrue(p.group().isEmpty());

        TrackVariantGroup t = new TrackVariantGroup(1, List.of(new TrackVariantGroup.Member("solo", 3)));
        ParentDeletes.TrackPromotion tp = ParentDeletes.promoteTrack(t).orElseThrow();
        assertEquals("solo", tp.newParent());
        assertTrue(tp.group().isEmpty());
    }

    @Test
    @DisplayName("track promote: rest keeps sidecar order and records")
    void trackPromoteFirst() {
        TrackVariantGroup.Member a = new TrackVariantGroup.Member("a", 9);
        TrackVariantGroup.Member b = new TrackVariantGroup.Member("b", 1, GATE, List.of("deep", "late"));
        TrackVariantGroup.Member c = new TrackVariantGroup.Member("c", 4);
        TrackVariantGroup g = new TrackVariantGroup(0, List.of(a, b, c));

        ParentDeletes.TrackPromotion p = ParentDeletes.promoteTrack(g).orElseThrow();

        assertEquals("a", p.newParent());
        assertEquals(9, p.group().orElseThrow().selfWeight());
        assertEquals(List.of(b, c), p.group().orElseThrow().members());
    }

    @Test
    @DisplayName("promote an empty group: nothing to promote")
    void promoteEmpty() {
        assertTrue(ParentDeletes.promoteContents(CarriageContentsGroup.EMPTY).isEmpty());
        assertTrue(ParentDeletes.promoteTrack(TrackVariantGroup.EMPTY).isEmpty());
    }
}
