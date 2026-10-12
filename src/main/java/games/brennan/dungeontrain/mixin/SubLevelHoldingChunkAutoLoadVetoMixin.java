package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import games.brennan.dungeontrain.train.HeldGroupWakeVeto;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps Sable from waking a held carriage group the train does not want right now. See
 * {@link HeldGroupWakeVeto} for the loop this closes.
 *
 * <p><b>Why this method.</b> Bytecode-verified against {@code sable-2.0.5+mc1.21.1}:
 * {@code canLoadSubLevel} is the private static gate {@code collectReadySubLevels} consults
 * before it takes a held sub-level out of its holding chunk — "is every chunk under its stored
 * bounds loaded enough". It is that method's only caller, and {@code collectReadySubLevels} is
 * Sable's automatic wake (run from {@code processChanges} every container tick). A false answer
 * leaves the entry in the chunk untouched, exactly as when the chunks are not loaded yet, so it is
 * asked again next tick and wakes as soon as the veto lifts. Dungeon Train's own reloads go
 * through {@code snatchAndLoad}, which never calls this, so they are unaffected.</p>
 *
 * <p><b>Re-verify the target and its single caller on any {@code sable_version} bump.</b> Left
 * {@code required}: a silent no-op would bring the reload/cull loop back with no log line.</p>
 *
 * <p>{@code remap = false}: Sable's own names, not Minecraft mappings.</p>
 */
@Mixin(value = SubLevelHoldingChunk.class, remap = false)
public abstract class SubLevelHoldingChunkAutoLoadVetoMixin {

    @Inject(method = "canLoadSubLevel", at = @At("HEAD"), cancellable = true)
    private static void dungeonTrain$leaveUnwantedTrainGroupsHeld(
        ServerLevel level, SubLevelData data, CallbackInfoReturnable<Boolean> cir
    ) {
        if (level == null || data == null) return;
        if (HeldGroupWakeVeto.shouldLeaveHeld(data.uuid(), level.getGameTime())) {
            cir.setReturnValue(false);
        }
    }
}
