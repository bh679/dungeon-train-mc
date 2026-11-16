package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * Which blocks a dimensional carriage's corridor (the hallway riding the train and its twin at the
 * room end) refuses to have placed.
 *
 * <p>The corridor exists twice and {@link PortalEditMirror} copies every edit from either copy to the
 * other, block <i>state</i> only. A container mirrored as a bare block is a second container whose
 * contents are not the first one's: what a player stores in one copy is missing from the other, and
 * a shulker box broken in the copy drops a second box outright. So the corridor does not take
 * storage. The dimensional room past it has no twin and no mirror, so it takes anything.</p>
 *
 * <p>By class rather than by item list or tag: {@link ChestBlock} is the chest and the trapped chest
 * (not the ender chest, which is an {@code EnderChestBlock}: its contents live with the player, so
 * there is nothing in the block to diverge), {@link ShulkerBoxBlock} every colour, and any modded
 * subclass of the four is caught without a list to keep in sync.</p>
 *
 * <p>Pure, so it can be unit-tested without a world.</p>
 */
public final class PortalCorridorContainers {

    private PortalCorridorContainers() {}

    /** True if this block may not be placed in either copy of a dimensional carriage's corridor. */
    public static boolean isRefusedInCorridor(Block block) {
        return block instanceof ChestBlock
            || block instanceof BarrelBlock
            || block instanceof HopperBlock
            || block instanceof ShulkerBoxBlock;
    }
}
