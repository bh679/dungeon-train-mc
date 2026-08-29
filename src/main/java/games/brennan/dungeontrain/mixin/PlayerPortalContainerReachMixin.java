package games.brennan.dungeontrain.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.portal.PortalContainerLink;
import games.brennan.dungeontrain.portal.PortalRemoteViewers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The container-reach half of {@link ServerPlayerPortalContainerReachMixin}, at the call site that
 * actually closes menus.
 *
 * <p>1.21.1 tests {@code containerMenu.stillValid(this)} in <b>two</b> places: {@code
 * ServerPlayer.tick}, and {@code Player.tick} — the latter reached from {@code ServerPlayer.doTick}
 * via {@code super.tick()}. Only the {@code Player.tick} one was observed closing a portal
 * container in practice, and a mixin on {@code ServerPlayer} cannot reach an inherited method it
 * does not override, so it needs its own target here. Both are wrapped because either may close a
 * menu.</p>
 *
 * <p>Logic is identical to the sibling: measure reach against the cell the player actually clicked
 * ({@link PortalRemoteViewers}) rather than the block entity's own position, which for a redirected
 * portal container sits in the carriage's sub-level plot, nowhere near the player. Fail-open — any
 * fault falls through to vanilla's answer.</p>
 */
@Mixin(Player.class)
public abstract class PlayerPortalContainerReachMixin {

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
        boolean vanilla = original.call(menu, player);
        // TEMP DIAGNOSTIC — remove before merge.
        if (!vanilla && menu != player.inventoryMenu) {
            LOGGER.info("[DT-DIAG] Player.tick stillValid=false menu={} backing={} eye={}",
                menu.getClass().getSimpleName(), describe(menu, player), player.getEyePosition());
        }
        return vanilla;
    }

    /** TEMP DIAGNOSTIC — remove before merge. Where the menu's backing container actually lives. */
    private static String describe(AbstractContainerMenu menu, Player player) {
        try {
            if (menu.slots.isEmpty()) return "no-slots";
            Object container = menu.getSlot(0).container;
            if (container instanceof net.minecraft.world.level.block.entity.BlockEntity be) {
                BlockPos pos = be.getBlockPos();
                boolean sameBe = be.getLevel() != null && be.getLevel().getBlockEntity(pos) == be;
                return be.getClass().getSimpleName() + "@" + pos
                    + " sameBE=" + sameBe
                    + " reach=" + player.canInteractWithBlock(pos, CONTAINER_REACH_PADDING);
            }
            return container.getClass().getSimpleName();
        } catch (Throwable t) {
            return "?" + t;
        }
    }
}
