package games.brennan.dungeontrain.mixin.client;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import games.brennan.dungeontrain.client.lod.CarriageLod;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips <em>section compilation</em> for carriages at the far LOD tier.
 *
 * <p>{@link SubLevelRenderDispatcherLodMixin} gates the draw side, but Sable's compile pass is
 * driven from its own {@code LevelRenderer} mixin — it walks every sub-level and calls
 * {@code getRenderData().compileSections(...)} directly, never through the dispatcher — so the draw
 * gate alone would leave far carriages still paying to re-mesh dirty sections. That is the more
 * expensive half: meshing is CPU work on the render thread, where a draw call is not.</p>
 *
 * <p>Deliberately <b>does not</b> free the render data. Sable keeps calling {@code setDirty} on
 * every block change, {@code resize} when the plot grows and {@code isSectionCompiled} from the
 * vanilla renderer, none of which DT gates; handing those a closed object would be a crash surface.
 * Leaving it open is also what makes promotion self-correcting — sections that went dirty while the
 * carriage was far are still marked dirty, so promoting simply resumes compiling and they rebuild
 * with no cache to invalidate.</p>
 *
 * <p>Bytecode-verified against {@code sable-2.0.2+mc1.21.1}. <b>Re-enumerate on any
 * {@code sable_version} bump.</b> {@code remap = false}: Sable's own names.</p>
 */
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public abstract class SubLevelRenderDataLodMixin {

    @Shadow public abstract ClientSubLevel getSubLevel();

    @Inject(method = "compileSections", at = @At("HEAD"), cancellable = true)
    private void dungeonTrain$skipFarCompile(CallbackInfo ci) {
        if (CarriageLod.isFar(getSubLevel())) ci.cancel();
    }
}
