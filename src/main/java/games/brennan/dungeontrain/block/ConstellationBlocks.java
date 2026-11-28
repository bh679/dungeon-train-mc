package games.brennan.dungeontrain.block;

import games.brennan.dungeontrain.perks.PerksGate;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * What the two constellation blocks ({@link ConstellationWindowBlock}, {@link ConstellationHazeBlock})
 * share: you can stand <em>in</em> one but never <em>on</em> one, and outside a perks-on world they
 * are not there at all.
 *
 * <p>Standing in one opens a patch of night sky holding the perks constellation, 45 degrees up — see
 * {@code client.constellation.ConstellationFade}. The block itself only answers "is this a
 * constellation block, and is it switched on".</p>
 */
public final class ConstellationBlocks {

    private ConstellationBlocks() {}

    /**
     * Are constellation blocks live in this world? Only where perks are on (today: perks test worlds).
     *
     * <p>Asked from shape and render code that does not know its side, so it ORs both answers: a
     * dedicated server only ever sets the server flag, a remote client only the client flag, and in
     * single player they agree.</p>
     */
    public static boolean active() {
        return PerksGate.isOn() || PerksGate.isOnClient();
    }

    /** Is {@code state} either constellation block, switched on? */
    public static boolean isLive(BlockState state) {
        Block block = state.getBlock();
        return (block instanceof ConstellationWindowBlock || block instanceof ConstellationHazeBlock) && active();
    }

    /**
     * The outline / raycast shape: a full cube for a creative player in a live world (so it can be
     * picked and broken), nothing otherwise. Survival players cannot obtain these blocks, and an
     * outline drawn round a hole in the sky would break the illusion.
     */
    public static VoxelShape outline(CollisionContext context) {
        boolean creative = context instanceof EntityCollisionContext entity
            && entity.getEntity() instanceof Player player && player.isCreative();
        return creative && active() ? Shapes.block() : Shapes.empty();
    }
}
