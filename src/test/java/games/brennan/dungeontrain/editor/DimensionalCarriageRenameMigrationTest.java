package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Upgrade behaviour of the portal-room → dimensional-carriage rename, against real files.
 *
 * <p>Drives {@link DimensionalCarriageRenameMigration#migrateRoot} rather than {@code runOnce()}:
 * the per-root pass is the whole of the logic, while {@code runOnce()} only decides which
 * directories to hand it, and that decision needs a Forge {@code FMLPaths} bootstrap. Sweeping the
 * right roots is covered by in-game verification; what a sweep <i>does</i> is covered here.</p>
 */
final class DimensionalCarriageRenameMigrationTest {

    private static final String LEGACY_DIR = "portals/room";
    private static final String NEW_DIR = "portals/dimensional_carriage";

    private static void write(Path file, String body) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("templates and every sidecar beside them move to the new subdir")
    void movesTemplatesAndSidecars(@TempDir Path root) throws Exception {
        write(root.resolve(LEGACY_DIR).resolve("library.nbt"), "blocks");
        write(root.resolve(LEGACY_DIR).resolve("library.variants.json"), "{variants}");
        write(root.resolve(LEGACY_DIR).resolve("library.group.json"), "{group}");
        write(root.resolve(LEGACY_DIR).resolve("library.contents-allow.json"), "{allow}");
        write(root.resolve(LEGACY_DIR).resolve("weights.json"), "{weights}");

        DimensionalCarriageRenameMigration.migrateRoot(root);

        Path dest = root.resolve(NEW_DIR);
        assertEquals("blocks", read(dest.resolve("library.nbt")));
        assertEquals("{variants}", read(dest.resolve("library.variants.json")));
        assertEquals("{group}", read(dest.resolve("library.group.json")));
        assertEquals("{allow}", read(dest.resolve("library.contents-allow.json")));
        assertEquals("{weights}", read(dest.resolve("weights.json")),
            "the per-kind weights file is what names every variant — it must travel too");

        assertFalse(Files.exists(root.resolve(LEGACY_DIR)),
            "the emptied legacy dir should be tidied away");
        assertTrue(Files.isDirectory(root.resolve("portals")),
            "the portals/ parent is the editor category folder and stays");
    }

    @Test
    @DisplayName("container pool filenames swap prefix, keeping the author's variant name")
    void renamesContainerPools(@TempDir Path root) throws Exception {
        write(root.resolve("containers/track_portal_room_library_dimension.contents.json"), "{a}");
        write(root.resolve("containers/track_portal_room_default.contents.json"), "{b}");
        // Another kind's pool, which must be left completely alone.
        write(root.resolve("containers/track_pillar_top_default.contents.json"), "{c}");

        DimensionalCarriageRenameMigration.migrateRoot(root);

        Path containers = root.resolve("containers");
        assertEquals("{a}",
            read(containers.resolve("track_dimensional_carriage_library_dimension.contents.json")),
            "a variant name containing underscores must survive intact");
        assertEquals("{b}", read(containers.resolve("track_dimensional_carriage_default.contents.json")));
        assertEquals("{c}", read(containers.resolve("track_pillar_top_default.contents.json")),
            "another kind's pool must not be touched");
        assertFalse(Files.exists(containers.resolve("track_portal_room_default.contents.json")));
    }

    @Test
    @DisplayName("an existing destination file is never clobbered")
    void skipsWhenDestinationExists(@TempDir Path root) throws Exception {
        write(root.resolve(LEGACY_DIR).resolve("library.nbt"), "old");
        write(root.resolve(NEW_DIR).resolve("library.nbt"), "new");
        write(root.resolve("containers/track_portal_room_default.contents.json"), "old-pool");
        write(root.resolve("containers/track_dimensional_carriage_default.contents.json"), "new-pool");

        DimensionalCarriageRenameMigration.migrateRoot(root);

        assertEquals("new", read(root.resolve(NEW_DIR).resolve("library.nbt")),
            "content authored after the rename wins");
        assertEquals("new-pool",
            read(root.resolve("containers/track_dimensional_carriage_default.contents.json")));
        assertEquals("old", read(root.resolve(LEGACY_DIR).resolve("library.nbt")),
            "the skipped source stays put rather than being silently dropped");
        assertEquals("old-pool",
            read(root.resolve("containers/track_portal_room_default.contents.json")));
    }

    @Test
    @DisplayName("running twice is a no-op the second time")
    void isIdempotent(@TempDir Path root) throws Exception {
        write(root.resolve(LEGACY_DIR).resolve("library.nbt"), "blocks");
        write(root.resolve("containers/track_portal_room_default.contents.json"), "{pool}");

        DimensionalCarriageRenameMigration.migrateRoot(root);
        DimensionalCarriageRenameMigration.migrateRoot(root);

        assertEquals("blocks", read(root.resolve(NEW_DIR).resolve("library.nbt")));
        assertEquals("{pool}",
            read(root.resolve("containers/track_dimensional_carriage_default.contents.json")));
    }

    @Test
    @DisplayName("a root with nothing to migrate is left alone")
    void ignoresUnrelatedRoot(@TempDir Path root) throws Exception {
        write(root.resolve("templates/cab.nbt"), "cab");

        DimensionalCarriageRenameMigration.migrateRoot(root);

        assertEquals("cab", read(root.resolve("templates/cab.nbt")));
        assertFalse(Files.exists(root.resolve("portals")),
            "no directories should be conjured for a root that never had any");
    }

    @Test
    @DisplayName("nested content under the legacy dir is walked, not skipped")
    void walksNestedContent(@TempDir Path root) throws Exception {
        write(root.resolve(LEGACY_DIR).resolve("sub/deep.nbt"), "deep");

        DimensionalCarriageRenameMigration.migrateRoot(root);

        assertEquals("deep", read(root.resolve(NEW_DIR).resolve("sub/deep.nbt")));
    }
}
