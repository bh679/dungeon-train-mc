package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic coverage for the group-strip half of {@link TemplateDeletes}: when a template is deleted,
 * which group sidecars change and how. The file-system half (source-tree deletes, weights, sidecars)
 * hangs off {@code FMLPaths} and is verified in-game.
 */
final class TemplateDeletesGroupStripTest {

    private static CarriageContentsGroup contentsGroup(String... ids) {
        List<CarriageContentsGroup.Member> ms = new ArrayList<>();
        for (String id : ids) ms.add(new CarriageContentsGroup.Member(id, 1));
        return new CarriageContentsGroup(ms);
    }

    private static TrackVariantGroup trackGroup(String... ids) {
        List<TrackVariantGroup.Member> ms = new ArrayList<>();
        for (String id : ids) ms.add(new TrackVariantGroup.Member(id, 1));
        return new TrackVariantGroup(ms);
    }

    @Test
    @DisplayName("A member is taken out of the one group that lists it; the others are untouched")
    void memberLeavesItsParentOnly() {
        Map<String, CarriageContentsGroup> groups = Map.of(
            "traps", contentsGroup("dangerous", "mild"),
            "armor", contentsGroup("armor2", "armor3"));

        Map<String, Optional<CarriageContentsGroup>> edits = TemplateDeletes.contentsGroupEdits(
            "dangerous", groups.keySet(), p -> Optional.ofNullable(groups.get(p)));

        assertEquals(List.of("traps"), new ArrayList<>(edits.keySet()), "only the listing parent changes");
        CarriageContentsGroup traps = edits.get("traps").orElseThrow();
        assertEquals(List.of("mild"), traps.members().stream().map(CarriageContentsGroup.Member::id).toList());
    }

    @Test
    @DisplayName("Removing the last member marks the parent's group file for deletion")
    void lastMemberEmptiesTheGroup() {
        Map<String, CarriageContentsGroup> groups = Map.of("traps", contentsGroup("dangerous"));

        Map<String, Optional<CarriageContentsGroup>> edits = TemplateDeletes.contentsGroupEdits(
            "dangerous", groups.keySet(), p -> Optional.ofNullable(groups.get(p)));

        assertTrue(edits.containsKey("traps"));
        assertTrue(edits.get("traps").isEmpty(), "empty = delete the sidecar, parent reverts to a leaf");
    }

    @Test
    @DisplayName("A template that is in no group produces no edits")
    void nonMemberChangesNothing() {
        Map<String, CarriageContentsGroup> groups = Map.of("traps", contentsGroup("dangerous", "mild"));

        Map<String, Optional<CarriageContentsGroup>> edits = TemplateDeletes.contentsGroupEdits(
            "vase", groups.keySet(), p -> Optional.ofNullable(groups.get(p)));

        assertTrue(edits.isEmpty());
    }

    @Test
    @DisplayName("The deleted id's own group is not rewritten here — that is the store delete's job")
    void ownGroupIsSkipped() {
        Map<String, CarriageContentsGroup> groups = Map.of("traps", contentsGroup("traps", "mild"));

        Map<String, Optional<CarriageContentsGroup>> edits = TemplateDeletes.contentsGroupEdits(
            "traps", groups.keySet(), p -> Optional.ofNullable(groups.get(p)));

        assertFalse(edits.containsKey("traps"));
    }

    @Test
    @DisplayName("Parents with no group sidecar are skipped, not treated as empty groups")
    void parentsWithoutAGroupAreIgnored() {
        Map<String, CarriageContentsGroup> groups = Map.of("traps", contentsGroup("dangerous"));

        Map<String, Optional<CarriageContentsGroup>> edits = TemplateDeletes.contentsGroupEdits(
            "dangerous", List.of("leaf", "traps"), p -> Optional.ofNullable(groups.get(p)));

        assertEquals(List.of("traps"), new ArrayList<>(edits.keySet()));
    }

    @Test
    @DisplayName("Track-side twin behaves the same: member out, last member empties, others untouched")
    void trackTwinMatches() {
        Map<String, TrackVariantGroup> groups = Map.of(
            "labrynth", trackGroup("maze1", "maze2"),
            "patterns", trackGroup("maze1"));

        Map<String, Optional<TrackVariantGroup>> edits = TemplateDeletes.trackGroupEdits(
            "maze1", List.of("labrynth", "patterns", "maze1"), p -> Optional.ofNullable(groups.get(p)));

        assertEquals(List.of("maze2"),
            edits.get("labrynth").orElseThrow().members().stream().map(TrackVariantGroup.Member::id).toList());
        assertTrue(edits.get("patterns").isEmpty(), "maze1 was its only member");
        assertEquals(2, edits.size());
    }
}
