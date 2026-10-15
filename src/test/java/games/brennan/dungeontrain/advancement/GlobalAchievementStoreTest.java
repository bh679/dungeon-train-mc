package games.brennan.dungeontrain.advancement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
}
