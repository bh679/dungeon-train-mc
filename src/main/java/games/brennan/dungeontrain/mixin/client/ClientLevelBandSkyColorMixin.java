package games.brennan.dungeontrain.mixin.client;

import games.brennan.dungeontrain.client.ClientNetherBand;
import games.brennan.dungeontrain.client.ClientVoidBand;
import games.brennan.dungeontrain.client.NetherFogEvents;
import games.brennan.dungeontrain.client.ShaderCompat;
import games.brennan.dungeontrain.client.shader.ShaderBisect;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tints the level's <em>sky colour</em> through the Nether and End bands, the way
 * {@code NetherFogEvents} / {@code VoidSkyEvents} already tint the fog colour.
 *
 * <p>Without a pack the sky colour is only vanilla's sky disc, which Dungeon Train's band domes
 * cover — but a shader pack reads it as the {@code skyColor} uniform, and the lighter packs paint
 * their whole sky from it. Sildur's Enhanced Default, told it was rendering the Nether, filled the
 * Nether band with the overworld biome's blue for exactly that reason. In the real Nether the
 * uniform carries a Nether biome's colour, so this is what the real dimension would hand the pack:
 * the band's fog colour for the Nether (a biome's own where one is authored), black for the End
 * (vanilla's End biome sky colour is 0).</p>
 *
 * <p>Overworld only; a pure lerp by the band ramps, so it fades with everything else.</p>
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelBandSkyColorMixin {

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true)
    private void dungeontrain$bandSkyColor(Vec3 pos, float partialTick, CallbackInfoReturnable<Vec3> cir) {
        // Shader packs only. Iris reads this for its skyColor uniform, which is the whole reason the
        // hook exists; vanilla reads it for the sky disc and the fog, both of which a band already
        // handles by other means. Tinting it with no pack loaded would change the vanilla look for
        // players who will never see the benefit.
        if (!ShaderCompat.active() || !ShaderBisect.skyColourEnabled()) return;
        ClientLevel level = (ClientLevel) (Object) this;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        double nether = ClientNetherBand.netherIntensityAt(pos.x);
        double end = nether > 0.0 ? 0.0 : ClientVoidBand.endSkyIntensityAt(pos.x);
        if (nether <= 0.0 && end <= 0.0) return;

        Vec3 sky = cir.getReturnValue();
        Vec3 target;
        double t;
        if (nether > 0.0) {
            int rgb = NetherFogEvents.smoothedNetherColor(level, BlockPos.containing(pos), false);
            target = new Vec3(((rgb >> 16) & 0xFF) / 255.0, ((rgb >> 8) & 0xFF) / 255.0, (rgb & 0xFF) / 255.0);
            t = Math.min(1.0, nether);
        } else {
            target = Vec3.ZERO;
            t = Math.min(1.0, end);
        }
        cir.setReturnValue(sky.lerp(target, t));
    }
}
