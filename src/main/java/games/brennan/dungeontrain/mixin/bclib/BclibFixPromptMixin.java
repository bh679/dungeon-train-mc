package games.brennan.dungeontrain.mixin.bclib;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.world.DungeonTrainSave;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.betterx.bclib.api.v2.datafixer.DataFixerAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.io.File;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Skips BCLib's "Guardian detected an incompatible World" prompt for Dungeon Train worlds only. When a
 * saved world's recorded patch level is behind the patches BCLib, BetterNether or BetterEnd register (or
 * the save has no record at all), {@code DataFixerAPI.fixData} calls {@code showBackupWarning}, which
 * opens {@code ConfirmFixScreen} and waits for the player to click Proceed. In a DT world BetterX is a
 * pinned dependency the player never chose, so the prompt is only friction: this answers it the way
 * Proceed does with "Create Backup" unchecked. The fixes still run (BCLib's own progress screen and error
 * screen still show), and the whole-world backup is skipped, since DT worlds are one-life runs and copying
 * a large train world is slow. Dungeon Backup does not cover this either: it archives player data under
 * {@code <gameDir>/dungeontrain}, not world saves.
 *
 * <p>Any other world, a BetterNether/BetterEnd world the player made themselves, gets BCLib's prompt
 * untouched. The DT check reads the save folder ({@link DungeonTrainSave}: {@code level.dat}, then the
 * preset marker that covers Compatible Terrain worlds) because this runs before the world opens. Wrapping the call inside the private {@code fixData(File, …)} rather than
 * {@code showBackupWarning} itself is what gives us the save folder; the latter only sees the level id.</p>
 */
@Mixin(value = DataFixerAPI.class, remap = false)
public abstract class BclibFixPromptMixin {

    @Unique
    private static final Logger DUNGEONTRAIN$LOGGER = LoggerFactory.getLogger("DungeonTrain/BclibFix");

    @WrapOperation(
            method = "fixData(Ljava/io/File;Ljava/lang/String;ZLjava/util/function/Consumer;Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;)Z",
            at = @At(value = "INVOKE",
                    target = "Lorg/betterx/bclib/api/v2/datafixer/DataFixerAPI;showBackupWarning(Ljava/lang/String;Ljava/util/function/BiConsumer;)V"))
    private static void dungeontrain$autoProceedInDtWorlds(String levelId, BiConsumer<Boolean, Boolean> callback,
                                                          Operation<Void> original,
                                                          File levelBaseDir, String levelID, boolean showUI,
                                                          Consumer<Boolean> onResume,
                                                          LevelStorageSource.LevelStorageAccess access) {
        if (!DungeonTrainSave.isDungeonTrainSave(levelBaseDir.toPath(),
                DungeonTrainCommonConfig.getDefaultCompatibleTerrain())) {
            original.call(levelId, callback);
            return;
        }
        DUNGEONTRAIN$LOGGER.info("[DT] Auto-applying BCLib world patches for Dungeon Train world '{}' (prompt suppressed, no backup)", levelId);
        callback.accept(false, true);
    }
}
