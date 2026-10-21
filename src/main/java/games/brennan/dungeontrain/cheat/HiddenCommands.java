package games.brennan.dungeontrain.cheat;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;

/**
 * Commands that work but are never offered: pruned from the <b>client's suggestion tree</b> so tab
 * completion in survival shows {@code /bug} (and, for an Everything Burrito holder,
 * {@code /advancement revoke @s everything}) and nothing else of DT's.
 *
 * <p>Why prune the client tree and not gate with {@code requires}: the server builds each player's
 * tree from the nodes' requirements ({@code SelfRevokeCommandAccess} opens one that way), but a
 * failed requirement also stops the command parsing, and these commands are run by chat buttons the
 * game builds — the Free Play notice's {@code /fixconfig}, a Live Eye ban's {@code /liveappeal},
 * the custom-content {@code /customcontent}. {@code ClientPacketListener.commands} is used for
 * suggestions only: the server executes from its own tree, and NeoForge runs client commands from
 * {@code ClientCommandHandler}'s own copy. So removing a root here changes what is suggested and
 * nothing else; a hidden command typed in full, or clicked, still runs (no node on the client means
 * no signable arguments, so it is sent unsigned, which every hidden command accepts — they take
 * {@code greedyString}, never {@code MessageArgument}). Wired by {@code ClientPacketListenerCommandsMixin}.
 *
 * <p><b>Reflection, deliberately</b>: Brigadier's {@code CommandNode.children} / {@code literals}
 * maps are private, in a library, not a Minecraft class — the same bet {@code SelfRevokeCommandAccess}
 * makes on {@code requirement}. Fails soft: if a field moves, nothing is hidden and the log says so.
 */
public final class HiddenCommands {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Roots pruned from the suggestion tree. DT's player-facing server commands, Dungeon Backup's
     * root and DT's aliases of it, Discord Presence's {@code /feedback} ({@code /bug} is the one
     * door), and every client-only command DT registers. All stay runnable.
     */
    public static final Set<String> HIDDEN_ROOTS = Set.of(
        // server, open to everyone
        "fixaisconfig", "fixconfig", "customcontent", "liveappeal", "reportcarriage", "dtrebuild",
        "dtbackup", "dtrestore", "dungeonbackup", "feedback",
        // client-only
        "streamer", "twitch", "discord", "dungeontrain-options", "dungeontrain-rewards",
        "framerate-throttle", "new-world",
        "dt-ledger-preview", "dungeontrain-debug-contribute", "dungeontrain-debug-death-pages");

    private static boolean warned;

    private HiddenCommands() {}

    /**
     * Remove every {@link #HIDDEN_ROOTS} literal from {@code root}.
     *
     * @return how many were removed; -1 when Brigadier's internals could not be reached
     */
    public static int prune(RootCommandNode<?> root) {
        return prune(root, HIDDEN_ROOTS);
    }

    static int prune(RootCommandNode<?> root, Set<String> hidden) {
        Map<String, ?> children = map(root, "children");
        Map<String, ?> literals = map(root, "literals");
        if (children == null || literals == null) return -1;
        int removed = 0;
        for (String name : hidden) {
            boolean had = children.remove(name) != null;
            literals.remove(name);
            if (had) removed++;
        }
        return removed;
    }

    private static Map<String, ?> map(CommandNode<?> node, String field) {
        try {
            Field f = CommandNode.class.getDeclaredField(field);
            f.setAccessible(true);
            return (Map<String, ?>) f.get(node);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                LOGGER.warn("[DungeonTrain] Brigadier CommandNode.{} not reachable ({}) — DT's commands stay in tab completion",
                    field, e.toString());
            }
            return null;
        }
    }
}
