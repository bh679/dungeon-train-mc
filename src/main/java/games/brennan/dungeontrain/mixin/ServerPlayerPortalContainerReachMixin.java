package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalContainerLink;
import games.brennan.dungeontrain.portal.PortalRemoteViewers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lets a portal container stay open when it is being viewed through the pair's <i>other</i> copy.
 *
 * <p>A corridor's container and its twin's are one inventory — {@link PortalContainerLink} — so a
 * click on the twin copy is redirected to the canonical block entity in the carriage's sub-level
 * plot. Those two sit nowhere near each other in coordinates, and {@code ServerPlayer.tick} closes
 * any menu whose {@code stillValid} fails, which for a container is a reach check against the block
 * entity's own position. Without this the redirected menu would shut on the next tick, every time.</p>
 *
 * <p>So the one call is wrapped and the reach measured against the cell the player actually
 * <b>clicked</b> ({@link PortalRemoteViewers}) instead. Reach is not removed, only relocated: walk
 * away from the copy in front of you and the menu closes exactly as it would for any chest. The cell
 * must still hold a container too, which is what closes the menu when someone mines either copy out
 * from under the viewer — the mirror clears the partner, and the next tick finds nothing there.</p>
 *
 * <p>{@code WrapOperation} rather than {@code @Redirect} so other mods injecting at the same call
 * still compose — the same reason {@code EnderChestBlockLabelMixin} and {@code ChunkMapMixin} use
 * it. Fail-open in both directions: any fault falls through to vanilla's own answer, which at worst
 * closes a menu.</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerPortalContainerReachMixin {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Vanilla's own slack on a container reach check ({@code Container.stillValidBlockEntity}). */
    private static final double CONTAINER_REACH_PADDING = 4.0;

    @WrapOperation(
        method = "tick()V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/inventory/AbstractContainerMenu;"
                + "stillValid(Lnet/minecraft/world/entity/player/Player;)Z"))
    private boolean dungeontrain$portalRemoteContainerReach(
            AbstractContainerMenu menu, Player player, Operation<Boolean> original) {
        try {
            BlockPos viewed = PortalRemoteViewers.viewedFrom(player);
            if (viewed != null && player.level() instanceof ServerLevel level) {
                if (PortalContainerLink.containerAt(level, viewed) != null
                        && player.canInteractWithBlock(viewed, CONTAINER_REACH_PADDING)) {
                    return true;
                }
                // Out of reach, or the copy in front of them has been mined: let vanilla close it,
                // and stop asking on every tick from here on.
                PortalRemoteViewers.closed(player);
            }
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Portal container reach check failed; using vanilla's", t);
        }
        return original.call(menu, player);
    }
}
