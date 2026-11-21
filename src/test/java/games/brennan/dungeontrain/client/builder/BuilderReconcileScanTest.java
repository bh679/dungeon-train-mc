package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The title-screen restore must recognise a room the relay already has, however that row's sub kind
 * was spelt. When it did not, every portal room was uploaded again as a second, larger row.
 */
final class BuilderReconcileScanTest {

    private static final BuilderReconcileScan.Build ROOM =
            new BuilderReconcileScan.Build(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "courtyard");

    @Test
    @DisplayName("a room the editor uploaded (empty sub kind) is not offered again")
    void editorRoomRowCounts() {
        BuilderReconcileScan.Result result = BuilderReconcileScan.compare(List.of(ROOM), List.of(),
                Set.of(BuilderReconcileScan.keyFor("portal_room", null, "courtyard")), 1, 200);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("a room an older restore uploaded as portal_room / portal_room is not offered again")
    void legacyRoomRowCounts() {
        BuilderReconcileScan.Result result = BuilderReconcileScan.compare(List.of(ROOM), List.of(),
                Set.of(BuilderReconcileScan.keyFor("portal_room", "portal_room", "courtyard")), 1, 200);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("a room the relay does not list is still offered, and a part keeps its sub kind")
    void missingStillOffered() {
        BuilderReconcileScan.Build floor =
                new BuilderReconcileScan.Build(BuilderPhotoPaths.Kind.PART, "floor", "standard");
        BuilderReconcileScan.Result result = BuilderReconcileScan.compare(List.of(ROOM, floor), List.of(),
                Set.of(BuilderReconcileScan.keyFor("part", "door", "standard")), 1, 200);
        assertEquals(2, result.onDisk().size());
    }
}
