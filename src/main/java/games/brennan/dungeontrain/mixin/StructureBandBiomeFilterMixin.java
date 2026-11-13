package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.legacy.preset.PresetTerrain;
import games.brennan.dungeontrain.worldgen.LostCityMixStarts;
import games.brennan.dungeontrain.worldgen.LostCityStructures;
import games.brennan.dungeontrain.worldgen.WorldGenCycle;
import games.brennan.dungeontrain.worldgen.structure.BandBetterNetherStructures;
import games.brennan.dungeontrain.worldgen.structure.BandNetherStructures;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
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

    /**
     * No structure starts in the mix zone: its chunks each generate as a different band, so a multi-chunk
     * structure would straddle unrelated terrain (a village half over void, a city half in the Nether). The
     * one exception is a Lost City building (never a street prop) in a chunk that picked the Lost City ({@link LostCityMixStarts}): the
     * void chunks around it skip structure pieces, so it stands cut to its own chunk.
     */
    @Inject(method = "findValidGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$noStructuresInMixZone(Structure.GenerationContext context,
                                                   CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (!PresetTerrain.isTrainOverworldGenerator(context.chunkGenerator())) return;
        if (!WorldGenCycle.fromConfig().mixPicksAt(context.chunkPos().getMinBlockX())) return;
        ResourceLocation id = context.registryAccess().registryOrThrow(Registries.STRUCTURE).getKey((Structure) (Object) this);
        if (LostCityStructures.isBuilding(id)
                && LostCityMixStarts.picksLostCity(context.chunkPos().x, context.chunkPos().z)) return;
        cir.setReturnValue(Optional.empty());
    }

    @Inject(method = "findValidGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$skipBiomeFilterInBand(Structure.GenerationContext context,
                                                    CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (!BandNetherStructures.isBandEligible((Structure) (Object) this)) return;
        if (BandNetherStructures.open(context, 0) == null) return;   // not band core — vanilla's filter stands
        cir.setReturnValue(this.findGenerationPoint(context));
    }

    /**
     * BetterNether's structures on the train overworld: only in a BetterNether band core, sited by their own
     * code against the band's Nether ({@link BandBetterNetherStructures}). The base-class biome re-check is
     * skipped for the same reason as above; the scope already checked the core biome where they looked.
     */
    @Inject(method = "findValidGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$betterNetherInBand(Structure.GenerationContext context,
                                                 CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (!BandBetterNetherStructures.isBandEligible((Structure) (Object) this)) return;
        if (!PresetTerrain.isTrainOverworldGenerator(context.chunkGenerator())) return;
        cir.setReturnValue(BandBetterNetherStructures.findInBand((Structure) (Object) this, context,
                this::findGenerationPoint));
    }
}
