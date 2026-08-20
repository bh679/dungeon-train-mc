package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.structure.BandFossilSiting;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFossilStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Lets {@code minecraft:nether_fossil} generate in the Nether band's core. Vanilla finds its resting place
 * by walking down the chunk generator's noise column; in the band that column is the overworld mountain the
 * core replaces, so {@link BandFossilSiting#site} walks the core terrain instead. Outside the band this
 * returns untouched and vanilla's own siting runs.
 */
@Mixin(NetherFossilStructure.class)
public abstract class NetherFossilStructureMixin extends Structure {

    private NetherFossilStructureMixin(StructureSettings settings) {
        super(settings);
    }

    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$siteInBand(GenerationContext context,
                                         CallbackInfoReturnable<Optional<GenerationStub>> cir) {
        NetherFossilStructure self = (NetherFossilStructure) (Object) this;
        Optional<GenerationStub> banded = BandFossilSiting.site(context, self.height, this.biomes());
        if (banded.isPresent()) cir.setReturnValue(banded);
    }
}
