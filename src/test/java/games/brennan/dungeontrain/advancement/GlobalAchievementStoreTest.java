package games.brennan.dungeontrain.advancement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        Path normal = dir.resolve(GlobalAchievementStore.fileName(PLAYER, "normal"));
        Path hard = dir.resolve(GlobalAchievementStore.fileName(PLAYER, "hard"));
        GlobalAchievementStore.writeAtomic(normal, new LinkedHashSet<>(List.of(GAMEPLAY, VANILLA)));
        GlobalAchievementStore.writeAtomic(hard, new LinkedHashSet<>(List.of(VANILLA)));

        assertEquals(Set.of(GAMEPLAY, VANILLA), GlobalAchievementStore.readFile(normal));
        assertEquals(Set.of(VANILLA), GlobalAchievementStore.readFile(hard));
        assertTrue(GlobalAchievementStore.readFile(dir.resolve("missing.json")).isEmpty());
    }

    // ---------------- Cache + background writer ----------------

    @AfterEach
    void restoreStore() {
        GlobalAchievementStore.resetForTests();
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
        assertEquals(Set.of(GAMEPLAY), GlobalAchievementStore.readFile(normalFile(dir)));
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
        assertEquals(ids, List.copyOf(GlobalAchievementStore.readFile(normalFile(dir))));
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
        assertTrue(GlobalAchievementStore.readFile(normalFile(dir)).isEmpty());
        assertEquals(Set.of(VANILLA),
            GlobalAchievementStore.readFile(dir.resolve(GlobalAchievementStore.fileName(PLAYER, "hard"))));
    }

    @Test
    @DisplayName("After a server stop the next read comes from disk, so title-screen edits aren't lost")
    void clearAllDropsStaleCache(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        GlobalAchievementStore.append(PLAYER, "normal", GAMEPLAY);
        GlobalAchievementStore.flushAndClearAll();
        // What AdvancementPromotion does at the title screen: edit the file directly.
        GlobalAchievementStore.writeAtomic(normalFile(dir), new LinkedHashSet<>(List.of(GAMEPLAY, VANILLA)));
        assertEquals(Set.of(GAMEPLAY, VANILLA), GlobalAchievementStore.read(PLAYER, "normal"));
    }

    @Test
    @DisplayName("Editor ids banked in the shared partition stay out of the difficulty file")
    void sharedAppendGoesToSharedFile(@TempDir Path dir) {
        GlobalAchievementStore.useDirectoryForTests(dir);
        GlobalAchievementStore.append(PLAYER, GlobalAchievementStore.SHARED, EDITOR);
        GlobalAchievementStore.flush();
        assertEquals(Set.of(EDITOR), GlobalAchievementStore.readFile(
            dir.resolve(GlobalAchievementStore.fileName(PLAYER, GlobalAchievementStore.SHARED))));
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
        assertEquals(Set.of(GAMEPLAY), GlobalAchievementStore.readFile(normalFile(dir)));
    }
}
