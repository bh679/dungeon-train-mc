package games.brennan.dungeontrain.mixin.betterend;

import games.brennan.dungeontrain.worldgen.PerThreadSdf;
import org.betterx.bclib.sdf.SDF;
import org.betterx.bclib.sdf.operator.SDFCoordModify;
import org.betterx.bclib.sdf.primitive.SDFSphere;
import org.betterx.betterend.world.features.terrain.OreLayerFeature;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * BetterEnd's rock layers (violecite, flavolite) set their radius, block and noise on a {@code static} SDF
 * before every fill. Every read of those statics during a placement returns this thread's own copy
 * ({@link PerThreadSdf}), so two layers placed at once can't take each other's size or rock.
 */
@Mixin(value = OreLayerFeature.class, remap = false)
public abstract class BetterEndOreLayerPerThreadMixin {

    @Shadow(remap = false) @Final private static SDFSphere SPHERE;
    @Shadow(remap = false) @Final private static SDFCoordModify NOISE;
    @Shadow(remap = false) @Final private static SDF FUNCTION;

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "SPHERE:Lorg/betterx/bclib/sdf/primitive/SDFSphere;", opcode = Opcodes.GETSTATIC))
    private static SDFSphere dungeontrain$sphere() {
        return PerThreadSdf.of(SPHERE);
    }

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "NOISE:Lorg/betterx/bclib/sdf/operator/SDFCoordModify;", opcode = Opcodes.GETSTATIC))
    private static SDFCoordModify dungeontrain$noise() {
        return PerThreadSdf.of(NOISE);
    }

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "FUNCTION:Lorg/betterx/bclib/sdf/SDF;", opcode = Opcodes.GETSTATIC))
    private static SDF dungeontrain$function() {
        return PerThreadSdf.of(FUNCTION);
    }
}
