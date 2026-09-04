package games.brennan.dungeontrain.mixin.client.iris;

import games.brennan.dungeontrain.client.shader.ShaderWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets Dungeon Train tell Iris which of a shader pack's worlds to render.
 *
 * <p>Iris chooses a pack's program set — {@code world0}, {@code world-1}, {@code world1} — from
 * {@code Iris.getCurrentDimension()} on every frame ({@code MixinLevelRenderer} →
 * {@code PipelineManager.preparePipeline}), and caches one pipeline per id. Dungeon Train's Nether
 * and End are <em>bands of the overworld</em>, so left alone every pack renders them with its
 * overworld sky, fog and lighting. Answering "the Nether" here while the camera is in a Nether band
 * (or a Nether-skied dimensional carriage) makes the pack itself render its Nether — the only way a
 * band can be indistinguishable from the real thing under shaders.</p>
 *
 * <p>The return value must be Iris' own canonical {@code DimensionId.NETHER}/{@code END} objects:
 * {@code WorldTimeUniforms} and {@code MixinMinecraft_PipelineManagement} compare by reference.
 * {@link ShaderWorld} fetches them reflectively so Iris stays off the compile classpath; this class
 * names only {@code Object}s for the same reason.</p>
 *
 * <p>Applied only when Iris is installed (see {@code IrisMixinPlugin}); {@code remap = false} because
 * the target is a mod class with no obfuscation.</p>
 */
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class IrisCurrentDimensionMixin {

    @Inject(method = "getCurrentDimension", at = @At("RETURN"), cancellable = true)
    private static void dungeontrain$reportShaderWorld(CallbackInfoReturnable<Object> cir) {
        Object override = ShaderWorld.irisOverride();
        if (override != null) cir.setReturnValue(override);
    }
}
