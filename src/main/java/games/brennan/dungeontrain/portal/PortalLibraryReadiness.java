package games.brennan.dungeontrain.portal;

/**
 * Whether a portal room could be filled if it were drawn right now.
 *
 * <h2>Why selection asks this at all</h2>
 * <p>A room that stocks its shelves from one author needs the relay to have said who has written
 * what. That answer arrives seconds into a world at the earliest, and may never arrive at all — and a
 * library room drawn before it lands is a hall of bare shelves with an undressed lectern, which reads
 * as a broken feature rather than as a room that is still loading.</p>
 *
 * <p>So the readiness question is asked at <i>pick</i> time instead: while a room cannot be filled it
 * is simply not one of the rooms a portal can draw, and the pool offers something else. The player
 * never meets the half-built version.</p>
 *
 * <p>Consulted through the eligibility predicate on
 * {@code TrackVariantRegistry.pickName}, which applies it to the top-level pool AND to a group's
 * members — the Books setting lives on the member, so {@code library_dimension} has to be filtered at
 * the member level or the group would resolve to a room that cannot fill.</p>
 */
public final class PortalLibraryReadiness {

    private PortalLibraryReadiness() {}

    /**
     * True when {@code roomName} may be drawn.
     *
     * <p>Cheap on the common path: a room that does not stock an author — almost all of them — costs
     * one settings read and one enum comparison, and never touches the directory.</p>
     */
    public static boolean canStamp(String roomName) {
        if (roomName == null || roomName.isBlank()) return true;
        PortalRoomBooks books = PortalRoomSettings.of(roomName).books();
        if (!books.locks()) return true;
        return PortalRoomAuthorLocks.canFill(books);
    }
}
