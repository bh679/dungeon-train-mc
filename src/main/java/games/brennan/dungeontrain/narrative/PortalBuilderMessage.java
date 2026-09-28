package games.brennan.dungeontrain.narrative;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;

/**
 * The quiet line that names who built the dimensional carriage a player has just walked into.
 *
 * <p>The portal-room counterpart of the drifting carriage's credit line
 * ({@link games.brennan.dungeontrain.train.SharedCarriageMessage#creditLine}): shown once on
 * entering a room whose template carries a builder credit (see {@code PortalBuilderGreeter}), styled
 * {@link ChatFormatting#GRAY} like {@link PortalLibraryMessage} so the two room greetings speak in
 * one voice.</p>
 *
 * <p>Without it the credit lives only in the editor's data sheet and the Credits page — nowhere the
 * player standing inside the build would ever see it.</p>
 *
 * <p><b>Localization.</b> Every variant is a {@link Component#translatable} key under
 * {@code chat.dungeontrain.portal_builder.*}: the server picks the variant, the client renders it in
 * its own language. The builder's name is substituted as a literal — it is a name somebody typed,
 * not a phrase to translate.</p>
 */
public final class PortalBuilderMessage {

    /** Common prefix for every portal-builder lang key. */
    private static final String KEY = "chat.dungeontrain.portal_builder.";

    /** How many {@code KEY}{@code <n>} variants exist, 1-based. */
    private static final int VARIANTS = 8;

    /** Key for the line shown when the credited builder is the player themselves. */
    private static final String SELF_KEY = KEY + "self";

    private PortalBuilderMessage() {}

    /**
     * A line naming {@code builder}, or the "you built this one" line when {@code mine} is true.
     *
     * <p>Self gets a single variant rather than a rotation, for the same reason the library greeter
     * does: "this is yours" only needs saying one way, and a line phrased as a discovery about a
     * stranger would read oddly to the one player who placed every block.</p>
     */
    public static MutableComponent random(RandomSource random, String builder, boolean mine) {
        if (mine) return Component.translatable(SELF_KEY).withStyle(ChatFormatting.GRAY);
        // The name may have come from a downloaded build's credit rather than the shipped weights,
        // and lands in a CHAT component — the one renderer that interprets §. Sanitize before the
        // blank check so a name of pure control characters falls back to "someone".
        String clean = BookSafeText.sanitizeName(builder).trim();
        String name = clean.isBlank() ? "someone" : clean;
        int variant = 1 + random.nextInt(VARIANTS);
        return Component.translatable(KEY + variant, Component.literal(name))
            .withStyle(ChatFormatting.GRAY);
    }
}
