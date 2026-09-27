package games.brennan.dungeontrain.mixin;

import com.mojang.datafixers.util.Either;
import games.brennan.dungeontrain.worldgen.LostCityGroundProcessor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.LiquidSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Attaches {@link LostCityGroundProcessor} to every pool element that places a Big Lost City template.
 *
 * <p>The mod's template pools name their processors in data, and DT's trackside copies point at those pools,
 * so the only seam that reaches both without overriding the mod's files is the place settings the element
 * builds for each piece. Templates of any other namespace are untouched.</p>
 */
@Mixin(SinglePoolElement.class)
public abstract class SinglePoolElementMixin {

    @Shadow @Final protected Either<ResourceLocation, StructureTemplate> template;

    @Inject(method = "getSettings", at = @At("RETURN"))
    private void dungeontrain$lostCityGround(Rotation rotation, BoundingBox box, LiquidSettings liquid,
                                              boolean keepJigsaws, CallbackInfoReturnable<StructurePlaceSettings> cir) {
        ResourceLocation id = template.left().orElse(null);
        if (LostCityGroundProcessor.appliesTo(id)) {
            cir.getReturnValue().addProcessor(LostCityGroundProcessor.INSTANCE);
        }
    }
}
