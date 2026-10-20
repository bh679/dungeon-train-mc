package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.ship.sable.SableStorageSync;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.jetbrains.annotations.Nullable;

/**
 * Tells the Sable save seam whether the save in progress is a flush save.
 *
 * <p>Vanilla's {@code ServerLevel.save(progress, flush, skipSave)} already carries the answer:
 * the periodic autosave and the singleplayer pause save pass {@code flush=false} and vanilla then
 * leaves its region writes in the OS cache; shutdown, Save &amp; Quit and {@code /save-all flush}
 * pass {@code true} and vanilla syncs. Sable's own {@code plot/ServerLevelMixin} runs its holding
 * save inside this same method but ignores the flag and syncs every file every time, which is the
 * 1.5-6 s autosave freeze players report. {@link SubLevelStorageFileNoDsyncMixin} reads the flag
 * through {@link SableStorageSync#shouldForce()}.</p>
 *
 * <p>HEAD sets the flag, RETURN restores the default ({@code true}), so a Sable flush outside a
 * level save keeps syncing. DT's own in-game catch-up save
 * ({@code SableShipyard#saveSubLevelsNow}) sets the flag to {@code false} itself, since it is
 * never a shutdown. Saves run on the server thread, one level at a time.</p>
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSaveFlushFlagMixin {

    @Inject(method = "save", at = @At("HEAD"))
    private void dungeontrain$beginLevelSave(
        @Nullable ProgressListener progress, boolean flush, boolean skipSave, CallbackInfo ci) {
        SableStorageSync.beginLevelSave(flush);
    }

    @Inject(method = "save", at = @At("RETURN"))
    private void dungeontrain$endLevelSave(
        @Nullable ProgressListener progress, boolean flush, boolean skipSave, CallbackInfo ci) {
        SableStorageSync.endLevelSave();
    }
}
