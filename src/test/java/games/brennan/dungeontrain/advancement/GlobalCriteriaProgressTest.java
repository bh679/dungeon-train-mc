package games.brennan.dungeontrain.advancement;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GlobalCriteriaProgress}: half-finished collections (Most Wanted's 34 mobs) carried across
 * worlds. What matters is that progress survives a fresh cache (a new world / restart), stays in its
 * own difficulty, and leaves cleanly when finished, revoked or reset.
 */
class GlobalCriteriaProgressTest {

    private static final UUID PLAYER = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final ResourceLocation MOST_WANTED = ResourceLocation.parse("dungeontrain:enchiridion/most_wanted");
    private static final ResourceLocation ADVENTURING = ResourceLocation.parse("minecraft:adventure/adventuring_time");

    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        GlobalCriteriaProgress.resetForTests();
    }

    @Test
    @DisplayName("file names sit beside the finished-advancement profiles without clashing with them")
    void fileNames() {
        assertEquals(PLAYER + ".criteria.json", GlobalCriteriaProgress.fileName(PLAYER, "normal"));
        assertEquals(PLAYER + ".hard.criteria.json", GlobalCriteriaProgress.fileName(PLAYER, "hard"));
        assertFalse(GlobalCriteriaProgress.fileName(PLAYER, "normal")
            .equals(GlobalAchievementStore.fileName(PLAYER, "normal")));
    }

    @Test
    @DisplayName("banked criteria come back from disk after the cache is gone — a new world")
    void survivesRestart() {
        GlobalCriteriaProgress.useDirectoryForTests(dir);
        assertEquals(2, GlobalCriteriaProgress.addAll(PLAYER, "normal", MOST_WANTED,
            List.of("minecraft:blaze", "minecraft:creeper")));
        assertEquals(1, GlobalCriteriaProgress.addAll(PLAYER, "normal", MOST_WANTED,
            List.of("minecraft:creeper", "minecraft:ghast")));
        GlobalCriteriaProgress.flushAndClearAll();

        assertEquals(Set.of("minecraft:blaze", "minecraft:creeper", "minecraft:ghast"),
            GlobalCriteriaProgress.criteria(PLAYER, "normal", MOST_WANTED));
    }

    @Test
    @DisplayName("each difficulty keeps its own progress")
    void partitionsAreSeparate() {
        GlobalCriteriaProgress.useDirectoryForTests(dir);
        GlobalCriteriaProgress.addAll(PLAYER, "hard", MOST_WANTED, List.of("minecraft:blaze"));
        assertTrue(GlobalCriteriaProgress.read(PLAYER, "normal").isEmpty());
        assertEquals(Set.of("minecraft:blaze"), GlobalCriteriaProgress.criteria(PLAYER, "hard", MOST_WANTED));
    }

    @Test
    @DisplayName("revoking the last criterion drops the advancement's entry")
    void removeCriterion() {
        GlobalCriteriaProgress.useDirectoryForTests(dir);
        GlobalCriteriaProgress.addAll(PLAYER, "normal", MOST_WANTED, List.of("minecraft:blaze", "minecraft:zombie"));
        assertTrue(GlobalCriteriaProgress.removeCriterion(PLAYER, "normal", MOST_WANTED, "minecraft:blaze"));
        assertFalse(GlobalCriteriaProgress.removeCriterion(PLAYER, "normal", MOST_WANTED, "minecraft:blaze"));
        assertTrue(GlobalCriteriaProgress.removeCriterion(PLAYER, "normal", MOST_WANTED, "minecraft:zombie"));
        GlobalCriteriaProgress.flushAndClearAll();
        assertFalse(GlobalCriteriaProgress.read(PLAYER, "normal").containsKey(MOST_WANTED));
    }

    @Test
    @DisplayName("a finished or struck advancement leaves; the others stay")
    void removeAdvancementAndEverywhere() {
        GlobalCriteriaProgress.useDirectoryForTests(dir);
        GlobalCriteriaProgress.addAll(PLAYER, "normal", MOST_WANTED, List.of("minecraft:blaze"));
        GlobalCriteriaProgress.addAll(PLAYER, "normal", ADVENTURING, List.of("minecraft:plains"));
        GlobalCriteriaProgress.addAll(PLAYER, "hard", MOST_WANTED, List.of("minecraft:ghast"));

        assertTrue(GlobalCriteriaProgress.removeAdvancement(PLAYER, "normal", ADVENTURING));
        assertTrue(GlobalCriteriaProgress.removeEverywhere(PLAYER, MOST_WANTED));
        GlobalCriteriaProgress.flushAndClearAll();

        assertEquals(Map.of(), GlobalCriteriaProgress.read(PLAYER, "normal"));
        assertEquals(Map.of(), GlobalCriteriaProgress.read(PLAYER, "hard"));
    }

    @Test
    @DisplayName("the profile reset deletes every partition's file")
    void deleteFor() throws Exception {
        GlobalCriteriaProgress.useDirectoryForTests(dir);
        GlobalCriteriaProgress.addAll(PLAYER, "normal", MOST_WANTED, List.of("minecraft:blaze"));
        GlobalCriteriaProgress.addAll(PLAYER, "hard", MOST_WANTED, List.of("minecraft:blaze"));
        GlobalCriteriaProgress.flush();
        assertTrue(GlobalCriteriaProgress.hasAny(PLAYER));

        assertTrue(GlobalCriteriaProgress.deleteFor(PLAYER));
        assertFalse(GlobalCriteriaProgress.hasAny(PLAYER));
        assertTrue(GlobalCriteriaProgress.read(PLAYER, "normal").isEmpty());
    }

    @Test
    @DisplayName("a malformed file reads as no progress instead of failing the login")
    void malformedFile() throws Exception {
        GlobalCriteriaProgress.useDirectoryForTests(dir);
        Files.writeString(dir.resolve(GlobalCriteriaProgress.fileName(PLAYER, "normal")), "{ not json");
        assertTrue(GlobalCriteriaProgress.read(PLAYER, "normal").isEmpty());
    }
}
