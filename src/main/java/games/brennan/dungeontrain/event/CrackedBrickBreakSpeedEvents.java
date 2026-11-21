package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Cracked blocks break a little faster than their whole versions. Vanilla gives each cracked block
 * the same hardness as its uncracked sibling (stone bricks 1.5, deepslate bricks/tiles 3.5, nether
 * bricks 2.0, polished blackstone bricks 1.5), so the cracked one, which looks weaker, took exactly
 * as long to mine.
 *
 * <p>"Cracked" means any block whose registry path starts with {@code cracked_}, in any namespace,
 * so modded cracked blocks follow the same rule. Infested blocks
 * ({@code infested_cracked_stone_bricks}) don't match; they are already soft.</p>
 *
 * <p>Runs on both sides: the client predicts break progress and the server checks it, so both must
 * agree on the speed or the server would reject the break. Players only; mob digging
 * ({@code combat/MobDigGoal}) reads hardness directly and is unchanged.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CrackedBrickBreakSpeedEvents {

    /** How much faster cracked blocks break than their whole versions (1.2 = 20% faster). */
    static final float CRACKED_BREAK_SPEED_MULTIPLIER = 1.2f;

    private static final String CRACKED_PREFIX = "cracked_";

    private CrackedBrickBreakSpeedEvents() {}

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        event.setNewSpeed(adjustedSpeed(event.getState(), event.getNewSpeed()));
    }

    /** The break speed for {@code state}, given the speed computed so far. */
    static float adjustedSpeed(BlockState state, float speed) {
        return isCracked(state) ? speed * CRACKED_BREAK_SPEED_MULTIPLIER : speed;
    }

    /** Whether {@code state}'s block is a cracked block (registry path starts with {@code cracked_}). */
    static boolean isCracked(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith(CRACKED_PREFIX);
    }
}
