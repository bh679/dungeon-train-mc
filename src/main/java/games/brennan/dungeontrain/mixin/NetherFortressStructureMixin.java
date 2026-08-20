package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.structure.BandFortressSiting;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Lets {@code minecraft:fortress} generate in the Nether band's core, so the band's fortresses <em>are</em>
 * the vanilla structure: {@code /locate structure minecraft:fortress} finds them, their own
 * {@code spawn_overrides} apply, and NeoForge's fortress-specific spawn path (which is hardcoded to this
 * structure's id) recognises them.
 *
 * <p>Only the siting differs, and only inside the band. Vanilla drops a fortress at a flat Nether-space
 * Y 64, which in the overworld is ordinary terrain;
 * {@link BandFortressSiting#site} translates that onto track level. Everywhere else — the real Nether above
 * all — this returns without touching anything and vanilla's own siting runs unchanged.</p>
 */
@Mixin(NetherFortressStructure.class)
public abstract class NetherFortressStructureMixin extends Structure {

    private NetherFortressStructureMixin(StructureSettings settings) {
        super(settings);
    }

    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$siteInBand(GenerationContext context,
                                         CallbackInfoReturnable<Optional<GenerationStub>> cir) {
        Optional<GenerationStub> banded = BandFortressSiting.site(context, this.biomes());
        if (banded.isPresent()) cir.setReturnValue(banded);
    }
}
