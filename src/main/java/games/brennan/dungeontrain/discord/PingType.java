package games.brennan.dungeontrain.discord;

import java.util.Optional;

/**
 * The kinds of Discord post a player who linked their Discord ({@code /discord}) can be @-pinged on,
 * each one a toggle in Options → Dungeon Train → Account. The relay keeps the choices (dp-relay
 * {@code community.PING_TYPES}); {@link #wireId} is the name both sides use for a kind.
 */
public enum PingType {
    /** Their death report — the public manifest. */
    DEATH("death"),
    /** They paid Tribute to their own photo. */
    OWN_TRIBUTE("own_tribute"),
    /** Someone paid Tribute to a photo they took. */
    PHOTO_TRIBUTED("photo_tributed"),
    /** A milestone advancement of theirs, with its screenshot. */
    MILESTONE("milestone"),
    /** The review thread opened when a translation they submitted needs a look. */
    TRANSLATION_REVIEW("translation_review");

    private final String wireId;

    PingType(String wireId) {
        this.wireId = wireId;
    }

    public String wireId() {
        return wireId;
    }

    /** Translation-key suffix for this kind's toggle in the Account tab. */
    public String key() {
        return wireId;
    }

    public static Optional<PingType> byWireId(String id) {
        for (PingType t : values()) {
            if (t.wireId.equals(id)) return Optional.of(t);
        }
        return Optional.empty();
    }
}
