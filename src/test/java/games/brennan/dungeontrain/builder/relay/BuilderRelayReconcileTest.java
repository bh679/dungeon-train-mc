package games.brennan.dungeontrain.builder.relay;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which of a world's uploaded builds count as lost, and which tier each one lands in.
 *
 * <p>The sorting is where this feature can do harm rather than good: calling a build lost when it
 * isn't re-uploads a duplicate, and putting a deleted build in the on-disk tier resurrects it without
 * being asked. Both are silent, so both are pinned here.</p>
 */
final class BuilderRelayReconcileTest {

    @Test
    @DisplayName("a build the relay still lists is not missing")
    void ignoresBuildsTheRelayStillHas() {
        BuilderRelayReconcile.Scan scan = BuilderRelayReconcile.classify(
                recorded(Map.of(BuilderRelayBuilds.keyOf("carriage", "", "alpha"), 11)), Set.of(11), everywhere());

        assertTrue(scan.isEmpty());
    }

    @Test
    @DisplayName("a missing build whose file is on disk is offered by default")
    void sortsOnDiskBuildsIntoTheFirstTier() {
        BuilderRelayReconcile.Scan scan = BuilderRelayReconcile.classify(
                recorded(Map.of(BuilderRelayBuilds.keyOf("carriage", "", "alpha"), 11)), Set.of(), onDiskOnly());

        assertEquals(1, scan.onDisk().size());
        assertEquals(0, scan.backupOnly().size());
        BuilderRelayReconcile.Missing missing = scan.onDisk().get(0);
        assertEquals(BuilderPhotoPaths.Kind.CARRIAGE, missing.kind());
        assertEquals("alpha", missing.id());
        assertTrue(missing.onDisk());
    }

    @Test
    @DisplayName("a missing build with no file left is kept in the second tier, never the first")
    void sortsBackupOnlyBuildsIntoTheSecondTier() {
        // Deleting a build locally does not clear its relay record, so this may be a build the player
        // threw away on purpose. It must never ride along with the unambiguous ones.
        BuilderRelayReconcile.Scan scan = BuilderRelayReconcile.classify(
                recorded(Map.of(BuilderRelayBuilds.keyOf("carriage", "", "alpha"), 11)), Set.of(), backupOnly());

        assertEquals(0, scan.onDisk().size());
        assertEquals(1, scan.backupOnly().size());
        assertEquals(1, scan.total());
    }

    @Test
    @DisplayName("a missing build with no copy anywhere is not offered at all")
    void dropsBuildsWithNoCopy() {
        BuilderRelayReconcile.Scan scan = BuilderRelayReconcile.classify(
                recorded(Map.of(BuilderRelayBuilds.keyOf("carriage", "", "alpha"), 11)), Set.of(), nowhere());

        assertTrue(scan.isEmpty(), "there is nothing to offer a player about a build that exists nowhere");
    }

    @Test
    @DisplayName("a part keeps its sub kind, and an id containing a space survives the split")
    void takesKeysApartCorrectly() {
        BuilderRelayReconcile.Scan scan = BuilderRelayReconcile.classify(
                recorded(Map.of(BuilderRelayBuilds.keyOf("part", "floor", "wide plank"), 12)),
                Set.of(), onDiskOnly());

        BuilderRelayReconcile.Missing missing = scan.onDisk().get(0);
        assertEquals(BuilderPhotoPaths.Kind.PART, missing.kind());
        assertEquals("floor", missing.subKind());
        assertEquals("wide plank", missing.id(), "only the id may contain a space, and it keeps it");
    }

    @Test
    @DisplayName("a kind this build of the mod doesn't know is skipped rather than guessed at")
    void skipsUnknownKinds() {
        BuilderRelayReconcile.Scan scan = BuilderRelayReconcile.classify(
                recorded(Map.of(BuilderRelayBuilds.keyOf("teapot", "", "alpha"), 13)), Set.of(), everywhere());

        assertTrue(scan.isEmpty());
    }

    @Test
    @DisplayName("an unreachable relay is not an empty relay")
    void unreachableIsNotEmpty() {
        BuilderRelayReconcile.Scan scan = BuilderRelayReconcile.Scan.unreachable();

        assertTrue(scan.isEmpty());
        assertTrue(!scan.reachable(),
                "a relay that could not be asked must never read as a relay that lost everything");
    }

    // ---- helpers ----

    private static List<Map.Entry<String, BuilderRelayBuilds.Entry>> recorded(Map<String, Integer> byKey) {
        List<Map.Entry<String, BuilderRelayBuilds.Entry>> out = new ArrayList<>();
        byKey.forEach((key, id) ->
                out.add(Map.entry(key, new BuilderRelayBuilds.Entry(id, "secret", "token", false))));
        return out;
    }

    private static BuilderRelayReconcile.Locator locator(boolean onDisk, boolean inBackup) {
        return new BuilderRelayReconcile.Locator() {
            @Override
            public boolean onDisk(BuilderPhotoPaths.Kind kind, String subKind, String id) {
                return onDisk;
            }

            @Override
            public boolean inBackup(BuilderPhotoPaths.Kind kind, String subKind, String id) {
                return inBackup;
            }
        };
    }

    private static BuilderRelayReconcile.Locator everywhere() {
        return locator(true, true);
    }

    private static BuilderRelayReconcile.Locator onDiskOnly() {
        return locator(true, false);
    }

    private static BuilderRelayReconcile.Locator backupOnly() {
        return locator(false, true);
    }

    private static BuilderRelayReconcile.Locator nowhere() {
        return locator(false, false);
    }
}
