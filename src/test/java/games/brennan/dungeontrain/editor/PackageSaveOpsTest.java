package games.brennan.dungeontrain.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the "Save must never destroy player content" contract for the
 * editor's package save.
 *
 * <p>The bug these tests exist for: Save used to move the working folder
 * and delete the old zip, so saving a package under a new name erased the
 * package it came from. {@link PackageSaveOps} replaced those primitives
 * with copy-and-verify plus an atomic zip swap; each test below pins one
 * of the properties that makes that safe.</p>
 *
 * <p>Path-based rather than going through {@link PackageRegistry}, which
 * resolves its roots via {@code FMLPaths} and would need a Forge
 * bootstrap. In-game verification covers the registry wiring.</p>
 */
final class PackageSaveOpsTest {

    @TempDir Path tmp;

    // ---- nameTaken ----

    @Test
    @DisplayName("nameTaken sees a zip with no extracted folder")
    void nameTakenSeesZipOnlyPackage() throws IOException {
        Path dtpacks = Files.createDirectories(tmp.resolve("dtpacks"));

        assertFalse(PackageSaveOps.nameTaken(dtpacks, "freename"), "unused name");

        // The regression that mattered: a dropped-in zip nobody has reloaded yet
        // is absent from PackageRegistry's list, so the old findByName() guard
        // said the name was free and the save deleted the zip.
        Files.writeString(dtpacks.resolve("friend.zip"), "not really a zip");
        assertTrue(PackageSaveOps.nameTaken(dtpacks, "friend"), "zip without folder");

        Files.createDirectories(dtpacks.resolve("folderonly"));
        assertTrue(PackageSaveOps.nameTaken(dtpacks, "folderonly"), "folder without zip");

        Files.createDirectories(dtpacks.resolve("both"));
        Files.writeString(dtpacks.resolve("both.zip"), "x");
        assertTrue(PackageSaveOps.nameTaken(dtpacks, "both"), "folder and zip");
    }

    // ---- copyTree / verifyTree ----

    @Test
    @DisplayName("copyTree reproduces the tree and leaves the source intact")
    void copyTreeIsNonDestructive() throws IOException {
        Path from = tmp.resolve("user");
        writeFile(from.resolve("templates/carriage_a.nbt"), "aaa");
        writeFile(from.resolve("parts/roof/part_b.nbt"), "bbbb");
        writeFile(from.resolve("weights.properties"), "w=1");

        Path to = tmp.resolve("dtpacks/packa");
        PackageSaveOps.CopyReport report = PackageSaveOps.copyTree(from, to);

        assertTrue(report.clean(), "no failures: " + report.failures());
        assertEquals(3, report.copied());
        assertEquals(0, report.skippedExisting());
        assertEquals("aaa", Files.readString(to.resolve("templates/carriage_a.nbt")));
        assertEquals("bbbb", Files.readString(to.resolve("parts/roof/part_b.nbt")));

        // The whole point: the source still has every file it started with.
        assertTrue(Files.isRegularFile(from.resolve("templates/carriage_a.nbt")));
        assertTrue(Files.isRegularFile(from.resolve("parts/roof/part_b.nbt")));
        assertTrue(Files.isRegularFile(from.resolve("weights.properties")));
        assertTrue(PackageSaveOps.verifyTree(from, to), "verify a complete copy");
    }

    @Test
    @DisplayName("copyTree never overwrites an existing destination file")
    void copyTreeSkipsExisting() throws IOException {
        Path from = tmp.resolve("user");
        writeFile(from.resolve("templates/shared.nbt"), "incoming");
        writeFile(from.resolve("templates/fresh.nbt"), "new");

        Path to = tmp.resolve("dtpacks/packa");
        writeFile(to.resolve("templates/shared.nbt"), "already here");

        PackageSaveOps.CopyReport report = PackageSaveOps.copyTree(from, to);

        assertTrue(report.clean());
        assertEquals(1, report.copied());
        assertEquals(1, report.skippedExisting());
        assertEquals("already here", Files.readString(to.resolve("templates/shared.nbt")),
            "existing destination content wins");
        assertEquals("new", Files.readString(to.resolve("templates/fresh.nbt")));
    }

    @Test
    @DisplayName("verifyTree fails when a file didn't make it across")
    void verifyTreeCatchesMissingFile() throws IOException {
        Path from = tmp.resolve("user");
        writeFile(from.resolve("templates/a.nbt"), "aaa");
        writeFile(from.resolve("templates/b.nbt"), "bbb");

        Path to = tmp.resolve("dtpacks/packa");
        PackageSaveOps.copyTree(from, to);
        Files.delete(to.resolve("templates/b.nbt"));

        assertFalse(PackageSaveOps.verifyTree(from, to), "missing file");

        // A truncated copy must fail too — size mismatch, not just absence.
        writeFile(to.resolve("templates/b.nbt"), "b");
        assertFalse(PackageSaveOps.verifyTree(from, to), "truncated file");
    }

    // ---- writeZipAtomically ----

    @Test
    @DisplayName("writeZipAtomically writes a manifest plus every file, and can replace itself")
    void writeZipProducesManifestAndEntries() throws IOException {
        Path source = tmp.resolve("dtpacks/packa");
        writeFile(source.resolve("templates/a.nbt"), "aaa");
        writeFile(source.resolve("parts/b.nbt"), "bbb");
        Path zip = tmp.resolve("dtpacks/packa.zip");

        assertEquals(2, PackageSaveOps.writeZipAtomically(source, zip));
        List<String> entries = entryNames(zip);
        assertTrue(entries.contains("manifest.json"), "manifest present: " + entries);
        assertTrue(entries.contains("templates/a.nbt"), entries.toString());
        assertTrue(entries.contains("parts/b.nbt"), entries.toString());
        assertFalse(Files.exists(tmp.resolve("dtpacks/packa.zip.tmp")), "temp file cleaned up");

        // Re-saving the same package rewrites the snapshot in place.
        writeFile(source.resolve("templates/c.nbt"), "ccc");
        assertEquals(3, PackageSaveOps.writeZipAtomically(source, zip));
        assertTrue(entryNames(zip).contains("templates/c.nbt"));
    }

    @Test
    @DisplayName("a failed zip write leaves the previous snapshot byte-identical")
    void failedZipWriteKeepsOldSnapshot() throws IOException {
        Path source = tmp.resolve("dtpacks/packa");
        writeFile(source.resolve("templates/a.nbt"), "aaa");
        Path zip = tmp.resolve("dtpacks/packa.zip");
        PackageSaveOps.writeZipAtomically(source, zip);
        byte[] before = Files.readAllBytes(zip);

        // Block the temp file the next write needs: a directory can't be opened
        // for writing, so the archive fails before anything replaces the target.
        Files.createDirectories(tmp.resolve("dtpacks/packa.zip.tmp"));
        assertThrows(IOException.class, () -> PackageSaveOps.writeZipAtomically(source, zip));

        assertArrayEquals(before, Files.readAllBytes(zip),
            "old snapshot survives a failed save untouched");
    }

    // ---- helpers ----

    private static void writeFile(Path path, String body) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, body, StandardCharsets.UTF_8);
    }

    private static List<String> entryNames(Path zip) throws IOException {
        List<String> out = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {
            var e = zf.entries();
            while (e.hasMoreElements()) {
                ZipEntry entry = e.nextElement();
                if (!entry.isDirectory()) out.add(entry.getName());
            }
        }
        return out;
    }
}
