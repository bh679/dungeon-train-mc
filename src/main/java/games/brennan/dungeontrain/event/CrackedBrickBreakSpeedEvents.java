package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Cracked stone bricks break a little faster than whole stone bricks. Vanilla gives both the same
 * hardness (1.5), so the cracked block, which looks weaker, took exactly as long to mine.
 *
 * <p>Runs on both sides: the client predicts break progress and the server checks it, so both must
 * agree on the speed or the server would reject the break. Players only; mob digging
 * ({@code combat/MobDigGoal}) reads hardness directly and is unchanged.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class CrackedBrickBreakSpeedEvents {

    /** How much faster cracked stone bricks break than stone bricks (1.2 = 20% faster). */
    static final float CRACKED_BREAK_SPEED_MULTIPLIER = 1.2f;

    private CrackedBrickBreakSpeedEvents() {}

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        event.setNewSpeed(adjustedSpeed(event.getState(), event.getNewSpeed()));
    }

    /** The break speed for {@code state}, given the speed computed so far. */
    static float adjustedSpeed(BlockState state, float speed) {
        return state.is(Blocks.CRACKED_STONE_BRICKS) ? speed * CRACKED_BREAK_SPEED_MULTIPLIER : speed;
    }
}
