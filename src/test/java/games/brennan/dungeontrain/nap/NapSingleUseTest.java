package games.brennan.dungeontrain.nap;

import games.brennan.dungeontrain.data.InstallKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static games.brennan.dungeontrain.nap.NapTestFiles.napAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NapStore}: a nap wakes once, whatever happens to the files around it. */
final class NapSingleUseTest {

    private static final String SLOT = NapSlot.key(false, Difficulty.HARD);

    @TempDir Path root;
    private Path keyDir;
    private NapStore store;
    private Path dir;
    private final UUID player = UUID.randomUUID();

    @BeforeEach
    void setUp() throws IOException {
        keyDir = Files.createDirectories(root.resolve("dungeontrain"));
        InstallKey.useDirectoryForTests(keyDir);
        store = new NapStore(keyDir);
        dir = keyDir.resolve(NapStore.DIR);
    }

    @AfterEach
    void tearDown() {
        InstallKey.resetForTests();
    }

    private Path list() {
        return dir.resolve(NapAppliedLog.FILE);
    }

    private Path keep(NapSnapshot nap) throws IOException {
        Path copy = root.resolve("copy-" + nap.napId() + ".dat");
        Files.copy(store.fileFor(player, SLOT), copy);
        return copy;
    }

    private void putBack(Path copy) throws IOException {
        Files.copy(copy, store.fileFor(player, SLOT), StandardCopyOption.REPLACE_EXISTING);
    }

    @Test
    @DisplayName("a failed mark still removes the nap file, and the failure is thrown")
    void failedMarkRemovesFile() throws Exception {
        NapSnapshot n = napAt("World 1", 10L);
        store.save(player, SLOT, n);
        assertEquals(n, store.load(player, SLOT).orElseThrow());
        Files.createDirectories(dir.resolve(NapAppliedLog.FILE + ".tmp")); // every list write now fails

        assertThrows(IOException.class, () -> store.consume(player, SLOT, n));
        assertFalse(store.hasFile(player, SLOT), "the woken nap's file is gone even though the mark failed");
        assertFalse(store.load(player, SLOT).isPresent());
    }

    @Test
    @DisplayName("a list that can't be written fails the save")
    void unwritableListFailsSave() throws Exception {
        Files.createDirectories(dir.resolve(NapAppliedLog.FILE + ".tmp"));
        assertThrows(IOException.class, () -> store.save(player, SLOT, napAt("World 2", 10L)));
        assertFalse(store.hasFile(player, SLOT));
    }

    @Test
    @DisplayName("deleting the list and putting a copy back does not wake it again")
    void deletedListRefusesCopy() throws Exception {
        NapSnapshot n = napAt("World 3", 10L);
        store.save(player, SLOT, n);
        Path copy = keep(n);
        store.consume(player, SLOT, store.load(player, SLOT).orElseThrow());

        Files.delete(list());
        putBack(copy);
        assertFalse(store.load(player, SLOT).isPresent());
        assertTrue(store.hasFile(player, SLOT), "refused, not deleted: the list can't say it is a copy");
        assertTrue(store.pending(player).isEmpty());
    }

    @Test
    @DisplayName("a hand-made v2 list in place of the v3 one does not wake a copy")
    void forgedV2ListRefusesCopy() throws Exception {
        NapSnapshot n = napAt("World 4", 10L);
        store.save(player, SLOT, n);
        Path copy = keep(n);
        store.consume(player, SLOT, n);

        Files.write(list(), NapTestFiles.legacyList((byte) 2, 0L));
        putBack(copy);
        assertFalse(store.load(player, SLOT).isPresent());
    }

    @Test
    @DisplayName("a copy whose nap id was edited is refused")
    void editedIdRefused() throws Exception {
        NapSnapshot n = napAt("World 5", 10L);
        store.save(player, SLOT, n);
        Path copy = keep(n);
        store.consume(player, SLOT, n);

        CompoundTag root = NbtIo.readCompressed(copy, NbtAccounter.unlimitedHeap());
        CompoundTag tag = NapSeal.fromBytes(root.getByteArray(NapSeal.TAG_BODY));
        tag.putUUID("napId", UUID.randomUUID());
        root.putByteArray(NapSeal.TAG_BODY, NapSeal.toBytes(tag));
        NbtIo.writeCompressed(root, store.fileFor(player, SLOT));
        assertFalse(store.load(player, SLOT).isPresent());
    }

