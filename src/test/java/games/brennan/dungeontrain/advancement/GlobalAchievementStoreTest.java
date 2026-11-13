package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.cheat.ProfileIntegrity;
import games.brennan.dungeontrain.data.InstallKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-difficulty split of {@link GlobalAchievementStore}. The rules that matter are the ones that
 * keep existing profiles where they are: Normal must keep reading the pre-split file, and editor
 * advancements earned before the split must stay visible on every difficulty.
 */
class GlobalAchievementStoreTest {

    private static final UUID PLAYER = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final ResourceLocation GAMEPLAY = ResourceLocation.parse("dungeontrain:dungeon_train/carts_100");
    private static final ResourceLocation VANILLA = ResourceLocation.parse("minecraft:story/mine_stone");
    private static final ResourceLocation EDITOR = ResourceLocation.parse("dungeontrain:editor/first_template");

    @Test
    @DisplayName("Normal keeps the pre-split file name, so every existing profile becomes the Normal one")
    void normalKeepsLegacyFileName() {
        assertEquals(PLAYER + ".json", GlobalAchievementStore.fileName(PLAYER, "normal"));
    }

    @Test
    @DisplayName("the other difficulties and the shared partition get their own Windows-safe files")
    void otherPartitionsGetTheirOwnFiles() {
        assertEquals(PLAYER + ".hard.json", GlobalAchievementStore.fileName(PLAYER, "hard"));
        assertEquals(PLAYER + ".peaceful.json", GlobalAchievementStore.fileName(PLAYER, "peaceful"));
        assertEquals(PLAYER + ".easy.json", GlobalAchievementStore.fileName(PLAYER, "easy"));
        assertEquals(PLAYER + ".shared.json",
            GlobalAchievementStore.fileName(PLAYER, GlobalAchievementStore.SHARED));
        assertFalse(GlobalAchievementStore.fileName(PLAYER, "hard").contains("|"));
    }

    @Test
    @DisplayName("only the Train Editor tab is shared across difficulties")
    void onlyEditorTabIsShared() {
        assertTrue(GlobalAchievementStore.isShared(EDITOR));
        assertFalse(GlobalAchievementStore.isShared(GAMEPLAY));
        assertFalse(GlobalAchievementStore.isShared(VANILLA));
        assertFalse(GlobalAchievementStore.isShared(ResourceLocation.parse("othermod:editor/thing")));
    }

    @Test
    @DisplayName("editor advancements banked in the Normal file before the split show up in the shared view")
    void sharedViewIncludesLegacyEditorIds() {
        Set<ResourceLocation> legacy = Set.of(GAMEPLAY, VANILLA, EDITOR);
        Set<ResourceLocation> view = GlobalAchievementStore.sharedView(Set.of(), legacy);
        assertEquals(Set.of(EDITOR), view, "gameplay ids must not leak out of Normal through the shared view");
    }

    @Test
    @DisplayName("partition files round-trip independently")
    void partitionFilesAreIndependent(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        Path normal = normalFile(dir);
        Path hard = dir.resolve(GlobalAchievementStore.fileName(PLAYER, "hard"));
        write(normal, "normal", GAMEPLAY, VANILLA);
        write(hard, "hard", VANILLA);

        assertEquals(Set.of(GAMEPLAY, VANILLA), read(normal, "normal"));
        assertEquals(Set.of(VANILLA), read(hard, "hard"));
        assertTrue(read(dir.resolve("missing.json"), "normal").isEmpty());
    }

    // ---------------- Seal ----------------

    @Test
    @DisplayName("A written profile carries a seal, and reads back as earned")
    void writtenProfileIsSealed(@TempDir Path dir) throws Exception {
        GlobalAchievementStore.useDirectoryForTests(dir);
        write(normalFile(dir), "normal", GAMEPLAY);
        assertTrue(Files.readString(normalFile(dir)).contains("\"seal\""));
        GlobalAchievementStore.Profile profile = GlobalAchievementStore.readFile(normalFile(dir), PLAYER, "normal");
        assertEquals(Set.of(GAMEPLAY), profile.granted());
        assertTrue(profile.absorbed().isEmpty());
        assertFalse(profile.tainted());
    }

    @Test
    @DisplayName("An id added by hand under an existing seal taints the profile and marks the player")
    void handEditIsTampered(@TempDir Path dir) throws Exception {
        GlobalAchievementStore.useDirectoryForTests(dir);
        write(normalFile(dir), "normal", GAMEPLAY);
        String edited = Files.readString(normalFile(dir)).replace("\"granted\":[", "\"granted\":[\"" + VANILLA + "\",");
        Files.writeString(normalFile(dir), edited);

        GlobalAchievementStore.Profile profile = GlobalAchievementStore.readFile(normalFile(dir), PLAYER, "normal");
        assertTrue(profile.tainted());
        assertEquals(Set.of(VANILLA, GAMEPLAY), profile.all(), "the contents are kept, not deleted");
        assertTrue(ProfileIntegrity.isTainted(PLAYER));
    }

