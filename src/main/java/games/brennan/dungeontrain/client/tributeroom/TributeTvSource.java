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
        if (TributeTvVisit.showsOn(tv.getBlockPos()) && TributeTvVisit.textureLocation() != null) {
            return TvScreenVertexConsumers.getSingleTextureVC(buffer, TributeTvVisit.textureLocation(), CrtOverlay.NONE,
                pixelEffectRes, switchAnim, staticAnim);
        }
        TributeTvShowcase.Entry shown = TributeTvShowcase.at(tv.getBlockPos());
        if (shown != null) {
            games.brennan.dungeontrain.client.live.LiveFeedSource live = TributeTvShowcase.liveFor(shown.ownerName());
            if (live != null) {
                return live.getVideoFrameBuilder(tv, partialTick, buffer, shouldUpdate, screenSize, pixelEffectRes,
                    videoAnimationTick, paused, switchAnim, staticAnim, showsTime);
            }
            return TvScreenVertexConsumers.getSingleTextureVC(buffer, shown.binding().texture().location(), CrtOverlay.NONE,
                pixelEffectRes, switchAnim, staticAnim);
        }
        return TvScreenVertexConsumers.getNoiseVC(buffer, pixelEffectRes, switchAnim);
    }

    @Override
    public ScreenFit getScreenFit() {
        return new ScreenFit(ScalingMode.CONTAIN, TributeTvVisit.pictureAspect());
    }
}
