package games.brennan.dungeontrain.mixin.betternether;

import games.brennan.dungeontrain.worldgen.structure.BandBetterNetherStructures;
import net.minecraft.core.BlockPos;
import org.betterx.betternether.world.structures.city.CityStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Builds a BetterNether city at the band's Nether y 40 instead of the overworld's. BetterNether's
 * {@code CityStructure.getCenter} hard-codes {@code y = 40} — the Nether's own height — for both the start
 * position and every building and the cave it digs, and the pieces keep that y in their own fields, so it
 * can't be moved afterwards. Inside a {@link BandBetterNetherStructures} scope (a city siting in a
 * BetterNether band core) the centre's y is translated onto the band; everywhere else, the real Nether
 * included, it is returned unchanged. Guarded by {@code ThirdPartyMixinPlugin}
 * ({@code compat/mixinguard/ThirdPartyMixinTargets}).
 */
@Mixin(value = CityStructure.class, remap = false)
public abstract class BetterNetherCityCenterMixin {

    @Inject(method = "getCenter", at = @At("RETURN"), cancellable = true)
    private static void dungeontrain$bandCenter(CallbackInfoReturnable<BlockPos> cir) {
        if (!BandBetterNetherStructures.inScope()) return;
        BlockPos center = cir.getReturnValue();
        cir.setReturnValue(new BlockPos(center.getX(), BandBetterNetherStructures.cityCenterY(center.getY()), center.getZ()));
    }
}
