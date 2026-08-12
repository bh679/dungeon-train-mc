package games.brennan.dungeontrain.player;

import net.minecraft.world.Difficulty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The difficulty-profile key: pure string/enum mapping, no world. The one behaviour worth pinning is
 * that Normal keeps the un-suffixed storage keys — that is what stops this change from stranding every
 * Ender Chest stash that already exists.
 */
class DifficultyPartitionTest {

    @Test
    void keyIsTheVanillaSerializedName() {
        assertEquals("peaceful", DifficultyPartition.keyFor(Difficulty.PEACEFUL));
        assertEquals("easy", DifficultyPartition.keyFor(Difficulty.EASY));
        assertEquals("normal", DifficultyPartition.keyFor(Difficulty.NORMAL));
        assertEquals("hard", DifficultyPartition.keyFor(Difficulty.HARD));
    }

    @Test
    void normalIsTheLegacyPartitionAndTakesNoSuffix() {
        assertEquals("normal", DifficultyPartition.LEGACY_KEY);
        assertEquals("", DifficultyPartition.suffixFor(Difficulty.NORMAL));
        assertEquals("", DifficultyPartition.suffixFor("normal"));
        // An absent/blank key means "we don't know" → treat as legacy, never as a fifth partition.
        assertEquals("", DifficultyPartition.suffixFor((String) null));
        assertEquals("", DifficultyPartition.suffixFor(""));
    }

    @Test
    void everyOtherDifficultyGetsItsOwnSuffix() {
        assertEquals("|hard", DifficultyPartition.suffixFor(Difficulty.HARD));
        assertEquals("|easy", DifficultyPartition.suffixFor(Difficulty.EASY));
        assertEquals("|peaceful", DifficultyPartition.suffixFor(Difficulty.PEACEFUL));
    }

    @Test
    void scopedKeysLeaveTheLegacyPartitionUntouched() {
        // "survival" is EnderChestPersistence's game-mode slot key: on Normal it must resolve to exactly
        // the key an existing chest is already stored under.
        assertEquals("survival", DifficultyPartition.scopeKey("survival", "normal"));
        assertEquals("survival|hard", DifficultyPartition.scopeKey("survival", "hard"));
        assertEquals("adventure|peaceful", DifficultyPartition.scopeKey("adventure", "peaceful"));
    }

    @Test
    void everyDifficultyMapsToADistinctPartition() {
        // Four difficulties, four distinct scoped keys — no two profiles can ever share storage.
        assertEquals(Difficulty.values().length,
            java.util.Arrays.stream(Difficulty.values())
                .map(d -> DifficultyPartition.scopeKey("survival", DifficultyPartition.keyFor(d)))
                .distinct()
                .count());
        assertTrue(java.util.Arrays.stream(Difficulty.values())
            .anyMatch(d -> DifficultyPartition.suffixFor(d).isEmpty()), "exactly the legacy one is bare");
    }
}
