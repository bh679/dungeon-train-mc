package games.brennan.dungeontrain.mixin.bclib;

import org.betterx.bclib.api.v2.datafixer.DataFixerAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/**
 * Skips BCLib's "Guardian detected an incompatible World" prompt. When a saved world's recorded patch
 * level is behind the patches BCLib, BetterNether or BetterEnd register (or the save has no record at all),
 * {@code DataFixerAPI.fixData} calls {@code showBackupWarning}, which opens {@code ConfirmFixScreen} and
 * waits for the player to click Proceed. BetterX is a pinned dependency the player never chose, so the
 * prompt is only friction. This answers it the way Proceed does with "Create Backup" unchecked: the fixes
 * still run (BCLib's own progress screen and error screen still show), and the whole-world backup is
 * skipped, since DT worlds are one-life runs and copying a large train world is slow.
 */
@Mixin(value = DataFixerAPI.class, remap = false)
public abstract class BclibFixPromptMixin {

    @Unique
    private static final Logger DUNGEONTRAIN$LOGGER = LoggerFactory.getLogger("DungeonTrain/BclibFix");

    @Inject(method = "showBackupWarning", at = @At("HEAD"), cancellable = true)
    private static void dungeontrain$autoProceed(String levelId, BiConsumer<Boolean, Boolean> callback, CallbackInfo ci) {
        DUNGEONTRAIN$LOGGER.info("[DT] Auto-applying BCLib world patches for '{}' (prompt suppressed, no backup)", levelId);
        ci.cancel();
        callback.accept(false, true);
    }
}
