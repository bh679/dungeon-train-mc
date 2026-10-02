package games.brennan.dungeontrain.event;

/**
 * What {@link WorldEndBandEvents} owes a band chunk when it loads — pure, so the rule is unit-testable.
 *
 * <ul>
 *   <li>A chunk still flagged pending is owed its terrain whichever way the band is generated: an old
 *       save from before terrain was generated in worldgen, or a worldgen-time write that failed and
 *       flagged the chunk for the background path.</li>
 *   <li>A brand-new chunk is flagged and requested only in background mode; in worldgen mode its terrain
 *       was written during generation and there is nothing to do.</li>
 * </ul>
 */
final class EndBandLoadDecision {

    enum Action {
        /** New chunk in background mode: flag it pending and request its sample. */
        FLAG_AND_REQUEST,
        /** Already pending: request (or write the stashed sample). */
        REQUEST,
        /** Terrain is in (or never owed): nothing to do. */
        NONE
    }

    private EndBandLoadDecision() {}

    static Action decide(boolean newChunk, boolean pending, boolean terrainInWorldgen) {
        if (pending) return Action.REQUEST;
        if (newChunk && !terrainInWorldgen) return Action.FLAG_AND_REQUEST;
        return Action.NONE;
    }
}
