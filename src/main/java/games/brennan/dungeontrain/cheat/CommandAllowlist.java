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
 *   <li>Dungeon Train: {@code cinematic} (on-rails intro replay) and {@code debug},
 *       plus the read-only {@code narrative list} / {@code narrative progress}. The
 *       editor-authoring commands ({@code editor}, {@code save}, {@code reset},
 *       {@code package}, {@code export}, {@code import}) are dev tools and
 *       <b>taint</b> — as does {@code cinematographer} (a free-fly spectator
 *       camera).</li>
 *   <li>Vanilla permission-0 social/info: {@code help}, {@code me},
 *       {@code msg}/{@code tell}/{@code w}, {@code teammsg}/{@code tm},
 *       {@code trigger}, {@code list}.</li>
 *   <li>{@code playanimation} and {@code stopsound}: purely cosmetic,
 *       no gameplay effect.</li>
 * </ul>
 *
 * <p>The classifier works off the raw command string so command aliases
 * (e.g. {@code /dt} for {@code /dungeontrain}) and namespaced ids
 * (e.g. {@code minecraft:give}) are handled uniformly, and so the core rule is
 * unit-testable without constructing Brigadier parse trees.</p>
 */
public final class CommandAllowlist {

    private static final Set<String> DT_ROOTS = Set.of("dungeontrain", "dt");

    /**
     * Dungeon Train subcommands that don't taint a run. Deliberately minimal: only the on-rails
     * {@code cinematic} intro replay and the read-only {@code debug} tooling. The editor/authoring
     * commands ({@code editor}, {@code save}, {@code reset}, {@code package}, and the
     * {@code export}/{@code import} nested under {@code editor}) are <b>not</b> here — they are
     * dev/authoring tools, so running one taints the run. In practice authoring happens in creative
     * (already Free Play), so the editor UI's own commands early-return in
     * {@code CheatDetectionEvents.onCommand}; the taint only bites when one is run from a clean run.

     */
    private static final Set<String> DT_ALLOWED_SUBS = Set.of("cinematic", "debug");

    /**
     * Dungeon Train subcommands that lead into a <b>Train Editor authoring session</b>. These
     * still taint — they are not in {@link #DT_ALLOWED_SUBS} and never will be — but the taint
     * they cause is the reversible kind ({@code RunIntegrity.markEditorCheated}): the editor puts
     * the player in creative and puts them back, inventory included, so turning the authored
     * content off afterwards can hand the run back.
     *
     * <p>{@code editor} covers its whole subtree ({@code editor reset}, {@code editor export}, …)
     * because the classifier only ever looks at the first subcommand.</p>
     */
    private static final Set<String> DT_EDITOR_AUTHORING_SUBS = Set.of("editor", "save", "package");

    /** Read-only {@code narrative} subcommands (the rest of the tree taints). */
    private static final Set<String> NARRATIVE_READONLY = Set.of("list", "progress");

    /**
     * Non-DT-namespaced roots that don't taint a run: vanilla permission-0
     * social/info commands everyone may run, plus benign "end / reset the run"
     * actions — {@code /new-world} (the dev world-roll command) and bare
     * {@code /kill} (self-kill only — see {@link #isAllowed}). {@code /feedback}
     * and {@code /bug} (player feedback / bug-report submission) are also exempt,
     * as are the config-restore actions {@code /fixaisconfig} and {@code /fixconfig}
     * — putting the config back the way it shipped is the opposite of cheating, so
     * the fix must never taint the run it repairs.
     * {@code /playanimation} and {@code /stopsound} (cosmetic, no gameplay
     * effect) are exempt too. {@code /customcontent} is exempt for the same
     * reason as {@code /fixaisconfig}: it is what the Free Play notice links to
     * for turning custom editor content OFF, and tainting a player for clicking
     * the way <em>out</em> of Free Play would be exactly backwards. {@code /weather} is deliberately <b>not</b>
     * exempt — unlike a dedicated server, DT's Free Play system relies on
     * cheat commands actually reaching a normal player (see the class
     * javadoc), so weather changes still need to gate behind the Free Play
     * confirmation like {@code /gamemode} and {@code /give} do.
     */
    private static final Set<String> ALLOWED_ROOTS = Set.of(
        "help", "me", "msg", "tell", "w", "teammsg", "tm", "trigger", "list",
        "feedback", "bug", "fixaisconfig", "fixconfig", "customcontent", "new-world",
        "playanimation", "stopsound");

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
            return false; // editor/save/reset/package, cinematographer, spawn, speed, carriages, tracks, narrative give/reset/…
        }
        if (root.equals("kill")) return sub.isEmpty(); // bare /kill (self) only; /kill @e taints
        return ALLOWED_ROOTS.contains(root);
    }

    /**
     * Does running this command amount to opening the Train Editor? Only meaningful for commands
     * that {@link #taints} — it says which <em>kind</em> of taint to record, not whether to record
     * one. See {@link #DT_EDITOR_AUTHORING_SUBS}.
     */
    public static boolean isEditorAuthoring(ParseResults<CommandSourceStack> parse) {
        return isEditorAuthoring(rawString(parse));
    }

    /** Core, string-based classifier (unit-testable without Brigadier). */
    public static boolean isEditorAuthoring(String rawCommand) {
        String[] parts = tokens(rawCommand);
        if (parts.length < 2) return false;
        if (!DT_ROOTS.contains(stripNamespace(parts[0]))) return false;
        return DT_EDITOR_AUTHORING_SUBS.contains(parts[1].toLowerCase(Locale.ROOT));
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
