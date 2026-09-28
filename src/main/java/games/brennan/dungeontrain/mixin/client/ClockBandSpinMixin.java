package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientVoidBand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Makes clocks spin inside the End and Nether bands, like they do in the real End and Nether.
 *
 * <p>The bands are stretches of the overworld, so vanilla's clock model ({@code ItemProperties$1},
 * the {@code minecraft:time} property) sees a {@code natural} dimension and keeps showing the time
 * of day. This flips that {@code DimensionType.natural()} read to {@code false} while the clock's
 * holder — the player, an item frame, a dropped item — stands where either band is past its
 * music crossover ({@code 0.5}), so vanilla takes its own random-spin path and its {@code wobble}
 * smooths the needle in and out of the band.</p>
 *
 * <p>Client-only model property: no server state or game logic changes. Self-disables outside the
 * overworld, on non-train worlds and when a band is turned off in config, since both band
 * intensities are {@code 0} there. Siblings: {@link LightTextureEndBandMixin},
 * {@link LightTextureNetherBandMixin}.</p>
 */
@Mixin(targets = "net.minecraft.client.renderer.item.ItemProperties$1")
public abstract class ClockBandSpinMixin {

    @ModifyExpressionValue(
            method = "unclampedCall",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/dimension/DimensionType;natural()Z"
            )
    )
    private boolean dungeontrain$spinInBands(boolean natural, @Local Entity holder) {
        if (!natural || holder == null) return natural;
        return !dungeontrain$inBand(holder);
    }

    /** True when the holder is in the overworld at a point where the End or Nether band has taken over. */
    @Unique
    private static boolean dungeontrain$inBand(Entity holder) {
        if (!holder.level().dimension().equals(Level.OVERWORLD)) return false;
        double x = holder.getX();
        return ClientVoidBand.endSkyIntensityAt(x) >= ClientVoidBand.MUSIC_CROSSOVER
                || ClientNetherBand.netherIntensityAt(x) >= ClientNetherBand.MUSIC_CROSSOVER;
    }
}
