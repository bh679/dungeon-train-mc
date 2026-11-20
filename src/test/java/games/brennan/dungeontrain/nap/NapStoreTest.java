package games.brennan.dungeontrain.nap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link NapSnapshot} round trip and {@link NapStore}'s single-use rule. */
final class NapStoreTest {

    @org.junit.jupiter.api.BeforeEach
    void installKey(@TempDir Path keyDir) {
        games.brennan.dungeontrain.data.InstallKey.useDirectoryForTests(keyDir);
    }

    @org.junit.jupiter.api.AfterEach
    void resetKey() {
        games.brennan.dungeontrain.data.InstallKey.resetForTests();
    }

    private static NapSnapshot nap(String levelId) {
        CompoundTag player = new CompoundTag();
        player.putInt("XpLevel", 30);
        return new NapSnapshot(UUID.randomUUID(), 123L, "0.1840.0", levelId, player,
            -5120.5, -149, "Overworld (BoP)", -1, 312, true, "hard");
    }

    @Test
    @DisplayName("a nap survives the trip through NBT unchanged")
    void roundTrip() {
        NapSnapshot n = nap("World 1700000000000");
        assertEquals(n, NapSnapshot.fromTag(n.toTag()));
        assertEquals(null, NapSnapshot.fromTag(new CompoundTag()));
    }

    @Test
    @DisplayName("the previous life's advancements, stats and narrative survive the round trip")
    void roundTripCarriesPreviousLife() {
        CompoundTag player = new CompoundTag();
        player.putInt("XpLevel", 7);
        CompoundTag narrative = new CompoundTag();
        narrative.putIntArray("shared_books_ever_read", new int[] {3, 9});
        String advancements = "{\"minecraft:story/root\":{\"done\":true},\"DataVersion\":3955}"
            + " ".repeat(70_000);
        NapSnapshot n = new NapSnapshot(UUID.randomUUID(), 5L, "0.1840.0", "World 1700000000003", player,
            12.0, 0, "Overworld", 1, 4, false, "normal", advancements, "{\"stats\":{}}", narrative);
        NapSnapshot back = NapSnapshot.fromTag(n.toTag());
        assertEquals(n, back);
        assertEquals(advancements, back.advancementsJson(), "past NBT's 64 KB string limit");
        assertEquals(narrative, back.narrative());
    }

    @Test
    @DisplayName("the run-state extras and the difficulty offset survive the round trip")
    void roundTripCarriesRunExtrasAndOffset() {
        CompoundTag player = new CompoundTag();
        player.putInt("XpLevel", 3);
        CompoundTag extras = new CompoundTag();
        extras.putInt("echoesKilled", 4);
        extras.putLong("liveStreamSeconds", 600L);
        NapSnapshot n = new NapSnapshot(UUID.randomUUID(), 9L, "0.1840.0", "World 1700000000005", player,
            300.0, 8, "Overworld", 1, 8, true, "hard", "", "", new CompoundTag(), extras, -250);
        NapSnapshot back = NapSnapshot.fromTag(n.toTag());
        assertEquals(n, back);
        assertEquals(extras, back.runExtras());
        assertEquals(-250, back.difficultyOffset());
    }

    @Test
    @DisplayName("a nap saved before run extras and the offset were carried reads with none and automatic difficulty")
    void oldNapReadsWithoutRunExtrasOrOffset() {
        CompoundTag tag = nap("World 1700000000006").toTag();
        assertFalse(tag.contains("runExtras"), "empty extras are left out of the file");
        assertFalse(tag.contains("difficultyOffset"), "a zero offset is left out of the file");
        NapSnapshot back = NapSnapshot.fromTag(tag);
        assertTrue(back.runExtras().isEmpty());
        assertEquals(0, back.difficultyOffset());
    }

