package games.brennan.dungeontrain.mixin.effortlessbuilding;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.compat.EffortlessBuildingMirror;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Effortless Building's own undo / redo ({@code Z}) rewrites cells with raw {@code setBlock} inside
 * {@code UndoManager}, firing no block events. Each write is noted for
 * {@link EffortlessBuildingMirror}, so rewinding a build inside a mirror-enabled editor plot rewinds
 * its mirrored half too. The record is opened and flushed by
 * {@link EffortlessBuildingPacketHandlerMixin}'s {@code handleUndo} / {@code handleRedo} pair.
 *
 * <p>Same fragility contract as {@link EffortlessBuildingPacketHandlerMixin}: read out of the pinned
 * {@code effortlessbuilding-4.2+1.21.1}, {@code required: false}, {@code remap = false}.</p>
 */
@Mixin(targets = "neoforge.nl.requios.effortlessbuilding.utilities.UndoManager", remap = false)
public abstract class EffortlessBuildingUndoManagerMixin {

    @WrapOperation(
        method = {"undo", "redo"},
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"),
        remap = false)
    private static boolean dungeontrain$recordSetBlock(
            ServerLevel level, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        boolean changed = original.call(level, pos, state, flags);
        if (changed) EffortlessBuildingMirror.record(pos);
        return changed;
    }
}