    @Test
    @DisplayName("A tainted profile is written straight back with the taint under the seal, so it holds")
    void taintIsPersisted(@TempDir Path dir) throws Exception {
        GlobalAchievementStore.useDirectoryForTests(dir);
        write(normalFile(dir), "normal", GAMEPLAY);
        Files.writeString(normalFile(dir), Files.readString(normalFile(dir)).replace("carts_100", "carts_999"));
        GlobalAchievementStore.read(PLAYER, "normal");  // the cache load catches it
        GlobalAchievementStore.flushAndClearAll();
        ProfileIntegrity.clear(PLAYER);

        assertTrue(Files.readString(normalFile(dir)).contains("\"tainted\":true"));
        GlobalAchievementStore.read(PLAYER, "normal");
        assertTrue(ProfileIntegrity.isTainted(PLAYER), "re-read from a fresh session, the taint is back");
    }

    @Test
    @DisplayName("A sealed file copied to another partition or player does not verify")
    void sealIsScoped(@TempDir Path dir) throws Exception {
        GlobalAchievementStore.useDirectoryForTests(dir);
        write(normalFile(dir), "normal", GAMEPLAY);
        assertTrue(GlobalAchievementStore.readFile(normalFile(dir), PLAYER, "hard").tainted());
        assertTrue(GlobalAchievementStore.readFile(normalFile(dir), UUID.randomUUID(), "normal").tainted());
    }

    @Test
    @DisplayName("An unsealed file on an install that never sealed is this install's own history")
    void unsealedBeforeFirstSealIsGrandfathered(@TempDir Path dir) throws Exception {
        GlobalAchievementStore.useDirectoryForTests(dir);
        Files.writeString(normalFile(dir), "{\"granted\":[\"" + GAMEPLAY + "\"]}");
        GlobalAchievementStore.Profile profile = GlobalAchievementStore.readFile(normalFile(dir), PLAYER, "normal");
        assertEquals(Set.of(GAMEPLAY), profile.granted());
        assertFalse(profile.tainted());
        assertFalse(ProfileIntegrity.isTainted(PLAYER));
    }

    @Test
    @DisplayName("An unsealed file on an install that had sealed before is kept, but not vouched for")
    void unsealedAfterKeyIsAbsorbed(@TempDir Path dir) throws Exception {
        GlobalAchievementStore.useDirectoryForTests(dir);
        write(normalFile(dir), "normal", GAMEPLAY);          // creates the key
        InstallKey.useDirectoryForTests(dir);                 // a new session: the key already exists
        Files.writeString(normalFile(dir), "{\"granted\":[\"" + GAMEPLAY + "\"]}");
        GlobalAchievementStore.Profile profile = GlobalAchievementStore.readFile(normalFile(dir), PLAYER, "normal");
        assertTrue(profile.granted().isEmpty());
        assertEquals(Set.of(GAMEPLAY), profile.absorbed());
        assertFalse(profile.tainted());
        assertEquals(Set.of(GAMEPLAY), GlobalAchievementStore.read(PLAYER, "normal"), "still replayed");
        assertEquals(Set.of(GAMEPLAY), GlobalAchievementStore.unverified(PLAYER, "normal"));
    }

    @Test
    @DisplayName("Absorbed ids stay apart from earned ones and never upgrade by a repeat append")
    void absorbedStaysAbsorbed(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        assertEquals(1, GlobalAchievementStore.appendAllAbsorbed(PLAYER, "normal", List.of(GAMEPLAY)));
        assertEquals(0, GlobalAchievementStore.appendAll(PLAYER, "normal", List.of(GAMEPLAY)));
        assertTrue(GlobalAchievementStore.append(PLAYER, "normal", VANILLA));
        assertEquals(0, GlobalAchievementStore.appendAllAbsorbed(PLAYER, "normal", List.of(VANILLA)));
        assertEquals(Set.of(GAMEPLAY), GlobalAchievementStore.unverified(PLAYER, "normal"));
        assertEquals(Set.of(GAMEPLAY, VANILLA), GlobalAchievementStore.read(PLAYER, "normal"));
        assertTrue(GlobalAchievementStore.removeEverywhere(PLAYER, GAMEPLAY));
        assertTrue(GlobalAchievementStore.unverified(PLAYER, "normal").isEmpty());
    }

    @Test
    @DisplayName("Malformed JSON is an empty profile, not a tamper")
    void malformedIsNotTamper(@TempDir Path dir) throws Exception {
        GlobalAchievementStore.useDirectoryForTests(dir);
        Files.writeString(normalFile(dir), "{\"granted\": [");
        assertTrue(GlobalAchievementStore.readFile(normalFile(dir), PLAYER, "normal").all().isEmpty());
        assertFalse(ProfileIntegrity.isTainted(PLAYER));
    }

