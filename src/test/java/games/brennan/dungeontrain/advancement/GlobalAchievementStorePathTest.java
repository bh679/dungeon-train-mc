package games.brennan.dungeontrain.advancement;

import games.brennan.dungeontrain.player.DifficultyPartition;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where each difficulty's advancement profile lands on disk. Pure path mapping — the store's actual I/O
 * goes through {@code FMLPaths.CONFIGDIR}, which isn't worth redirecting for a round-trip test.
 *
 * <p>The load-bearing case is the first one: Normal must keep the exact pre-partition filename, or every
 * player's existing advancement profile silently becomes unreachable.</p>
 */
class GlobalAchievementStorePathTest {

    private static final UUID PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void normalKeepsTheOriginalUnpartitionedFilename() {
        assertEquals(PLAYER + ".json",
            GlobalAchievementStore.relativeFile(PLAYER, DifficultyPartition.LEGACY_KEY));
        // An unknown/blank partition means "we don't know", which is the legacy profile — never a new one.
        assertEquals(PLAYER + ".json", GlobalAchievementStore.relativeFile(PLAYER, ""));
        assertEquals(PLAYER + ".json", GlobalAchievementStore.relativeFile(PLAYER, null));
    }

    @Test
    void otherDifficultiesGetTheirOwnSubdirectory() {
        assertEquals("hard/" + PLAYER + ".json", GlobalAchievementStore.relativeFile(PLAYER, "hard"));
        assertEquals("easy/" + PLAYER + ".json", GlobalAchievementStore.relativeFile(PLAYER, "easy"));
        assertEquals("peaceful/" + PLAYER + ".json",
            GlobalAchievementStore.relativeFile(PLAYER, "peaceful"));
    }

    @Test
    void partitionsNeverCollide() {
        String normal = GlobalAchievementStore.relativeFile(PLAYER, "normal");
        String hard = GlobalAchievementStore.relativeFile(PLAYER, "hard");
        String easy = GlobalAchievementStore.relativeFile(PLAYER, "easy");
        assertNotEquals(normal, hard);
        assertNotEquals(hard, easy);
    }

    @Test
    void pathsAreLegalFilenamesOnWindowsToo() {
        // A subdirectory, deliberately: the Ender Chest slot key uses "|", which is fine in an NBT tag name
        // and illegal in a Windows filename. This store writes real files.
        for (String partition : new String[] {"normal", "hard", "easy", "peaceful"}) {
            String path = GlobalAchievementStore.relativeFile(PLAYER, partition);
            for (char illegal : new char[] {'|', ':', '*', '?', '"', '<', '>'}) {
                assertFalse(path.indexOf(illegal) >= 0, "illegal '" + illegal + "' in " + path);
            }
        }
    }
}
