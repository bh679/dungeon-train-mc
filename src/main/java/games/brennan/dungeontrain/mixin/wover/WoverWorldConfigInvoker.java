package games.brennan.dungeontrain.mixin.wover;

import net.minecraft.world.level.storage.LevelStorageSource;
import org.betterx.wover.state.impl.WorldConfigImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reaches WorldWeaver's private {@code WorldConfigImpl.loadForWorld}, the method its
 * {@code WORLD_FOLDER_READY} event runs to point its per-world config (and the static folder it saves
 * {@code wover-generator.nbt} into) at a world. {@code client.WorldOpenFlowsWoverFolderMixin} calls it as
 * soon as {@code createFreshLevel} has the new world's folder. See {@code compat.WoverWorldFolder}.
 *
 * <p>Guarded by {@code ThirdPartyMixinTargets}: when a WorldWeaver build renames the method this mixin is
 * skipped and the body below runs instead, which {@code WoverWorldFolder} catches.</p>
 */
@Mixin(value = WorldConfigImpl.class, remap = false)
public interface WoverWorldConfigInvoker {

    @Invoker("loadForWorld")
    static void dungeontrain$loadForWorld(LevelStorageSource.LevelStorageAccess access) {
        throw new UnsupportedOperationException("WoverWorldConfigInvoker was not applied");
    }
}
