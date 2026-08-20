package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.structure.BandNetherStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Skips the base-class biome filter for the four vanilla Nether structures when they are being sited inside
 * the Nether band, because that filter asks the wrong authority there.
 *
 * <p>{@code Structure.findValidGenerationPoint} re-asks the <b>overworld</b> biome source at the stub's
 * position. In the band that answer is only as good as DT's biome-forcing mixin, and that mixin
 * deliberately yields to the original overworld biome below sea level — which is exactly where a bastion's
 * rolled height or a ruined portal's Nether-space Y regularly lands. A perfectly good site would be thrown
 * away for being "not a Nether biome" while the terrain around it is netherrack.
 *
 * <p>The band's own siting already applied the same {@code #minecraft:has_structure/…} restriction against
 * the <b>real Nether's</b> biome source, at the very patch of Nether the core terrain was sampled from, so
 * the check has been made — and made more accurately. This only fires when that siting actually produced a
 * position ({@link BandNetherStructures#isBandEligible} plus a non-empty stub); everything else, in every
 * dimension, keeps vanilla's filter.</p>
 */
@Mixin(Structure.class)
public abstract class StructureBandBiomeFilterMixin {

    @Shadow
    protected abstract Optional<Structure.GenerationStub> findGenerationPoint(Structure.GenerationContext context);

    @Inject(method = "findValidGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipBiomeFilterInBand(Structure.GenerationContext context,
                                                    CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (!BandNetherStructures.isBandEligible((Structure) (Object) this)) return;
        if (BandNetherStructures.open(context, 0) == null) return;   // not band core — vanilla's filter stands
        cir.setReturnValue(this.findGenerationPoint(context));
    }
}
