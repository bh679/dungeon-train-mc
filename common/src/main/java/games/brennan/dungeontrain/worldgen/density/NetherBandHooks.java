package games.brennan.dungeontrain.worldgen.density;

/**
 * Tiny coordination point between the two worldgen mixins that install the
 * {@link NetherBandTerrainDensityFunction} into the overworld noise router.
 *
 * <p>The nether-transition band is <b>overworld-only</b>, but {@code RandomState} (where the
 * router is built) has no notion of which dimension it belongs to. {@code ChunkMap}'s
 * constructor — which DOES know its {@code ServerLevel} — sets {@link #CONSTRUCTING_OVERWORLD}
 * just around its {@code RandomState.create(...)} call (see {@code ChunkMapMixin}), and the
 * {@code RandomState} constructor mixin reads it to decide whether to wrap the router
 * ({@code RandomStateMixin}). Both run synchronously on the same server thread during level
 * setup, so a {@link ThreadLocal} scopes the flag precisely to one overworld router build.</p>
 */
public final class NetherBandHooks {

    private NetherBandHooks() {}

    /**
     * True only while the OVERWORLD dimension's {@code RandomState} (and thus its noise router)
     * is under construction. Defaults false; set+cleared around the {@code RandomState.create}
     * call in {@code ChunkMap.<init>}. Non-overworld dimensions (Nether/End/other mods') never
     * see it true, so their routers are left byte-identical to vanilla.
     */
    public static final ThreadLocal<Boolean> CONSTRUCTING_OVERWORLD =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
}
