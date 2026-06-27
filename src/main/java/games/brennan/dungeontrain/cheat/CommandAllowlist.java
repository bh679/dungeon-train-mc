package games.brennan.dungeontrain.cheat;

import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;

import java.util.Locale;
import java.util.Set;

/**
 * Classifies a player-run command as cheat-tainting or not, by <b>allowlist</b>:
 * anything not explicitly allowed taints the run. This auto-covers every vanilla
 * cheat command ({@code /gamemode}, {@code /give}, {@code /tp}, {@code /effect},
 * …) and any future ones without enumerating them.
 *
 * <p>Allowed (clean):
 * <ul>
 *   <li>Dungeon Train: {@code cinematic} (on-rails intro replay), {@code debug},
 *       and the editor-authoring roots ({@code editor}, {@code save},
 *       {@code reset}, {@code package}, {@code export}, {@code import}), plus the
 *       read-only {@code narrative list} / {@code narrative progress}. Note
 *       {@code cinematographer} is a free-fly spectator camera and is
 *       <b>not</b> exempt.</li>
 *   <li>Vanilla permission-0 social/info: {@code help}, {@code me},
 *       {@code msg}/{@code tell}/{@code w}, {@code teammsg}/{@code tm},
 *       {@code trigger}, {@code list}.</li>
 * </ul>
 *
 * <p>The classifier works off the raw command string so command aliases
 * (e.g. {@code /dt} for {@code /dungeontrain}) and namespaced ids
 * (e.g. {@code minecraft:give}) are handled uniformly, and so the core rule is
 * unit-testable without constructing Brigadier parse trees.</p>
 */
public final class CommandAllowlist {

    private static final Set<String> DT_ROOTS = Set.of("dungeontrain", "dt");

    /** Dungeon Train subcommands that don't taint a run. */
    private static final Set<String> DT_ALLOWED_SUBS = Set.of(
        "cinematic", "debug", "editor", "save", "reset", "package", "export", "import");

    /** Read-only {@code narrative} subcommands (the rest of the tree taints). */
    private static final Set<String> NARRATIVE_READONLY = Set.of("list", "progress");

    /**
     * Non-DT-namespaced roots that don't taint a run: vanilla permission-0
     * social/info commands everyone may run, plus benign "end / reset the run"
     * actions — {@code /new-world} (the dev world-roll command) and bare
     * {@code /kill} (self-kill only — see {@link #isAllowed}). {@code /feedback}
     * (player bug-report submission) is also exempt.
     */
    private static final Set<String> ALLOWED_ROOTS = Set.of(
        "help", "me", "msg", "tell", "w", "teammsg", "tm", "trigger", "list",
        "feedback", "new-world");

    private CommandAllowlist() {}

    /** @return true when running this parsed command should mark the run cheated. */
    public static boolean taints(ParseResults<CommandSourceStack> parse) {
        return taints(rawString(parse));
    }

    /** Core, string-based classifier (unit-testable without Brigadier). */
    public static boolean taints(String rawCommand) {
        String[] parts = tokens(rawCommand);
        if (parts.length == 0) return false;
        String root = stripNamespace(parts[0]);
        String sub = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "";
        return !isAllowed(root, sub, parts);
    }

    private static boolean isAllowed(String root, String sub, String[] parts) {
        if (DT_ROOTS.contains(root)) {
            if (sub.isEmpty()) return true;                 // bare "/dungeontrain" just prints usage
            if (DT_ALLOWED_SUBS.contains(sub)) return true;
            if (sub.equals("narrative")) {
                String n = parts.length > 2 ? parts[2].toLowerCase(Locale.ROOT) : "";
                return NARRATIVE_READONLY.contains(n);
            }
            return false; // cinematographer, spawn, speed, carriages, tracks, narrative give/reset/…
        }
        if (root.equals("kill")) return sub.isEmpty(); // bare /kill (self) only; /kill @e taints
        return ALLOWED_ROOTS.contains(root);
    }

    /** A short label for the warning message, e.g. {@code "/give"} or {@code "/dungeontrain cinematographer"}. */
    public static String label(ParseResults<CommandSourceStack> parse) {
        String[] parts = tokens(rawString(parse));
        if (parts.length == 0) return "/";
        String root = stripNamespace(parts[0]);
        if (DT_ROOTS.contains(root) && parts.length > 1) {
            return "/" + root + " " + parts[1].toLowerCase(Locale.ROOT);
        }
        return "/" + root;
    }

    private static String[] tokens(String raw) {
        String cmd = raw == null ? "" : raw.strip();
        if (cmd.startsWith("/")) cmd = cmd.substring(1).strip();
        if (cmd.isEmpty()) return new String[0];
        return cmd.split("\\s+");
    }

    private static String stripNamespace(String token) {
        String t = token.toLowerCase(Locale.ROOT);
        int colon = t.indexOf(':');
        return colon >= 0 ? t.substring(colon + 1) : t;
    }

    private static String rawString(ParseResults<CommandSourceStack> parse) {
        return parse.getReader().getString();
    }
}
