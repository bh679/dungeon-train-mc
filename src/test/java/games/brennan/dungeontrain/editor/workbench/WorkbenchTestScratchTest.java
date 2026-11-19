package games.brennan.dungeontrain.editor.workbench;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.workbench.WorkbenchTestScratch.Scene;
import games.brennan.dungeontrain.track.TrackTestPiece;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkbenchTestScratchTest {

    /** Every template kind's name rule — carriages, contents, groups and track names share it. */
    private static final Pattern NAME = Pattern.compile("^[a-z0-9_]{1,32}$");

    @Test
    @DisplayName("A scratch id is a legal template name for any staged id, and reads back as scratch")
    void scratchId_isLegalName() {
        for (String staged : List.of("mossy-cottage-12", "x-1", "a-very-long-build-name-that-goes-on-and-on-9999", "")) {
            String id = WorkbenchTestScratch.scratchIdFor(staged);
            assertTrue(NAME.matcher(id).matches(), id);
            assertTrue(WorkbenchTestScratch.isScratchId(id), id);
        }
        assertNotEquals(WorkbenchTestScratch.scratchIdFor("a-1"), WorkbenchTestScratch.scratchIdFor("a-2"));
        assertEquals(WorkbenchTestScratch.scratchIdFor("a-1"), WorkbenchTestScratch.scratchIdFor("a-1"));
    }

    @Test
    @DisplayName("Two staged ids with the same String.hashCode still get different scratch ids")
    void scratchId_survivesHashCodeCollision() {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        assertNotEquals(WorkbenchTestScratch.scratchIdFor("Aa"), WorkbenchTestScratch.scratchIdFor("BB"));
    }

    @Test
    @DisplayName("A room's sidecars are filed as a portal room whatever relay kind it arrived under")
    void sidecarKind_roomIsPortalRoom() {
        assertEquals(BuilderPhotoPaths.Kind.PORTAL_ROOM,
            WorkbenchTestScratch.sidecarKindFor(BuilderPhotoPaths.Kind.TRACK, Scene.PORTAL_ROOM));
        assertEquals(BuilderPhotoPaths.Kind.PORTAL_ROOM,
            WorkbenchTestScratch.sidecarKindFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, Scene.PORTAL_ROOM));
        assertEquals(BuilderPhotoPaths.Kind.TRACK,
            WorkbenchTestScratch.sidecarKindFor(BuilderPhotoPaths.Kind.TRACK, Scene.TRACK));
        assertEquals(BuilderPhotoPaths.Kind.CARRIAGE,
            WorkbenchTestScratch.sidecarKindFor(BuilderPhotoPaths.Kind.CARRIAGE, Scene.CARRIAGE));
    }

    @Test
    @DisplayName("Ordinary template names are never taken for scratch")
    void ordinaryNames_notScratch() {
        assertFalse(WorkbenchTestScratch.isScratchId("pen"));
        assertFalse(WorkbenchTestScratch.isScratchId("wbtes"));
        assertFalse(WorkbenchTestScratch.isScratchId(null));
    }

    @Test
    @DisplayName("Each kind with a test scene maps to it; parts and buildings have none")
    void sceneFor_perKind() {
        assertEquals(Scene.CARRIAGE, WorkbenchTestScratch.sceneFor(BuilderPhotoPaths.Kind.CARRIAGE, ""));
        assertEquals(Scene.CONTENTS, WorkbenchTestScratch.sceneFor(BuilderPhotoPaths.Kind.CONTENTS, ""));
        assertEquals(Scene.WHOLE_GROUP, WorkbenchTestScratch.sceneFor(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, ""));
        assertEquals(Scene.PORTAL_ROOM, WorkbenchTestScratch.sceneFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, ""));
        // A dimensional carriage can also arrive as a track of the portal-room sub kind.
        assertEquals(Scene.PORTAL_ROOM, WorkbenchTestScratch.sceneFor(BuilderPhotoPaths.Kind.TRACK, "portal_room"));
        for (TrackTestPiece piece : TrackTestPiece.values()) {
            assertEquals(Scene.TRACK, WorkbenchTestScratch.sceneFor(BuilderPhotoPaths.Kind.TRACK, piece.kind().id()),
                piece.name());
        }
        assertNull(WorkbenchTestScratch.sceneFor(BuilderPhotoPaths.Kind.TRACK, "nonsense"));
        assertNull(WorkbenchTestScratch.sceneFor(BuilderPhotoPaths.Kind.PART, "floor"));
        assertNull(WorkbenchTestScratch.sceneFor(BuilderPhotoPaths.Kind.BUILDING, ""));
        assertNull(WorkbenchTestScratch.sceneFor(null, ""));
    }

    @Test
    @DisplayName("A scratch stays up while any test names it — a reseed's back-then-in never drops it")
    void unused_keepsNamed() {
        String carriage = WorkbenchTestScratch.scratchIdFor("cart-1");
        String track = WorkbenchTestScratch.scratchIdFor("rail-2");
        String orphan = WorkbenchTestScratch.scratchIdFor("gone-3");
        List<String> named = List.of(carriage, "track:" + track, "crypt_hall");
        assertEquals(List.of(orphan), WorkbenchTestScratch.unused(List.of(carriage, track, orphan), named));
        assertEquals(List.of(carriage), WorkbenchTestScratch.unused(List.of(carriage), List.of()));
    }
}
