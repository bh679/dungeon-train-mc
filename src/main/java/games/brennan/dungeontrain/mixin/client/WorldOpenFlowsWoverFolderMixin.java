package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.brennan.dungeontrain.compat.WoverWorldFolder;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Hands a new world's folder to WorldWeaver the moment {@code createFreshLevel} creates it, before the
 * world loads. Without it WorldWeaver saves the new world's generator settings into the previous world's
 * folder, bringing back run worlds that were just deleted. See {@link WoverWorldFolder}.
 *
 * <p>Hooks the {@code createWorldAccess} call rather than HEAD, so the call
 * {@code WorldOpenFlowsDelayMixin} cancels and parks runs nothing; only the real one does.</p>
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsWoverFolderMixin {

    @ModifyExpressionValue(method = "createFreshLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;createWorldAccess(Ljava/lang/String;)"
                    + "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;"))
    private LevelStorageSource.LevelStorageAccess dungeontrain$pointWoverAtNewWorld(
            LevelStorageSource.LevelStorageAccess access) {
        WoverWorldFolder.pointAt(access);
        return access;
    }
}
