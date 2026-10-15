package games.brennan.dungeontrain.advancement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay-sent advancement promote. What must hold: only advancements the target lacks are offered,
 * the shared editor tab never is, copy leaves Normal alone, move takes the id off Normal, the backup
 * itself is never touched, and a promote id answered once stays answered.
 */
class AdvancementPromotionTest {

    private static final UUID PLAYER = UUID.fromString("380df991-f603-344c-a090-369bad2a924a");
    private static final ResourceLocation CARTS = ResourceLocation.parse("dungeontrain:dungeon_train/carts_100");
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:story/mine_stone");
    private static final ResourceLocation EDITOR = ResourceLocation.parse("dungeontrain:editor/first_build");
    private static final ResourceLocation CHESTS = ResourceLocation.parse("dungeontrain:dungeon_train/chests_100_unique");

    private static void write(Path file, ResourceLocation... ids) throws IOException {
        Files.createDirectories(file.getParent());
        GlobalAchievementStore.writeAtomic(file, new java.util.LinkedHashSet<>(List.of(ids)));
    }

    private static Path backup(Path dir) {
        return dir.resolve(AdvancementSplitBackup.COPY_DIR).resolve(PLAYER + ".json");
    }

    private static Path profile(Path dir, String partition) {
        return dir.resolve(GlobalAchievementStore.fileName(PLAYER, partition));
    }

    @Test
    @DisplayName("candidates come from the backup, skip the editor tab and what the target already has")
    void candidatesFromBackup(@TempDir Path dir) throws IOException {
        write(backup(dir), CARTS, EDITOR, STONE, CHESTS);
        write(profile(dir, "hard"), STONE);

        assertEquals(List.of(CARTS, CHESTS), AdvancementPromotion.candidates(dir, PLAYER, "hard"));
    }

    @Test
    @DisplayName("with no backup, the Normal profile is the source")
    void fallsBackToNormal(@TempDir Path dir) throws IOException {
        write(profile(dir, "normal"), CARTS, STONE);

        assertEquals(List.of(CARTS, STONE), AdvancementPromotion.candidates(dir, PLAYER, "hard"));
    }

    @Test
    @DisplayName("copy adds to the target and Normal keeps it; move takes it off Normal; the backup is untouched")
    void copyAndMove(@TempDir Path dir) throws IOException {
        write(backup(dir), CARTS, STONE, CHESTS);
        write(profile(dir, "normal"), CARTS, STONE, CHESTS);

        AdvancementPromotion.Applied applied =
            AdvancementPromotion.apply(dir, PLAYER, "hard", List.of(CARTS), List.of(STONE));

        assertEquals(2, applied.added());
        assertEquals(1, applied.removed());
        assertEquals(Set.of(CARTS, STONE), GlobalAchievementStore.readFile(profile(dir, "hard")));
        assertEquals(Set.of(CARTS, CHESTS), GlobalAchievementStore.readFile(profile(dir, "normal")));
        assertEquals(Set.of(CARTS, STONE, CHESTS), GlobalAchievementStore.readFile(backup(dir)));
    }

    @Test
    @DisplayName("moving into Normal is just a copy")
    void moveIntoNormalKeepsIt(@TempDir Path dir) throws IOException {
        write(backup(dir), CARTS);

        AdvancementPromotion.Applied applied =
            AdvancementPromotion.apply(dir, PLAYER, "normal", List.of(), List.of(CARTS));

        assertEquals(new AdvancementPromotion.Applied(1, 0), applied);
        assertEquals(Set.of(CARTS), GlobalAchievementStore.readFile(profile(dir, "normal")));
    }

    @Test
    @DisplayName("an answered promote id stays answered, and others don't")
    void usedMarker(@TempDir Path dir) {
        assertFalse(AdvancementPromotion.isUsed(dir, "p_1"));
        assertTrue(AdvancementPromotion.markUsed(dir, "p_1"));
        assertTrue(AdvancementPromotion.markUsed(dir, "p_1"));
        assertTrue(AdvancementPromotion.markUsed(dir, "p_2"));

        assertTrue(AdvancementPromotion.isUsed(dir, "p_1"));
        assertFalse(AdvancementPromotion.isUsed(dir, "p_3"));
        assertEquals(Set.of("p_1", "p_2"), AdvancementPromotion.usedIds(dir));
    }

    @Test
    @DisplayName("copy and move wire names parse; anything else is rejected")
    void modeParse() {
        assertEquals(AdvancementPromotion.Mode.COPY, AdvancementPromotion.Mode.parse("copy"));
        assertEquals(AdvancementPromotion.Mode.MOVE, AdvancementPromotion.Mode.parse("move"));
        assertEquals(null, AdvancementPromotion.Mode.parse("swap"));
    }
}
