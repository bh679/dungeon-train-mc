package games.brennan.dungeontrain.mixin.betterend;

import games.brennan.dungeontrain.worldgen.PerThreadSdf;
import org.betterx.bclib.sdf.operator.SDFBinary;
import org.betterx.bclib.sdf.operator.SDFFlatWave;
import org.betterx.bclib.sdf.operator.SDFTranslate;
import org.betterx.bclib.sdf.primitive.SDFPrimitive;
import org.betterx.betterend.world.features.trees.MossyGlowshroomFeature;
import org.joml.Vector3f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The mossy glowshroom sets its cap, stem and root blocks, head offset and root angle on a {@code static} SDF
 * graph before every fill, and its noise reads a {@code static} centre. Every read of those statics during a
 * placement returns this thread's own copy ({@link PerThreadSdf}), so two glowshrooms growing at once can't
 * take each other's shape.
 */
@Mixin(value = MossyGlowshroomFeature.class, remap = false)
public abstract class BetterEndGlowshroomPerThreadMixin {

    @Shadow(remap = false) @Final private static Vector3f CENTER;
    @Shadow(remap = false) @Final private static SDFPrimitive CONE1;
    @Shadow(remap = false) @Final private static SDFPrimitive CONE2;
    @Shadow(remap = false) @Final private static SDFPrimitive CONE_GLOW;
    @Shadow(remap = false) @Final private static SDFPrimitive ROOTS;
    @Shadow(remap = false) @Final private static SDFTranslate HEAD_POS;
    @Shadow(remap = false) @Final private static SDFFlatWave ROOTS_ROT;
    @Shadow(remap = false) @Final private static SDFBinary FUNCTION;

    @Redirect(method = { "place", "lambda$static$2" }, at = @At(value = "FIELD",
        target = "CENTER:Lorg/joml/Vector3f;", opcode = Opcodes.GETSTATIC))
    private static Vector3f dungeontrain$center() {
        return PerThreadSdf.of(CENTER);
    }

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "CONE1:Lorg/betterx/bclib/sdf/primitive/SDFPrimitive;", opcode = Opcodes.GETSTATIC))
    private static SDFPrimitive dungeontrain$cone1() {
        return PerThreadSdf.of(CONE1);
    }

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "CONE2:Lorg/betterx/bclib/sdf/primitive/SDFPrimitive;", opcode = Opcodes.GETSTATIC))
    private static SDFPrimitive dungeontrain$cone2() {
        return PerThreadSdf.of(CONE2);
    }

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "CONE_GLOW:Lorg/betterx/bclib/sdf/primitive/SDFPrimitive;", opcode = Opcodes.GETSTATIC))
    private static SDFPrimitive dungeontrain$coneGlow() {
        return PerThreadSdf.of(CONE_GLOW);
    }

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "ROOTS:Lorg/betterx/bclib/sdf/primitive/SDFPrimitive;", opcode = Opcodes.GETSTATIC))
    private static SDFPrimitive dungeontrain$roots() {
        return PerThreadSdf.of(ROOTS);
    }

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "HEAD_POS:Lorg/betterx/bclib/sdf/operator/SDFTranslate;", opcode = Opcodes.GETSTATIC))
    private static SDFTranslate dungeontrain$headPos() {
        return PerThreadSdf.of(HEAD_POS);
    }

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "ROOTS_ROT:Lorg/betterx/bclib/sdf/operator/SDFFlatWave;", opcode = Opcodes.GETSTATIC))
    private static SDFFlatWave dungeontrain$rootsRot() {
        return PerThreadSdf.of(ROOTS_ROT);
    }

    @Redirect(method = "place", at = @At(value = "FIELD",
        target = "FUNCTION:Lorg/betterx/bclib/sdf/operator/SDFBinary;", opcode = Opcodes.GETSTATIC))
    private static SDFBinary dungeontrain$function() {
        return PerThreadSdf.of(FUNCTION);
    }
}
