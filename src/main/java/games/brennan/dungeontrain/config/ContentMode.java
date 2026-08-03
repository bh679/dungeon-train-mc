package games.brennan.dungeontrain.config;

/**
 * Which tier of other-players' content this client accepts — chosen once on the title-screen consent
 * card and changeable in Options → Dungeon Train…
 *
 * <p>Dungeon Train serves a lot of content written by strangers: community books as chest loot,
 * player-written narrative series on lecterns, Death Note curses, carriages built in other people's
 * worlds — and the developer can message the player directly. All of it is moderated, but moderation
 * asks "is this abusive?", not "is this fine for a nine-year-old", and a chat channel with an adult
 * stranger is a live conversation however well it is screened. {@link #KID} is the answer for a parent
 * who wants the game without those parts.</p>
 *
 * <h3>Exactly what KID changes</h3>
 * <ul>
 *   <li><b>No developer chat</b>, in either direction — no title-screen chat window, no inbound
 *       {@code [Developer]} lines, no {@code @dev} relay out.</li>
 *   <li><b>Community books</b> are restricted to those the relay has explicitly flagged kid-safe (a
 *       stricter bar than "approved"). This one FILTERS rather than switching off, because book
 *       selection is already resolved per-player at pickup.</li>
 *   <li><b>Lectern narratives, Death Note curses and shared carriages are off.</b> There is no
 *       kid-safe curation for those, so there is no honest subset to serve.</li>
 * </ul>
 *
 * <p><b>Publishing stays open.</b> A child can still sign a book and share a carriage; what they write
 * is additionally screened relay-side for contact and personal information, which is the risk that
 * actually applies to a child publishing to strangers.</p>
 *
 * <p>{@link #ADULT} is the default and is exactly today's behaviour, so no existing install changes on
 * upgrade. There is deliberately no lock on switching back: this is a setting for a parent to use, not
 * a control the game tries to enforce against its own player.</p>
 */
public enum ContentMode {
    /** Everything, as before. The default — an install that never answers the card stays here. */
    ADULT,
    /** Dev chat off; community books kid-safe-only; other player-authored content off. */
    KID;

    /** True for {@link #KID} — reads better than {@code == KID} at the call sites that gate content. */
    public boolean isKid() {
        return this == KID;
    }

    /**
     * Parse a stored/synced name, falling back to {@code fallback} for anything unrecognised. Used by
     * the config read and the network decode so a hand-edited TOML or a packet from a client running a
     * different version can never throw — and callers pick which way an unknown value should fail.
     */
    public static ContentMode parse(String name, ContentMode fallback) {
        if (name == null) return fallback;
        for (ContentMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name.trim())) return mode;
        }
        return fallback;
    }
}
