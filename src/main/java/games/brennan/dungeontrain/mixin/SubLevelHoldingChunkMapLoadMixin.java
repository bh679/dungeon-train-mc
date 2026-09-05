package games.brennan.dungeontrain.mixin;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import games.brennan.dungeontrain.ship.sable.SableHoldingIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Clears Dungeon Train's holding record when Sable takes a sub-level back out of holding, the
 * counterpart to {@link SubLevelHoldingChunkFileMixin}. See {@link SableHoldingIndex}.
 *
 * <p>{@code loadHoldingSubLevel} is the single funnel every resurrection passes through —
 * {@code snatchAndLoad} (DT's explicit reload) and {@code processChanges} →
 * {@code collectReadySubLevels} (Sable's own chunk-load path) both end there.</p>
 *
 * <p><b>The entry is dropped whether or not the load succeeded.</b> Bytecode-verified against
 * {@code sable-2.0.5+mc1.21.1}: {@code loadHoldingSubLevel} ends with
 * {@code allHoldingSubLevels.remove(uuid)} outside the {@code fullyLoad != null} branch, so after
 * a failed deserialize the data is unreachable through Sable's holding store and nothing will
 * retry it. DT must agree, or it would tell the spawn lanes to keep waiting for a group that can
 * never come back. {@code @At("TAIL")} so DT's clear lands after Sable's own.</p>
 *
 * <p>{@code queueDeletion} is belt-and-braces: a sub-level being deleted for good should not be
 * carrying a holding claim. It fires for the {@code REMOVED} path that
 * {@code SableShipyard.delete} produces.</p>
 *
 * <p><b>Re-verify on any {@code sable_version} bump.</b> {@code remap = false}: Sable's own names.</p>
 */
@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class SubLevelHoldingChunkMapLoadMixin {

    @Inject(method = "loadHoldingSubLevel", at = @At("TAIL"))
    private void dungeonTrain$recordLoaded(HoldingSubLevel held, CallbackInfo ci) {
        if (held == null) return;
        SubLevelData data = held.data();
        if (data == null) return;
        SableHoldingIndex.loaded(data.uuid());
    }

    @Inject(method = "queueDeletion", at = @At("HEAD"))
    private void dungeonTrain$recordDeleted(ServerSubLevel subLevel, CallbackInfo ci) {
        if (subLevel == null) return;
        SableHoldingIndex.loaded(subLevel.getUniqueId());
    }
}
