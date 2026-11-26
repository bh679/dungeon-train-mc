package games.brennan.dungeontrain.mixin.client.positionreadout.xaerominimap;

import games.brennan.dungeontrain.client.CoordinateDisguise;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes Xaero's Minimap info lines print the position and dimension the debug screen prints
 * ({@link CoordinateDisguise}).
 *
 * <p>Each built-in info display is a lambda in {@code BuiltInInfoDisplays} handed the player's
 * {@link BlockPos}. Only the ones that do nothing but print it are shifted — coordinates
 * ({@code lambda$static$0}), the overworld-scaled coordinates ({@code $2}) and chunk coordinates
 * ({@code $3}) — so biome and light, which look the position up, still read the real place. The
 * dimension line ({@code $6}) gets the band name. Lambda numbers are as of Xaero's Minimap 26.6.0;
 * an update that renumbers them leaves the true figures showing (the plugin logs what applied).</p>
 */
@Pseudo
@Mixin(targets = "xaero.hud.minimap.info.BuiltInInfoDisplays", remap = false)
public abstract class XaeroMinimapInfoMixin {

    @ModifyVariable(method = {"lambda$static$0", "lambda$static$2", "lambda$static$3"},
        at = @At("HEAD"), argsOnly = true, require = 0, remap = false)
    private static BlockPos dungeontrain$disguisePosition(BlockPos pos) {
        try {
            return CoordinateDisguise.shifted(pos, CoordinateDisguise.cameraShift());
        } catch (Throwable ignored) {
            return pos;
        }
    }

    @Redirect(method = "lambda$static$6", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/resources/ResourceKey;location()Lnet/minecraft/resources/ResourceLocation;"),
        require = 0, remap = false)
    private static ResourceLocation dungeontrain$disguiseDimension(ResourceKey<?> key) {
        ResourceLocation real = key.location();
        try {
            return CoordinateDisguise.shownDimension(real);
        } catch (Throwable ignored) {
            return real;
        }
    }
}
