package games.brennan.dungeontrain.client.live;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.mehvahdjukaar.vista.client.textures.web.IWebTexture;
import net.mehvahdjukaar.vista.client.web.IMediaSession;
import net.mehvahdjukaar.vista.client.web.MediaStatus;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The GPU texture a Live Feed session draws into: a {@link DynamicTexture} of the decoded size
 * whose pixels are refreshed from the session's latest frame on the render thread.
 */
public final class LiveFeedTexture extends DynamicTexture implements IWebTexture {

    private final ResourceLocation location;
    private final LiveHlsSession session;
    private long shownFrame = -1;
    private boolean everUploaded;

    public LiveFeedTexture(ResourceLocation location, LiveHlsSession session, int width, int height) {
        super(width, height, false);
        this.location = location;
        this.session = session;
    }

    @Override
    public ResourceLocation getTextureLocation() {
        return location;
    }

    @Override
    public IMediaSession getSession() {
        return session;
    }

    public boolean hasPicture() {
        return everUploaded;
    }

    /** Render thread: copy the newest decoded frame in, if there is one we have not shown. */
    @Override
    public MediaStatus uploadFrameAtTime(int ticks, float deltaTime, boolean paused) {
        long seq = session.frameSequence();
        if (seq != shownFrame) {
            NativeImage mine = getPixels();
            if (mine != null && session.copyLatestInto(mine)) {
                shownFrame = seq;
                if (RenderSystem.isOnRenderThread()) upload(); else RenderSystem.recordRenderCall(this::upload);
                everUploaded = true;
            }
        }
        return session.status();
    }

    @Override
    public void close() {
        this.releaseId();
        NativeImage px = getPixels();
        if (px != null) px.close();
    }
}
