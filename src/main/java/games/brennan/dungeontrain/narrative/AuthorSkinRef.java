package games.brennan.dungeontrain.narrative;

import java.util.Optional;

/**
 * A note author's skin as captured at their death and carried through the relay's
 * {@code authorSkinRef} field: the Mojang texture URL plus the arm model.
 *
 * <p>Needed because the echo's {@code SourceProfileSkin} ref has a URL segment but no slim flag, and
 * PlayerMob reads the arm model from the entity's synched flag when a URL is present. Format:
 * {@code slim;<url>} or {@code wide;<url>}. A Mojang texture URL never contains {@code ';'}.</p>
 *
 * <p>Pure (no Minecraft) so it is unit-tested; external data is never trusted — anything that does
 * not decode is treated as "no skin captured", which is the pre-fix behaviour.</p>
 */
public record AuthorSkinRef(String url, boolean slim) {

    private static final String SLIM = "slim";
    private static final String WIDE = "wide";
    private static final char SEP = ';';

    /** {@code slim;<url>} / {@code wide;<url>}. */
    public String encode() {
        return (slim ? SLIM : WIDE) + SEP + url;
    }

    /**
     * Decode a relay {@code authorSkinRef}; empty for null/blank (a note uploaded before skins were
     * captured), an unknown model word, or a blank URL.
     */
    public static Optional<AuthorSkinRef> decode(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        int sep = value.indexOf(SEP);
        if (sep < 0) return Optional.empty();
        String model = value.substring(0, sep);
        String url = value.substring(sep + 1);
        if (url.isBlank()) return Optional.empty();
        if (SLIM.equals(model)) return Optional.of(new AuthorSkinRef(url, true));
        if (WIDE.equals(model)) return Optional.of(new AuthorSkinRef(url, false));
        return Optional.empty();
    }
}
