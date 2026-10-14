package games.brennan.dungeontrain.logrequest;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text for the Developer {@code /version} ping (see {@link VersionRequestManager}): parsing the
 * relay-issued command and rendering the answer. No game classes, so every rule is unit-testable.
 */
public final class VersionReportText {

    /** Hard cap on a report; the packet decode cap and the relay both rely on it. */
    public static final int MAX_CHARS = 16 * 1024;

    /** {@code /version}, optional {@code full}, then the relay's request id: {@code /version full #ab12…}. */
    private static final Pattern COMMAND = Pattern.compile(
            "^[/!]version(\\s+full)?(?:\\s+#([A-Za-z0-9_-]{16,64}))?$", Pattern.CASE_INSENSITIVE);

    private VersionReportText() {}

    /** A parsed {@code /version} command. {@code reqId} is null when the relay didn't issue one. */
    public record Command(boolean full, @Nullable String reqId) {}

    /** The command in {@code content}, or null when it isn't {@code /version}. */
    @Nullable
    public static Command parse(@Nullable String content) {
        if (content == null) return null;
        Matcher m = COMMAND.matcher(content.trim());
        if (!m.matches()) return null;
        String reqId = m.group(2);
        return new Command(m.group(1) != null, reqId);
    }

    /** The short answer: {@code Dungeon Train 0.1288.0 (main)}. */
    public static String shortLine(String version, String branch) {
        String b = branch == null || branch.isBlank() || "?".equals(branch) ? "" : " (" + branch + ")";
        return "Dungeon Train " + (version == null || version.isBlank() ? "?" : version) + b;
    }

    /**
     * The full answer: the short line, the client's system spec, then every installed mod as
     * {@code id@version}. Capped at {@link #MAX_CHARS}, trimming the mod list first.
     */
    public static String full(String version, String branch, String spec, List<String> mods) {
        StringBuilder head = new StringBuilder(shortLine(version, branch));
        if (spec != null && !spec.isBlank()) head.append('\n').append(spec.strip());
        head.append("\nMods (").append(mods.size()).append("):");
        StringBuilder out = new StringBuilder(head);
        for (int i = 0; i < mods.size(); i++) {
            String line = "\n" + mods.get(i);
            String more = "\n… and " + (mods.size() - i) + " more";
            if (out.length() + line.length() + more.length() > MAX_CHARS) {
                out.append(more);
                break;
            }
            out.append(line);
        }
        return cap(out.toString());
    }

    /** Clamp any report text (the client's is untrusted) to {@link #MAX_CHARS}. */
    public static String cap(@Nullable String text) {
        if (text == null) return "";
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS - 1) + "…" : text;
    }
}
