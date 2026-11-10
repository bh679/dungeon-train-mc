package games.brennan.dungeontrain.ship.sable;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RapierNativePath#choose}: Sable's Rapier natives go to the usual file unless it is locked
 * (the 2026-11-10 Windows {@code AccessDeniedException} startup crash), then to a per-process copy.
 */
class RapierNativePathTest {

    private static final String NATIVE = "sable_rapier_x86_64_windows.dll";
    private static final long PID = 4242L;

    /** Deletes like the real filesystem, except for the given locked files. */
    private static RapierNativePath.Deleter lockedOn(final Set<Path> locked) {
        return path -> {
            if (locked.contains(path)) {
                throw new AccessDeniedException(path.toString());
            }
            Files.delete(path);
        };
    }

    @Test
    @DisplayName("no existing file → Sable's own path")
    void absent_keepsCanonical(@TempDir final Path dir) {
        final Path canonical = dir.resolve(NATIVE);
        assertEquals(canonical, RapierNativePath.choose(canonical, lockedOn(Set.of()), PID));
    }

    @Test
    @DisplayName("deletable file → deleted, Sable's own path")
    void deletable_deletedAndCanonical(@TempDir final Path dir) throws IOException {
        final Path canonical = Files.createFile(dir.resolve(NATIVE));
        assertEquals(canonical, RapierNativePath.choose(canonical, lockedOn(Set.of()), PID));
        assertFalse(Files.exists(canonical));
    }

    @Test
    @DisplayName("locked file → per-process sibling with the same extension, locked file untouched")
    void locked_fallsBackToPerProcessCopy(@TempDir final Path dir) throws IOException {
        final Path canonical = Files.createFile(dir.resolve(NATIVE));
        final Path chosen = RapierNativePath.choose(canonical, lockedOn(Set.of(canonical)), PID);
        assertEquals(dir.resolve("sable_rapier_x86_64_windows.dt-4242.dll"), chosen);
        assertTrue(Files.exists(canonical));
    }

    @Test
    @DisplayName("fallback name keeps .dylib / .so")
    void fallbackName_keepsExtension(@TempDir final Path dir) {
        assertEquals("sable_rapier_aarch64_macos.dt-7.dylib",
                RapierNativePath.fallbackFor(dir.resolve("sable_rapier_aarch64_macos.dylib"), 7).getFileName().toString());
        assertEquals("sable_rapier_x86_64_linux.dt-7.so",
                RapierNativePath.fallbackFor(dir.resolve("sable_rapier_x86_64_linux.so"), 7).getFileName().toString());
    }

    @Test
    @DisplayName("earlier per-process copies are removed, still-locked ones stay")
    void staleFallbacks_cleanedWhenDeletable(@TempDir final Path dir) throws IOException {
        final Path canonical = dir.resolve(NATIVE);
        final Path staleFree = Files.createFile(dir.resolve("sable_rapier_x86_64_windows.dt-1.dll"));
        final Path staleLocked = Files.createFile(dir.resolve("sable_rapier_x86_64_windows.dt-2.dll"));
        final Path otherArch = Files.createFile(dir.resolve("sable_rapier_aarch64_windows.dt-3.dll"));

        assertEquals(canonical, RapierNativePath.choose(canonical, lockedOn(Set.of(staleLocked)), PID));
        assertFalse(Files.exists(staleFree));
        assertTrue(Files.exists(staleLocked));
        assertTrue(Files.exists(otherArch));
    }

    @Test
    @DisplayName("unexpected failure → Sable's own path, never throws")
    void unexpected_keepsCanonical(@TempDir final Path dir) throws IOException {
        final Path canonical = Files.createFile(dir.resolve(NATIVE));
        final RapierNativePath.Deleter broken = path -> {
            throw new IllegalStateException("boom");
        };
        assertEquals(canonical, RapierNativePath.choose(canonical, broken, PID));
    }
}
