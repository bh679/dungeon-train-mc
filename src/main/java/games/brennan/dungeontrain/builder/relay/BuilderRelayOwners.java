package games.brennan.dungeontrain.builder.relay;

/**
 * Whose relay row a request is about, when the client did not say.
 *
 * <p>The editor's own roster sends no owner: a tile is one of this world's templates, and the world
 * holds the relay record for it. Usually that record is the player's own row. On a dev build it can
 * be somebody else's — a build loaded as-is from their profile and linked so that saves go back into
 * their history — and asking the relay for it under the dev's own uuid would be refused. So a blank
 * request resolves through the record first, and only a release build (which never links a foreign
 * row) or an unrecorded id falls through to the player.</p>
 */
final class BuilderRelayOwners {

    private BuilderRelayOwners() {}

    /**
     * @param own           this player's uuid
     * @param requested     the owner the client named, or blank/null for "whoever's it is"
     * @param devBuild      whether foreign rows can be linked at all ({@code DungeonTrain.isDevBuild()})
     * @param recordedOwner the owner the world's relay record names for the row, blank when it is
     *                      the player's own or the row is unrecorded
     */
    static String resolve(String own, String requested, boolean devBuild, String recordedOwner) {
        if (requested != null && !requested.isBlank()) return requested.trim();
        if (devBuild && recordedOwner != null && !recordedOwner.isBlank()) return recordedOwner.trim();
        return own;
    }
}
