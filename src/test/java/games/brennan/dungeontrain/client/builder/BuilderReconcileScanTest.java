package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    @DisplayName("a build in the user folder is found, though no world has said custom content may load")
    void onDiskBuildFoundAtTitleScreen(@TempDir Path userRoot) throws IOException {
        // The title screen: no world has answered the custom-content prompt, so content is
        // suppressed. That gate emptied the old scan; the user folder must be read regardless.
        assertTrue(games.brennan.dungeontrain.cheat.EditorContentIntegrity.isSuppressed());
        touch(userRoot.resolve("portals/room/e2ecourtyard.nbt"));

        List<BuilderReconcileScan.Build> found = BuilderReconcileScan.localBuilds(userRoot::resolve);

        assertTrue(found.contains(
                new BuilderReconcileScan.Build(BuilderPhotoPaths.Kind.PORTAL_ROOM, "", "e2ecourtyard")),
                "found " + found);
    }

    @Test
    @DisplayName("only .nbt files directly in a store folder count as builds")
    void onlyTemplatesCount(@TempDir Path userRoot) throws IOException {
        touch(userRoot.resolve("portals/room/e2ecourtyard.json"));
        touch(userRoot.resolve("portals/room/nested/deep.nbt"));

        assertFalse(BuilderReconcileScan.localBuilds(userRoot::resolve).stream()
                .anyMatch(b -> b.kind() == BuilderPhotoPaths.Kind.PORTAL_ROOM));
    }

    @Test
    @DisplayName("a missing user folder is no builds, not an error")
    void missingFolder(@TempDir Path userRoot) {
        assertTrue(BuilderReconcileScan.localBuilds(userRoot.resolve("absent")::resolve).isEmpty());
    }

    private static void touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1});
    }
}
