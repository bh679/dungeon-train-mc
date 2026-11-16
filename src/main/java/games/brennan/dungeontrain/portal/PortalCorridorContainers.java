package games.brennan.dungeontrain.portal;

import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * Which blocks a portal room — the room body and every copy of it — refuses to have placed.
 *
 * <p>A room does not stay where it was put. It is re-stamped when the train drifts, tiled into copies
 * that come and go, relocated, captured and carried into other worlds. Blocks survive all of that as
 * blocks; what a container <i>holds</i> does not reliably travel with it, so storage left in a room is
 * either lost or, where a copy is captured with its contents, duplicated. So the room simply does not
 * take storage. Everything else in it stays fully editable, and containers stamped from the room's own
 * template, or placed before this existed, can still be opened and broken.</p>
 *
 * <p>By class rather than by item list or tag: {@link ChestBlock} is the chest and the trapped chest
 * (not the ender chest, which is an {@code EnderChestBlock} — its contents live with the player, so
 * there is nothing in the block to lose), {@link ShulkerBoxBlock} every colour, and any modded
 * subclass of the four is caught without a list to keep in sync.</p>
 *
 * <p>Pure, so it can be unit-tested without a world.</p>
 */
public final class PortalRoomContainers {

    private PortalRoomContainers() {}

    /** True if this block may not be placed inside a portal room or any copy of it. */
    public static boolean isRefusedInRoom(Block block) {
        return block instanceof ChestBlock
            || block instanceof BarrelBlock
            || block instanceof HopperBlock
            || block instanceof ShulkerBoxBlock;
    }
}
