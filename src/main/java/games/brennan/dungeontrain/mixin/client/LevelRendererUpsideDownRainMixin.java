package games.brennan.dungeontrain.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import games.brennan.dungeontrain.client.ClientUpsideDownBand;
import games.brennan.dungeontrain.client.UpsideDownRainRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Make rain and snow fall <b>upward</b> in the upside-down band.
 *
 * <p>The band renders the world mirrored around {@link ClientUpsideDownBand#plane()}, and weather was
 * the one piece of atmosphere still oriented the old way — in practice not even that, since vanilla's
 * "only draw above the {@code MOTION_BLOCKING} heightmap" clamp collapses every in-band column against
 * the mirrored ceiling and draws nothing. Band columns are handed to
 * {@link UpsideDownRainRenderer}, which re-emits vanilla's sheets with the scroll inverted and that
 * clamp dropped; everywhere else {@code original} runs untouched.</p>
 *
 * <p>Only the falling sheets are redirected. The splash particles and rain ambience that
 * {@code tickRain} scatters are left on vanilla, which in-band places them off the
 * {@code MOTION_BLOCKING} heightmap — the bedrock lid far above the train — and so effectively spawns
 * none. Putting them on the underside of the hanging terrain instead needs a per-attempt upward block
 * scan (~1.1k lookups per client tick at full rain), which is not worth paying yet; see the
 * {@code tickRain} wrapper removed in this commit if it is wanted later.</p>
 *
 * <p>Gated on {@link ClientUpsideDownBand#isInBand} — the core band <em>and</em> its entry lead-in, the
 * same predicate that drives the block-render flip in {@code ModelBlockRendererUpsideDownMixin}. The
 * exit crossfade is deliberately left on vanilla downward rain: the overworld is returning there and
 * its terrain is upright again. No overlap with {@code LevelRendererVoidSkyMixin}, which cancels the
 * sheets entirely over the Nether and End cores — those bands never share world-X with this one.</p>
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererUpsideDownRainMixin {

    @Shadow @Final private float[] rainSizeX;
    @Shadow @Final private float[] rainSizeZ;
    @Shadow private int ticks;

    @WrapMethod(method = "renderSnowAndRain(Lnet/minecraft/client/renderer/LightTexture;FDDD)V")
    private void dungeontrain$upwardWeatherInUpsideDownBand(LightTexture lightTexture, float partialTick,
                                                            double camX, double camY, double camZ,
                                                            Operation<Void> original) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null
                || !level.dimension().equals(Level.OVERWORLD)
                || !ClientUpsideDownBand.isInBand(Mth.floor(camX))) {
            original.call(lightTexture, partialTick, camX, camY, camZ);
            return;
        }
        UpsideDownRainRenderer.render(level, lightTexture, partialTick, camX, camY, camZ,
                this.rainSizeX, this.rainSizeZ, this.ticks);
    }
}
