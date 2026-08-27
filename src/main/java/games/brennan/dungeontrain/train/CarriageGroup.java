package games.brennan.dungeontrain.train;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identifier for a <b>carriage group</b> — one saved build covering a whole run of carriages, kept
 * together as a single template.
 *
 * <p>The Train Outside builder parks a group and every carriage in it is editable, because that is
 * what you are authoring from the platform: a stretch of train, composed. Saving that as one
 * {@link WholeCarriage} per carriage would keep the blocks but lose the composition — which carriage
 * followed which, and that they were made to be seen together.</p>
 *
 * <p>Its own kind rather than an oversized {@link WholeCarriage} because the two are gated on their
 * footprint, in opposite directions: a whole carriage is refused unless it is exactly one
 * {@link CarriageDims}, and a group is refused unless it is a whole number of them. Sharing a store
 * would mean one of those checks could not be made.</p>
 *
 * <p>A group holds its carriage <em>count</em> in its footprint — {@code sizeX / dims.length()} — so
 * a group authored at three carriages is recognisably a three-carriage group wherever it is read,
 * and a world whose {@code groupSize} differs simply never offers it. See
 * {@code CarriageGroupTemplateStore}.</p>
 */
public record CarriageGroup(String name) {

    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9_]{1,32}$");

    public CarriageGroup {
        Objects.requireNonNull(name, "name");
    }

    /**
     * Validating factory. Use this at every boundary that accepts a name from a player or a packet;
     * the canonical constructor stays permissive so registry scans can build handles for ids already
     * on disk without a second validation pass.
     */
    public static CarriageGroup of(String name) {
        String key = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!NAME_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                "Invalid carriage-group name '" + name + "' — must match " + NAME_PATTERN.pattern());
        }
        return new CarriageGroup(key);
    }

    /** True iff {@code name} could be a carriage-group id. Non-throwing counterpart to {@link #of}. */
    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name.toLowerCase(Locale.ROOT)).matches();
    }

    public String id() {
        return name;
    }
}
