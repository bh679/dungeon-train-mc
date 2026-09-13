package games.brennan.dungeontrain.advancement;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BooleanSupplier;

/**
 * Lets a capstone-holder write {@code @s} — and only {@code @s} — without permission level 2.
 *
 * <p>Vanilla refuses every selector below permission 2, at two separate points: parse
 * ({@code EntityArgument.parse(reader, source)} → {@code EntitySelectorParser.allowSelectors}) and
 * resolve ({@code EntitySelector.checkPermissions}). Both are mixed in
 * ({@code EntityArgumentSelfSelectorMixin}, {@code EntitySelectorSelfPermissionMixin}) and both
 * answer through here, so the two can't drift: a player who {@linkplain
 * StartAgainAdvancement#holdsBankedCapstone holds the banked capstone} may target themself, which is
 * the one selector {@code /advancement revoke @s everything} needs. Never {@code @a}, {@code @p},
 * {@code @e}, {@code @r} — the token check is exact — and never anyone else's tree.</p>
 *
 * <p>On the client the source is a {@code ClientSuggestionProvider}, not a
 * {@code CommandSourceStack}, and there is no sidecar to read. The client-side answer is cosmetic
 * (it decides whether the chat box paints {@code @s} red), so it asks the only thing the client
 * knows: did the server put an {@code advancement} node in the tree it sent? That node is only
 * ever sent to a capstone-holder (see {@link SelfRevokeCommandAccess}), so it is exactly "the server
 * told me I may". The client installs that check through {@link #setClientCheck} at client setup, so
 * this class never names a client type and a dedicated server never loads one.</p>
 */
public final class SelfSelectorGrant {

    /** Client-only answer to "did the server expose {@code /advancement} to me?"; absent on a server. */
    private static volatile BooleanSupplier clientCheck = () -> false;

    private SelfSelectorGrant() {}

    /** Install the client-side tree check. Called once from client setup. */
    public static void setClientCheck(BooleanSupplier check) {
        clientCheck = check;
    }

    /**
     * Parse-time: may {@code source} write the selector that starts at the reader's cursor?
     * Only when it is a bare {@code @s} token and the source is a self-revoke-eligible player.
     */
    public static boolean allowsAtParse(StringReader reader, Object source) {
        if (!isBareSelfSelector(reader.getRemaining())) return false;
        if (source instanceof CommandSourceStack stack) return holds(stack);
        // Client-side highlight only — the server re-checks with the real source.
        return clientCheck.getAsBoolean();
    }

    /** Resolve-time: may this {@code CommandSourceStack} resolve a {@code @s} it already parsed? */
    public static boolean holds(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player != null && StartAgainAdvancement.holdsBankedCapstone(player);
    }

    /**
     * Is the remaining input exactly the {@code @s} token — {@code "@s"} or {@code "@s …"} — and
     * nothing that merely starts with it ({@code @self}, {@code @s2})? Package-private for unit tests.
     */
    static boolean isBareSelfSelector(String remaining) {
        if (!remaining.startsWith("@s")) return false;
        return remaining.length() == 2 || Character.isWhitespace(remaining.charAt(2));
    }
}
