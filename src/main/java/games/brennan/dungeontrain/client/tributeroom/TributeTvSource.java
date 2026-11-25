package games.brennan.dungeontrain.client.tributeroom;

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
 * What a TV tuned with the Tribute Cassette shows: the picture {@link TributeTvVisit} is rendering for this
 * player, on their own tribute TV; snow on any other TV, or when there is no visit.
 *
 * <p>Vista only asks once the TV passed the frustum cull, its screen is on, its face is turned to the
 * camera and it is within Vista's range, which is what tells a {@link ScreenToTexture} it is seen.</p>
 */
public final class TributeTvSource implements IVideoSource {

    public static final TributeTvSource INSTANCE = new TributeTvSource();

    private TributeTvSource() {}

    @Override
    public @NotNull VertexConsumer getVideoFrameBuilder(TVBlockEntity tv, float partialTick, MultiBufferSource buffer,
                                                        boolean shouldUpdate, Vec2i screenSize, Vec2i pixelEffectRes,
                                                        int videoAnimationTick, boolean paused,
                                                        IntAnimationState switchAnim, IntAnimationState staticAnim,
                                                        boolean showsTime) {
        // Asked for a picture means the TV is being drawn: stamp it seen, so it is drawn for the next
        // frame (TributeTvClientEvents#onFrameStart). Until it has one, snow.
        ScreenToTexture visit = TributeTvVisit.showsOn(tv.getBlockPos()) ? TributeTvVisit.texture() : null;
        if (visit != null) {
            visit.markSeen();
            if (!visit.hasPicture()) return TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);
            return TvScreenVertexConsumers.getSingleTextureVC(buffer, visit.location(), CrtOverlay.NONE,
                pixelEffectRes, switchAnim, staticAnim);
        }
        TributeTvShowcase.Entry shown = TributeTvShowcase.at(tv.getBlockPos());
        if (shown != null) {
            games.brennan.dungeontrain.client.live.LiveFeedSource live = TributeTvShowcase.liveFor(shown.ownerName());
            if (live != null) {
                return live.getVideoFrameBuilder(tv, partialTick, buffer, shouldUpdate, screenSize, pixelEffectRes,
                    videoAnimationTick, paused, switchAnim, staticAnim, showsTime);
            }
            ScreenToTexture texture = shown.binding().texture();
            texture.markSeen();
            if (!texture.hasPicture()) return TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);
            return TvScreenVertexConsumers.getSingleTextureVC(buffer, texture.location(), CrtOverlay.NONE,
                pixelEffectRes, switchAnim, staticAnim);
        }
        return TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);
    }

    @Override
    public ScreenFit getScreenFit() {
        return new ScreenFit(ScalingMode.CONTAIN, TributeTvVisit.pictureAspect());
    }
}
