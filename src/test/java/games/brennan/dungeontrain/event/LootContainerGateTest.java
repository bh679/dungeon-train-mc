package games.brennan.dungeontrain.event;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coverage for {@link AchievementEvents#isLootContainer} — the single definition of "loot" shared by
 * the chest-open path and the chest-break path.
 *
 * <p>The two paths used to disagree: only a right-click ended the "no chest or barrel" streak, so a
 * player could mine every container on the train, take the same loot off the floor, and still earn
 * "Not My Chest" and top the {@code carriages_no_chest} board. They now run the same predicate, and
 * these cases pin what it does and does not consider loot — an ender chest is the player's own, and
 * a decorated pot has always been fair game for this streak.</p>
 */
class LootContainerGateTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("chests, trapped chests and barrels are loot")
    void containersAreLoot() {
        assertTrue(AchievementEvents.isLootContainer(Blocks.CHEST), "chest");
        assertTrue(AchievementEvents.isLootContainer(Blocks.TRAPPED_CHEST), "trapped chest");
        assertTrue(AchievementEvents.isLootContainer(Blocks.BARREL), "barrel");
    }

    @Test
    @DisplayName("ender chests and vases are not — they never touch the container streak")
    void nonLootIsExcluded() {
        assertFalse(AchievementEvents.isLootContainer(Blocks.ENDER_CHEST), "ender chest");
        assertFalse(AchievementEvents.isLootContainer(Blocks.DECORATED_POT), "decorated pot");
        assertFalse(AchievementEvents.isLootContainer(Blocks.STONE), "an ordinary block");
    }
}
