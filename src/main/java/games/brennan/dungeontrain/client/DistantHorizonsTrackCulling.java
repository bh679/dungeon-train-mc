package games.brennan.dungeontrain.client;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiCullingFrustum;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.coreapi.interfaces.dependencyInjection.IOverrideInjector;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.worldgen.VoidWallPlane;

/**
 * Distant Horizons' culling frustum with one extra test: a LOD section reaching past this frame's void
 * wall ({@link ClientVoidWall}) is not drawn — straddling ones included, since a LOD section can be
 * hundreds of blocks wide and its far part would carry what lies past the void. Only X — the direction
 * the train runs — is cut, and DH's render distance never changes, so its LODs are never rebuilt; the
 * wall moves and DH simply stops or starts drawing the sections behind it.
 *
 * <p>The track's LODs go with everything else past the wall; the track there is drawn by
 * {@code DistantHorizonsVoidWall} instead.</p>
 *
 * <p>Bound above DH's core frustum, which it wraps rather than replaces: DH's own view-frustum test
 * still runs ({@link #delegate()}), so off-screen sections are still culled as before.</p>
 */
final class DistantHorizonsTrackCulling implements IDhApiCullingFrustum {

    /** Above {@link IOverrideInjector#DEFAULT_NON_CORE_OVERRIDE_PRIORITY}, so another mod's frustum is wrapped too. */
    static final int PRIORITY = IOverrideInjector.DEFAULT_NON_CORE_OVERRIDE_PRIORITY + 10;

    private IDhApiCullingFrustum delegate;

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
        VoidWallPlane wall = ClientVoidWall.plane();
        if (wall.hidesTerrain(lodBlockPosMinX, (double) lodBlockPosMinX + lodBlockWidth)
                && ClientDisplayConfig.isDistantHorizonsAdjustmentsEnabled()) {
            return false;
        }
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
