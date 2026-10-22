package games.brennan.dungeontrain.config;

/**
 * How solid the block-variant editor's empty-cell placeholder
 * ({@link games.brennan.dungeontrain.block.VariantPlaceholderBlock}) is to walk into.
 *
 * <p>Collision only: in every mode the ghost cube still draws and keeps its full outline, so it can
 * be seen, targeted and built against. Stored in {@link ClientDisplayConfig} and set from the X
 * menu's Settings tab — a client choice on the same terms as the Skybox switch, so in single player
 * it is the real collision and on a dedicated server the server keeps {@link #DEFAULT}.</p>
 */
public enum PlaceholderCollision {

    /** Solid only to something standing on top of it and not sneaking; walk through from the side. */
    HALF_SOLID,

    /** A full block from every side, like any other. */
    SOLID,

    /** No collision at all: walk and fall straight through. */
    AIR;

    /** What ships — stand on the markers, but never get walled in by them. */
    public static final PlaceholderCollision DEFAULT = HALF_SOLID;
}
