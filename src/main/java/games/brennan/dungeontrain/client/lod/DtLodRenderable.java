package games.brennan.dungeontrain.client.lod;

/**
 * Duck-type interface mixed into Sable's {@code ClientSubLevel} (by
 * {@link games.brennan.dungeontrain.mixin.client.ClientSubLevelLodMixin}) so DT can carry a
 * per-sub-level "render far" flag <em>on the instance itself</em> — O(1) to read, no external
 * map to key/clean, and it dies with the sub-level when Sable removes it.
 *
 * <p>Client-side mirror of {@link games.brennan.dungeontrain.ship.sable.DtFreezable}, which does
 * the same job for the server-side physics soft-freeze. Same rationale for the shape: the flag is
 * read on the hot render path (once per sub-level per RenderType per frame, plus once per
 * section-compile pass), so it must stay a plain field read rather than a map lookup.</p>
 *
 * <p><b>"Far"</b> means DT has demoted this carriage to the baked-mesh LOD tier: Sable's
 * per-sub-level chunk render is skipped for it ({@code compileSections} / {@code updateCulling} /
 * {@code renderSectionLayer} / {@code renderBlockEntities}) and
 * {@link CarriageMeshRenderer} draws a single baked mesh in its place. Nothing is unloaded and no
 * server state changes — the sub-level stays tracked, its blocks stay present, and promoting it
 * back to NEAR is a flag flip plus Sable's normal section rebuild.</p>
 *
 * <p>Read/written only on the client thread (the render tick and the {@link CarriageLodController}
 * reconcile both run there), so no synchronisation is needed.</p>
 */
public interface DtLodRenderable {

    /** True while DT has demoted this sub-level to the baked-mesh LOD tier. */
    boolean dt$isLodFar();

    /** Set by {@link CarriageLod#demote}/{@link CarriageLod#promote}. */
    void dt$setLodFar(boolean far);

    /** Consecutive client ticks this carriage has been continuously beyond the LOD threshold. */
    int dt$farTicks();

    /** Updated each reconcile tick by {@link CarriageLodController}. */
    void dt$setFarTicks(int ticks);
}
