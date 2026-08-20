package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.structure.BandNetherStructures;
import games.brennan.dungeontrain.worldgen.structure.BandRuinedPortalSiting;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.RuinedPortalStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Lets {@code minecraft:ruined_portal_nether} generate in the Nether band's core.
 *
 * <p>All seven ruined-portal variants share {@link RuinedPortalStructure}, and only the Nether one belongs
 * in netherrack, so this checks {@link BandNetherStructures#isNetherRuinedPortal} first — a jungle or ocean
 * portal returns immediately and generates exactly as it did before, band or no band.</p>
 */
@Mixin(RuinedPortalStructure.class)
public abstract class RuinedPortalStructureMixin extends Structure {

    private RuinedPortalStructureMixin(StructureSettings settings) {
        super(settings);
    }

    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$siteInBand(GenerationContext context,
                                         CallbackInfoReturnable<Optional<GenerationStub>> cir) {
        RuinedPortalStructure self = (RuinedPortalStructure) (Object) this;
        if (!BandNetherStructures.isNetherRuinedPortal(self)) return;
        Optional<GenerationStub> banded = BandRuinedPortalSiting.site(
                context, ((RuinedPortalStructureAccessor) self).dungeontrain$setups(), this.biomes());
        if (banded.isPresent()) cir.setReturnValue(banded);
    }
}
