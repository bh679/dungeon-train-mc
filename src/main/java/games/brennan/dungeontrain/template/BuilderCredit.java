package games.brennan.dungeontrain.template;

import java.util.Locale;

/**
 * Who originally built a template — the credit that travels with it in the kind's
 * {@code weights.json} (see {@link TemplateMeta#builder()}).
 *
 * <p>The {@code uuid} is the identity: it is what the relay knows a player by, what the builder
 * leaderboard counts, and what survives a Minecraft rename. The {@code name} is a cache of their
 * display name at the moment the credit was set, so the editor's data sheet and the Credits page
 * can print somebody without a lookup. A credit with a name and no uuid is allowed — a builder who
 * has never been on the relay can still be thanked — but it cannot be counted on a leaderboard.</p>
 *
 * <p>Value object: normalises on construction so two credits for the same person compare equal
 * whatever form the uuid arrived in (dashed, upper-case), and never holds a blank field as
 * anything but empty.</p>
 */
public record BuilderCredit(String uuid, String name) {

    /** Longest display name kept — the same cap as the editor label it sits beside. */
    public static final int NAME_MAX = TemplateMeta.NAME_MAX;

    public BuilderCredit {
        uuid = normaliseUuid(uuid);
        name = normaliseName(name);
    }

    /** True when the credit names anybody at all — the only kind worth storing. */
    public boolean known() {
        return !uuid.isEmpty() || !name.isEmpty();
    }

    /** True when the credit carries the durable identity a leaderboard can count. */
    public boolean hasUuid() {
        return !uuid.isEmpty();
    }

    /** What to print: the cached name, or the uuid when no name was ever cached. */
    public String display() {
        return name.isEmpty() ? uuid : name;
    }

    /**
     * {@code raw} as a stored credit, or {@code null} when it names nobody — so a store can treat
     * "clear the builder" and "set an empty builder" as the same write.
     */
    public static BuilderCredit ofOrNull(String uuid, String name) {
        BuilderCredit c = new BuilderCredit(uuid, name);
        return c.known() ? c : null;
    }

    /** Undashed, lower-case, trimmed; {@code ""} for null / blank. Never throws on a bad string. */
    public static String normaliseUuid(String raw) {
        if (raw == null) return "";
        return raw.trim().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** Trimmed, cut to {@link #NAME_MAX}; {@code ""} for null / blank. */
    public static String normaliseName(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        return s.length() > NAME_MAX ? s.substring(0, NAME_MAX) : s;
    }
}