    @Test
    @DisplayName("a nap saved before the previous life was carried still reads, with none of it")
    void oldNapReadsWithoutPreviousLife() {
        CompoundTag tag = nap("World 1700000000004").toTag();
        NapSnapshot back = NapSnapshot.fromTag(tag);
        assertEquals("", back.advancementsJson());
        assertEquals("", back.statsJson());
        assertTrue(back.narrative().isEmpty());
        assertFalse(tag.contains("advancements"), "empty fields are left out of the file");
    }

    @Test
    @DisplayName("a nap wakes once: a copy put back after waking is refused")
    void singleUse(@TempDir Path root) throws Exception {
        NapStore store = new NapStore(root);
        UUID player = UUID.randomUUID();
        String slot = NapSlot.key(false, Difficulty.HARD);
        NapSnapshot n = nap("World 1700000000001");
        store.save(player, slot, n);
        assertEquals(Set.of("World 1700000000001"), store.pendingLevelIds());
        Path copy = root.resolve("copy.bin");
        Files.copy(store.fileFor(player, slot), copy);

        assertEquals(n, store.load(player, slot).orElseThrow());
        store.consume(player, slot, n);
        assertFalse(store.load(player, slot).isPresent());

        Files.copy(copy, store.fileFor(player, slot));
        assertFalse(store.load(player, slot).isPresent());
        assertFalse(Files.exists(store.fileFor(player, slot)), "the refused copy is deleted");
        assertTrue(store.pendingLevelIds().isEmpty());
    }

    @Test
    @DisplayName("Free Play, Survival and each difficulty keep their own nap")
    void slotsAreSeparate(@TempDir Path root) throws Exception {
        assertEquals("survival-normal", NapSlot.key(false, Difficulty.NORMAL));
        assertEquals("freeplay-hard", NapSlot.key(true, Difficulty.HARD));

        NapStore store = new NapStore(root);
        UUID player = UUID.randomUUID();
        NapSnapshot n = nap("World 1700000000002");
        store.save(player, NapSlot.key(false, Difficulty.NORMAL), n);
        assertTrue(store.load(player, NapSlot.key(false, Difficulty.NORMAL)).isPresent());
        assertFalse(store.load(player, NapSlot.key(true, Difficulty.NORMAL)).isPresent());
        assertFalse(store.load(player, NapSlot.key(false, Difficulty.HARD)).isPresent());
    }

    @Test
    @DisplayName("an old text list is migrated into the v3 list and removed")
    void legacyTextListMigrated(@TempDir Path root) throws Exception {
        NapStore store = new NapStore(root);
        UUID player = UUID.randomUUID();
        String slot = NapSlot.key(false, Difficulty.HARD);
        NapSnapshot n = nap("World 1700000000003");
        Path dir = root.resolve(NapStore.DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(NapAppliedLog.LEGACY_FILE), n.napId() + "\n");
        NapTestFiles.writeUnmarked(store, player, slot, n);

        assertFalse(store.load(player, slot).isPresent(), "the id from the text list still counts");
        assertFalse(Files.exists(dir.resolve(NapAppliedLog.LEGACY_FILE)));
        assertEquals(Set.of(n.napId()), NapAppliedLog.decode(Files.readAllBytes(dir.resolve(NapAppliedLog.FILE))).woken());
    }

    @Test
    @DisplayName("a v1 and a v2 list still decode for the move to v3")
    void legacyBinaryDecodes() throws Exception {
        UUID id = UUID.randomUUID();
        NapAppliedLog.Legacy v1 = NapAppliedLog.decodeLegacy(NapTestFiles.legacyList((byte) 1, 0L, id));
        assertEquals(Set.of(id), v1.ids());
        assertEquals(0L, v1.trustSince());
        NapAppliedLog.Legacy v2 = NapAppliedLog.decodeLegacy(NapTestFiles.legacyList((byte) 2, 77L, id));
        assertEquals(Set.of(id), v2.ids());
        assertEquals(77L, v2.trustSince());
    }
}
