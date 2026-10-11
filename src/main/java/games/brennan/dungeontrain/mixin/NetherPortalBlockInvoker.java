package games.brennan.dungeontrain.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.DimensionTransition;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches vanilla's private {@code NetherPortalBlock#getExitPortal}: find the nearest portal to
 * {@code exitPos} in {@code level} or build one, and wrap it in a {@link DimensionTransition} that keeps
 * the entity's position within the frame, plays the portal sound and places the chunk ticket. Used by
 * {@link NetherPortalBlockBandJumpMixin} with {@code isNether = false} (overworld search radius and
 * placement rules) for the along-the-ride portal.
 */
@Mixin(NetherPortalBlock.class)
public interface NetherPortalBlockInvoker {

    @Invoker("getExitPortal")
    @Nullable
    DimensionTransition dungeontrain$getExitPortal(ServerLevel level, Entity entity, BlockPos pos, BlockPos exitPos,
                                                   boolean isNether, WorldBorder worldBorder);
}