    @Test
    @DisplayName("an edited list fails its seal: the waiting nap is refused and kept, the list set aside")
    void editedListFailsSeal() throws Exception {
        store.save(player, SLOT, napAt("World 6", 10L));
        byte[] bytes = Files.readAllBytes(list());
        bytes[8] ^= 1;
        Files.write(list(), bytes);

        assertFalse(store.load(player, SLOT).isPresent());
        assertTrue(store.hasFile(player, SLOT));
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith(NapAppliedLog.DAMAGED_PREFIX)));
        }
        NapSnapshot later = napAt("World 7", 20L);
        store.save(player, SLOT, later);
        assertEquals(later, store.load(player, SLOT).orElseThrow(), "a nap saved after the damage wakes");
    }

    @Test
    @DisplayName("a list sealed under another install's key is refused")
    void otherInstallKeyRefused() throws Exception {
        store.save(player, SLOT, napAt("World 8", 10L));
        InstallKey.useDirectoryForTests(Files.createDirectories(root.resolve("other-install")));
        assertFalse(store.load(player, SLOT).isPresent());
    }

    @Test
    @DisplayName("saving again in a slot replaces its waiting nap: a copy of the first is refused")
    void overwriteRefusesOldCopy() throws Exception {
        NapSnapshot first = napAt("World 9", 10L);
        store.save(player, SLOT, first);
        Path copy = keep(first);
        NapSnapshot second = napAt("World 10", 20L);
        store.save(player, SLOT, second);
        assertEquals(second, store.load(player, SLOT).orElseThrow());

        putBack(copy);
        assertFalse(store.load(player, SLOT).isPresent());
    }

    @Test
    @DisplayName("binding to the waking world keeps the nap wakeable")
    void bindKeepsPending() throws Exception {
        NapSnapshot n = napAt("World 11", 10L);
        store.save(player, SLOT, n);
        NapSnapshot bound = store.bind(player, SLOT, n, "World 12");
        assertEquals(bound, store.load(player, SLOT).orElseThrow());
    }

    @Test
    @DisplayName("upgrade: a waiting nap from before v3 is carried over and wakes once")
    void upgradeFromV2AdoptsPendingNap() throws Exception {
        UUID oldWoken = UUID.randomUUID();
        Files.createDirectories(dir);
        Files.write(list(), NapTestFiles.legacyList((byte) 2, 0L, oldWoken));
        NapSnapshot n = napAt("World 13", 10L);
        NapTestFiles.writeUnmarked(store, player, SLOT, n);

        assertEquals(n, store.load(player, SLOT).orElseThrow());
        assertEquals(Set.of(oldWoken), NapAppliedLog.decode(Files.readAllBytes(list())).woken());
        Path copy = keep(n);
        store.consume(player, SLOT, n);
        putBack(copy);
        assertFalse(store.load(player, SLOT).isPresent());
    }

    @Test
    @DisplayName("upgrade with no list yet: a waiting nap from before v3 wakes")
    void upgradeWithNoListAdopts() throws Exception {
        NapSnapshot n = napAt("World 14", 10L);
        NapTestFiles.writeUnmarked(store, player, SLOT, n);
        assertEquals(n, store.load(player, SLOT).orElseThrow());
    }

    @Test
    @DisplayName("upgrade: a nap saved before the old list's restart stays refused, and is kept")
    void upgradeKeepsTrustSinceRefusal() throws Exception {
        Files.createDirectories(dir);
        Files.write(list(), NapTestFiles.legacyList((byte) 2, 500L));
        NapTestFiles.writeUnmarked(store, player, SLOT, napAt("World 15", 499L));
        assertFalse(store.load(player, SLOT).isPresent());
        assertTrue(store.hasFile(player, SLOT));
    }

    @Test
    @DisplayName("upgrade: a copy of a nap the old list saw wake is refused and deleted")
    void upgradeKeepsWokenRefusal() throws Exception {
        NapSnapshot woken = napAt("World 16", 600L);
        Files.createDirectories(dir);
        Files.write(list(), NapTestFiles.legacyList((byte) 2, 0L, woken.napId()));
        NapTestFiles.writeUnmarked(store, player, SLOT, woken);
        assertFalse(store.load(player, SLOT).isPresent());
        assertFalse(store.hasFile(player, SLOT));
    }

    @Test
    @DisplayName("a nap saved by this version is never carried over when the list is rebuilt")
    void markedNapNeverAdopted() throws Exception {
        NapSnapshot n = napAt("World 17", 10L);
        store.save(player, SLOT, n);
        Files.delete(list());
        assertFalse(store.load(player, SLOT).isPresent());
    }

    @Test
    @DisplayName("restoring the whole folder from a backup brings the nap back with its list and key")
    void backupRestoreWakes() throws Exception {
        NapSnapshot n = napAt("World 18", 10L);
        store.save(player, SLOT, n);
        Path backup = root.resolve("backup");
        copyTree(keyDir, backup);
        store.consume(player, SLOT, n);
        assertFalse(store.load(player, SLOT).isPresent());

        deleteTree(keyDir);
        copyTree(backup, keyDir);
        InstallKey.useDirectoryForTests(keyDir); // a new session after the restore
        assertEquals(n, new NapStore(keyDir).load(player, SLOT).orElseThrow());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
}
