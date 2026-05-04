package games.brennan.dungeontrain.template;

/**
 * Per-call runtime inputs for {@link Template#placeAt}. Bundles the variable
 * arguments that vary by template kind into a single immutable record so the
 * dispatch method on {@link Template} can stay uniform across all permittees.
 *
 * <ul>
 *   <li>{@code seed} — world seed used by part placements to deterministically
 *       pick variant blocks. Carriages / contents / tunnels ignore.</li>
 *   <li>{@code carriageIndex} — the carriage's index along the train, used
 *       alongside {@code seed} for per-placement randomness in part stamps.
 *       Carriages / contents / tunnels ignore.</li>
 *   <li>{@code mirrorX} — for tunnel portals, whether to render the mirrored
 *       (exit) orientation. Other kinds ignore.</li>
 * </ul>
 *
 * <p>Use {@link #EMPTY} as the no-context default for kinds whose placement
 * doesn't depend on any of these fields. Phase-4 introduces the bundle so
 * existing static callers ({@code TrainAssembler}, {@code TrackGenerator})
 * can migrate to {@link Template#placeAt} without losing per-kind args.</p>
 */
public record PlaceContext(long seed, int carriageIndex, boolean mirrorX) {

    /** No-context default — used by kinds whose placement ignores all three fields. */
    public static final PlaceContext EMPTY = new PlaceContext(0L, 0, false);

    /** Convenience for callers that only need a seed + index (parts). */
    public static PlaceContext forParts(long seed, int carriageIndex) {
        return new PlaceContext(seed, carriageIndex, false);
    }

    /** Convenience for tunnel portal placement — only the mirror flag matters. */
    public static PlaceContext forPortal(boolean mirrorX) {
        return new PlaceContext(0L, 0, mirrorX);
    }
}
