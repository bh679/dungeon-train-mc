package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;

/**
 * The quiet line that names whose library a player has just walked into.
 *
 * <p>Shown once on entering a portal room whose Books setting locks it to an author (see
 * {@code PortalLibraryGreeter}), styled {@link ChatFormatting#GRAY} to match {@link SharedBookMessage}
 * and {@link FamiliarBookMessage} — the same "somebody else's words are in here" voice the whole
 * community-book feature speaks in.</p>
 *
 * <p>Without it the pattern is discoverable only by reading two books and noticing the same name on
 * both covers, which is a lot to ask of a room the player may take one book from.</p>
 *
 * <p><b>Localization.</b> Every variant is a {@link Component#translatable} key under
 * {@code chat.dungeontrain.portal_library.*}: the server picks the variant, the client renders it in
 * its own language. The author's signature is substituted as a literal — it is a name somebody typed,
 * not a phrase to translate.</p>
 */
public final class PortalLibraryMessage {

    /** Common prefix for every portal-library lang key. */
    private static final String KEY = "chat.dungeontrain.portal_library.";

    /** How many {@code KEY}{@code <n>} variants exist, 1-based. */
    private static final int VARIANTS = 5;

    /** Key for the line shown when the room is serving the reader their OWN writing. */
    private static final String SELF_KEY = KEY + "self";

    private PortalLibraryMessage() {}

    /**
     * A line naming {@code author}, or the "your own shelves" line when {@code mine} is true.
     *
     * <p>Self gets its own single variant rather than a rotation: "everything here is yours" only
     * needs saying one way, and phrasing it as a discovery about a stranger would read oddly to the
     * one player who already knows every book in the room.</p>
     */
    public static MutableComponent random(RandomSource random, String author, boolean mine) {
        if (mine) return Component.translatable(SELF_KEY).withStyle(ChatFormatting.GRAY);
        String name = author == null || author.isBlank() ? "someone" : author;
        int variant = 1 + random.nextInt(VARIANTS);
        return Component.translatable(KEY + variant, Component.literal(name))
            .withStyle(ChatFormatting.GRAY);
    }
}
