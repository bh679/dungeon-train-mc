package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.OtherworldBand;
import net.minecraft.client.renderer.item.CompassItemPropertyFunction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes spawn compasses spin inside the End and Nether bands and from the Far Lands onward, like they
 * do in the real End and Nether.
 *
 * <p>In those dimensions a plain compass has no spawn to point at ({@code CompassItem.getSpawnPosition}
 * is {@code null} when the dimension is not {@code natural}), so vanilla's
 * {@code getCompassRotation} falls back to its random spin. The bands are overworld, so this clears
 * the target while the holder is in one of those stretches ({@link OtherworldBand#at}). Lodestone and recovery compasses
 * keep their targets, exactly as in the real dimensions.</p>
 *
 * <p>Client-only model property; sibling of {@link ClockBandSpinMixin}.</p>
 */
@Mixin(CompassItemPropertyFunction.class)
public abstract class CompassBandSpinMixin {

    @ModifyExpressionValue(
            method = "getCompassRotation",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/item/CompassItemPropertyFunction$CompassTarget;"
                            + "getPos(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/world/item/ItemStack;"
                            + "Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/core/GlobalPos;"
            )
    )
    private GlobalPos dungeontrain$spinInBands(GlobalPos target,
                                               @Local(argsOnly = true) ItemStack stack,
                                               @Local(argsOnly = true) Entity holder) {
        if (target == null || !stack.is(Items.COMPASS) || stack.has(DataComponents.LODESTONE_TRACKER)) {
            return target;
        }
        return OtherworldBand.at(holder) ? null : target;
    }
}
