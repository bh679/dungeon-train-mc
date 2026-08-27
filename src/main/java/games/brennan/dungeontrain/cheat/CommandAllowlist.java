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
 *   <li>{@code advancement revoke @s everything} <b>exactly</b>: wiping your own slate
 *       destroys progress and can never create it. Every other {@code /advancement} form
 *       taints — {@code grant}, {@code set}, a partial revoke, and any target but
 *       {@code @s} (you may only ever clear yourself, never someone else).</li>
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
     * {@code advancement} is handled in {@link #isAllowed} rather than here:
     * only the exact form {@code /advancement revoke @s everything} is clean
     * (see {@link #isSelfRevokeEverything}) — every other spelling, target
     * included, still taints.
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
        // Exactly "/advancement revoke @s everything" — see isSelfRevokeEverything.
        if (root.equals("advancement")) return isSelfRevokeEverything(root, parts);
        return ALLOWED_ROOTS.contains(root);
    }

    /**
     * Is this the one {@code /advancement} form that doesn't taint —
     * {@code /advancement revoke @s everything}, exactly?
     *
     * <p>Clearing your <em>own</em> slate destroys progress and can never create it, which is the
     * opposite of cheating; it is also how
     * {@link games.brennan.dungeontrain.advancement.StartAgainAdvancement "It's Not That Simple"}
     * is earned, and that reward cannot bank on a tainted run. Everything else under
     * {@code /advancement} stays cheating: {@code grant} and {@code set} hand progress out, a
     * partial revoke ({@code … only <id>}) isn't the wipe the advancement is about, and
     * <b>any target but {@code @s}</b> is reaching into someone else's profile.
     *
     * <p>Public because {@link games.brennan.dungeontrain.advancement.StartAgainAdvancement}
     * arms off the same rule: one classifier, two call sites, so the command that is forgiven and
     * the command that is rewarded can never drift apart. Lives here rather than there because
     * this class is deliberately free of Minecraft types, which keeps the rule unit-testable
     * without bootstrapping the game.
     */
    public static boolean isSelfRevokeEverything(String rawCommand) {
        String[] parts = tokens(rawCommand);
        if (parts.length == 0) return false;
        return isSelfRevokeEverything(stripNamespace(parts[0]), parts);
    }

    /** Shared body, off an already-tokenised command whose root is already namespace-stripped. */
    private static boolean isSelfRevokeEverything(String root, String[] parts) {
        return root.equals("advancement")
            && parts.length == 4
            && parts[1].equalsIgnoreCase("revoke")
            && parts[2].equals("@s")
            && parts[3].equalsIgnoreCase("everything");
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
