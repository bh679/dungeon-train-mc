package games.brennan.dungeontrain.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import games.brennan.dungeontrain.worldgen.DhHorizon;

/**
 * Distant Horizons' culling frustum with one extra test: a LOD section is drawn only if its whole X
 * span lies inside the stretch of track that may be seen ({@link DhHorizon.XWindow}). Only X — the
 * direction the train runs — is limited, so the view out to either side of the track is untouched,
 * and DH's render distance never changes, so its LODs are never rebuilt.
 *
 * <p>Bound above DH's core frustum, which it wraps rather than replaces: DH's own view-frustum test
 * still runs first ({@link #delegate()}), so off-screen sections are still culled as before.</p>
 */
final class DistantHorizonsTrackCulling implements IDhApiCullingFrustum {

    /** Above {@link IOverrideInjector#DEFAULT_NON_CORE_OVERRIDE_PRIORITY}, so another mod's frustum is wrapped too. */
    static final int PRIORITY = IOverrideInjector.DEFAULT_NON_CORE_OVERRIDE_PRIORITY + 10;

    private volatile DhHorizon.XWindow window = DhHorizon.XWindow.OPEN;
    private IDhApiCullingFrustum delegate;

    /** Set the stretch of X that may be drawn; {@link DhHorizon.XWindow#OPEN} draws everything. */
    void setWindow(DhHorizon.XWindow window) {
        this.window = window;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void update(int worldMinBlockY, int worldMaxBlockY, DhApiMat4f worldViewProjection) {
        IDhApiCullingFrustum d = delegate();
        if (d != null) d.update(worldMinBlockY, worldMaxBlockY, worldViewProjection);
    }

    @Override
    public boolean intersects(int lodBlockPosMinX, int lodBlockPosMinZ, int lodBlockWidth, int lodDetailLevel) {
        DhHorizon.XWindow w = window;
        if (!w.isOpen() && !w.contains(lodBlockPosMinX, (double) lodBlockPosMinX + lodBlockWidth)) return false;
        IDhApiCullingFrustum d = delegate();
        return d == null || d.intersects(lodBlockPosMinX, lodBlockPosMinZ, lodBlockWidth, lodDetailLevel);
    }

    /** The frustum this one wraps: another mod's at the default priority, else DH's own core frustum. */
    private IDhApiCullingFrustum delegate() {
        IDhApiCullingFrustum d = delegate;
        if (d == null) {
            d = DhApi.overrides.get(IDhApiCullingFrustum.class, IOverrideInjector.DEFAULT_NON_CORE_OVERRIDE_PRIORITY);
            if (d == null) d = DhApi.overrides.get(IDhApiCullingFrustum.class, IOverrideInjector.CORE_PRIORITY);
            if (d == this) d = null;
            delegate = d;
        }
        return d;
    }
}
