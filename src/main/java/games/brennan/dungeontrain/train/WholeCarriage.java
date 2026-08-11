package games.brennan.dungeontrain.train;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifier for a <b>whole carriage</b> — one saved build that holds the shell and the interior
 * together in a single template, rather than the shell/contents split the spawn pool uses.
 *
 * <p>The split exists because any shell can pair with any room: {@link CarriageVariant} covers the
 * outside, {@link CarriageContents} the inside, and
 * {@link CarriagePlacer#placeAt(net.minecraft.server.level.ServerLevel, net.minecraft.core.BlockPos,
 * CarriageVariant, CarriageDims) placeAt} rolls one against the other at spawn. That is the right
 * model for variety and the wrong one for a carriage that was authored as a single thing — the
 * builder who furnished an interior for <em>this</em> shell did not intend it to be swapped out.
 * A whole carriage keeps the two as one.</p>
 *
 * <p>Every whole carriage is user-authored. There are no built-ins and no bundled tier: the
 * {@link #id()} is the canonical lowercase token used as the filename under
 * {@code config/dungeontrain/user/wholecarriages/} and as the id in
 * {@link WholeCarriageRegistry}.</p>
 */
public record WholeCarriage(String name) {

    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    public WholeCarriage {
        Objects.requireNonNull(name, "name");
    }

    /**
     * Validating factory. Use this at every boundary that accepts a name from a player or a packet;
     * the canonical constructor stays permissive so registry scans can build handles for ids that
     * are already on disk without a second validation pass.
     */
    public static WholeCarriage of(String name) {
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!NAME_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                "Invalid whole-carriage name '" + name + "' — must match " + NAME_PATTERN.pattern());
        }
        return new WholeCarriage(key);
    }

    /** True iff {@code name} could be a whole-carriage id. Non-throwing counterpart to {@link #of}. */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    public String id() {
        return name;
    }
}
