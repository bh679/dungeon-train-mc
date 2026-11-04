package games.brennan.dungeontrain.client.homeroom;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.mehvahdjukaar.moonlight.api.util.math.Vec2i;
import net.mehvahdjukaar.vista.client.CrtOverlay;
import net.mehvahdjukaar.vista.client.textures.ScalingMode;
import net.mehvahdjukaar.vista.client.textures.ScreenFit;
import net.mehvahdjukaar.vista.client.textures.TvScreenVertexConsumers;
import net.mehvahdjukaar.vista.client.video_source.IVideoSource;
import net.mehvahdjukaar.vista.common.tv.IntAnimationState;
import net.mehvahdjukaar.vista.common.tv.TVBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import org.jetbrains.annotations.NotNull;

/**
 * What a TV tuned with the Home Cassette shows: the picture {@link HomeTvVisit} is rendering for this
 * player, on their own home TV; snow on any other TV, or when there is no visit.
 */
public final class HomeTvSource implements IVideoSource {

    public static final HomeTvSource INSTANCE = new HomeTvSource();

    private HomeTvSource() {}

    @Override
    public @NotNull VertexConsumer getVideoFrameBuilder(TVBlockEntity tv, float partialTick, MultiBufferSource buffer,
                                                        boolean shouldUpdate, Vec2i screenSize, Vec2i pixelEffectRes,
                                                        int videoAnimationTick, boolean paused,
                                                        IntAnimationState switchAnim, IntAnimationState staticAnim,
                                                        boolean showsTime) {
        if (!HomeTvVisit.showsOn(tv.getBlockPos())) {
            return TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);
        }
        return TvScreenVertexConsumers.getSingleTextureVC(buffer, ScreenToTexture.LOCATION, CrtOverlay.NONE,
            pixelEffectRes, switchAnim, staticAnim);
    }

    @Override
    public ScreenFit getScreenFit() {
        return new ScreenFit(ScalingMode.CONTAIN, HomeTvVisit.pictureAspect());
    }
}
