package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.player.DifficultyPartition;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where each bucket lands on disk. Pure path mapping — the store's I/O goes through
 * {@code FMLPaths.CONFIGDIR}, which isn't worth redirecting for a round-trip test.
 *
 * <p>The load-bearing case is the first one: the cross-life bucket must keep the exact pre-buckets filename,
 * or every existing advancement profile silently becomes unreachable.</p>
 */
class GlobalAchievementStorePathTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void theCrossLifeBucketKeepsTheOriginalFilename() {
        assertEquals(PLAYER + ".json",
            GlobalAchievementStore.relativeFile(PLAYER, GlobalAchievementStore.GLOBAL_BUCKET));
        assertEquals(PLAYER + ".json", GlobalAchievementStore.relativeFile(PLAYER, null));
    }

    @Test
    void everyDifficultyGetsItsOwnSingleLifeSubdirectory() {
        // Including normal: the per-life bucket must never share a file with the global one.
        assertEquals("normal/" + PLAYER + ".json", GlobalAchievementStore.relativeFile(PLAYER, "normal"));
        assertEquals("hard/" + PLAYER + ".json", GlobalAchievementStore.relativeFile(PLAYER, "hard"));
        assertEquals("easy/" + PLAYER + ".json", GlobalAchievementStore.relativeFile(PLAYER, "easy"));
        assertEquals("peaceful/" + PLAYER + ".json",
            GlobalAchievementStore.relativeFile(PLAYER, "peaceful"));
    }

    @Test
    void noBucketCollidesWithAnother() {
        String global = GlobalAchievementStore.relativeFile(PLAYER, GlobalAchievementStore.GLOBAL_BUCKET);
        String normal = GlobalAchievementStore.relativeFile(PLAYER, "normal");
        String hard = GlobalAchievementStore.relativeFile(PLAYER, "hard");
        assertNotEquals(global, normal, "the cross-life bucket is not Normal's single-life bucket");
        assertNotEquals(normal, hard);
    }

    @Test
    void aBlankPartitionResolvesToTheLegacyDifficultyBucket() {
        // "Don't know which difficulty" must still be a real per-life bucket, never the global file.
        assertEquals(DifficultyPartition.LEGACY_KEY, GlobalAchievementStore.perLifeBucket(""));
        assertEquals(DifficultyPartition.LEGACY_KEY, GlobalAchievementStore.perLifeBucket(null));
        assertEquals("hard", GlobalAchievementStore.perLifeBucket("hard"));
    }

    @Test
    void pathsAreLegalFilenamesOnWindowsToo() {
        // A subdirectory, deliberately: the Ender Chest slot key uses "|", fine in an NBT tag name and
        // illegal in a Windows filename. This store writes real files.
        for (String bucket : new String[] {GlobalAchievementStore.GLOBAL_BUCKET, "normal", "hard", "easy",
                "peaceful"}) {
            String path = GlobalAchievementStore.relativeFile(PLAYER, bucket);
            for (char illegal : new char[] {'|', ':', '*', '?', '"', '<', '>'}) {
                assertFalse(path.indexOf(illegal) >= 0, "illegal '" + illegal + "' in " + path);
            }
        }
    }
}