    private static void write(Path file, String partition, ResourceLocation... ids) {
        GlobalAchievementStore.writeAtomic(file, PLAYER, partition,
            new GlobalAchievementStore.Profile(new LinkedHashSet<>(List.of(ids)), Set.of(), false));
    }

    private static Set<ResourceLocation> read(Path file, String partition) {
        return GlobalAchievementStore.readFile(file, PLAYER, partition).all();
    }

    // ---------------- Cache + background writer ----------------

    @AfterEach
    void restoreStore() {
        GlobalAchievementStore.resetForTests();
        ProfileIntegrity.clear(PLAYER);
    }

    private static Path normalFile(Path dir) {
        return dir.resolve(GlobalAchievementStore.fileName(PLAYER, "normal"));
    }

    @Test
    @DisplayName("An earn reaches the disk once the writer is flushed; a repeat earn changes nothing")
    void appendWritesInBackground(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        assertTrue(GlobalAchievementStore.append(PLAYER, "normal", GAMEPLAY));
        GlobalAchievementStore.flush();
        assertEquals(Set.of(GAMEPLAY), read(normalFile(dir), "normal"));
        assertFalse(GlobalAchievementStore.append(PLAYER, "normal", GAMEPLAY));
    }

    @Test
    @DisplayName("A read straight after an earn sees it, before the write has landed")
    void readServedFromCache(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        GlobalAchievementStore.append(PLAYER, "hard", VANILLA);
        assertTrue(GlobalAchievementStore.read(PLAYER, "hard").contains(VANILLA));
    }

    @Test
    @DisplayName("A burst of earns ends with every id on disk, in earn order")
    void burstKeepsEveryIdInOrder(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        List<ResourceLocation> ids = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ResourceLocation id = ResourceLocation.parse("dungeontrain:test/burst_" + i);
            ids.add(id);
            GlobalAchievementStore.append(PLAYER, "normal", id);
        }
        GlobalAchievementStore.flush();
        assertEquals(ids, List.copyOf(read(normalFile(dir), "normal")));
    }

    @Test
    @DisplayName("Revoking strikes the id from the cache and from every partition's file")
    void removeEverywhereUpdatesCacheAndDisk(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        GlobalAchievementStore.append(PLAYER, "normal", GAMEPLAY);
        GlobalAchievementStore.append(PLAYER, "hard", GAMEPLAY);
        GlobalAchievementStore.append(PLAYER, "hard", VANILLA);
        assertTrue(GlobalAchievementStore.removeEverywhere(PLAYER, GAMEPLAY));
        assertFalse(GlobalAchievementStore.read(PLAYER, "hard").contains(GAMEPLAY));
        GlobalAchievementStore.flush();
        assertTrue(read(normalFile(dir), "normal").isEmpty());
        assertEquals(Set.of(VANILLA),
            read(dir.resolve(GlobalAchievementStore.fileName(PLAYER, "hard")), "hard"));
    }

    @Test
    @DisplayName("After a server stop the next read comes from disk, so title-screen edits aren't lost")
    void clearAllDropsStaleCache(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        GlobalAchievementStore.append(PLAYER, "normal", GAMEPLAY);
        GlobalAchievementStore.flushAndClearAll();
        // What AdvancementPromotion does at the title screen: edit the file directly.
        write(normalFile(dir), "normal", GAMEPLAY, VANILLA);
        assertEquals(Set.of(GAMEPLAY, VANILLA), GlobalAchievementStore.read(PLAYER, "normal"));
    }

    @Test
    @DisplayName("Editor ids banked in the shared partition stay out of the difficulty file")
    void sharedAppendGoesToSharedFile(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        GlobalAchievementStore.append(PLAYER, GlobalAchievementStore.SHARED, EDITOR);
        GlobalAchievementStore.flush();
        assertEquals(Set.of(EDITOR), read(
            dir.resolve(GlobalAchievementStore.fileName(PLAYER, GlobalAchievementStore.SHARED)), GlobalAchievementStore.SHARED));
        assertFalse(java.nio.file.Files.exists(normalFile(dir)));
    }

    @Test
    @DisplayName("Logout doesn't wait for a slow write, and a rejoin still sees the queued earn")
    void evictWhileWritePendingKeepsEarn(@TempDir Path dir) throws Exception {
        GlobalAchievementStore.useDirectoryForTests(dir);
        CountDownLatch slowDisk = new CountDownLatch(1);
        GlobalAchievementStore.runOnWriterForTests(() -> {
            try { slowDisk.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        GlobalAchievementStore.append(PLAYER, "normal", GAMEPLAY);
        GlobalAchievementStore.evict(PLAYER);
        assertFalse(java.nio.file.Files.exists(normalFile(dir)));
        assertTrue(GlobalAchievementStore.read(PLAYER, "normal").contains(GAMEPLAY));
        slowDisk.countDown();
        GlobalAchievementStore.flush();
        assertEquals(Set.of(GAMEPLAY), read(normalFile(dir), "normal"));
    }
}
