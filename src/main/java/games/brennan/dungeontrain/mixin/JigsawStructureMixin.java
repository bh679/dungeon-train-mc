package games.brennan.dungeontrain.mixin;

import games.brennan.dungeontrain.worldgen.structure.BandBastionSiting;
import games.brennan.dungeontrain.worldgen.structure.BandNetherStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Lets {@code minecraft:bastion_remnant} generate in the Nether band's core.
 *
 * <p>{@link JigsawStructure} is shared with villages, pillager outposts, trial chambers and every other
 * jigsaw structure, so this checks {@link BandNetherStructures#isBastion} — vanilla's own
 * {@code bastion/starts} pool — before doing anything at all. Anything else, and any position outside the
 * band core, returns immediately and generates exactly as it did before.</p>
 */
@Mixin(JigsawStructure.class)
public abstract class JigsawStructureMixin extends Structure {

    private JigsawStructureMixin(StructureSettings settings) {
        super(settings);
    }

    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void dungeontrain$siteBastionInBand(GenerationContext context,
                                                CallbackInfoReturnable<Optional<GenerationStub>> cir) {
        JigsawStructure self = (JigsawStructure) (Object) this;
        if (games.brennan.dungeontrain.worldgen.structure.BandOthersideStructures.isTemple(self)) {
            // Deeper Darker's Ancient Temple in a Deeper Dark core, sited onto the Otherside slab. Anywhere else —
            // the real Otherside included — vanilla's own siting runs untouched.
            Optional<GenerationStub> temple = games.brennan.dungeontrain.worldgen.structure.BandOthersideStructures.site(
                    self, context, this.biomes());
            if (temple.isPresent()) cir.setReturnValue(temple);
            return;
        }
        if (!BandNetherStructures.isBastion(self)) return;
        Optional<GenerationStub> banded = BandBastionSiting.site(self, context, this.biomes());
        if (banded.isPresent()) cir.setReturnValue(banded);
    }
}
