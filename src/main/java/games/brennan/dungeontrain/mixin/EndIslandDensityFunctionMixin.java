package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.ColumnMemo;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Memoises vanilla's {@code end_islands} density function by block column.
 *
 * <p>{@code EndIslandDensityFunction.compute} reads only {@code blockX} and {@code blockZ}, then runs 625
 * simplex evaluations — and is asked the same column over and over: once per quart by every End biome
 * source (vanilla's and WorldWeaver's), and once per noise cell corner by the terrain interpolator, because
 * {@code RandomState} strips the {@code cache_2d} marker and the End's router has none around it in the
 * interpolated density. On an End-band sample that was ~70 % of the whole sample (JFR, 2026-10-01); the
 * live End pays the same. One {@link ColumnMemo} per function instance (each End random state has its
 * own), so different seeds never share an answer.</p>
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$EndIslandDensityFunction")
public abstract class EndIslandDensityFunctionMixin {

    @Unique
    private final ColumnMemo dungeontrain$memo = new ColumnMemo();

    @Inject(method = "compute", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$answerFromMemo(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        Double known = dungeontrain$memo.get(context.blockX(), context.blockZ());
        if (known != null) cir.setReturnValue(known);
    }

    @Inject(method = "compute", at = @At("RETURN"))
    private void dungeontrain$remember(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        dungeontrain$memo.put(context.blockX(), context.blockZ(), cir.getReturnValueD());
    }
}
