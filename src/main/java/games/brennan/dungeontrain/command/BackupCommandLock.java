package games.brennan.dungeontrain.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.Predicate;

/**
 * Closes the backup and restore commands to everyone — players, operators and the console.
 *
 * <p>A player must not be able to run a backup or a restore by hand; both happen at moments this
 * mod chooses (see {@code data.AutoRestore} and the triggers in {@code data.DungeonTrainBackup}).
 * Dungeon Train no longer asks for its {@code /dtbackup} / {@code /dtrestore} aliases, but Dungeon
 * Backup 0.2.0 — the build DT still requires — registers {@code /dungeonbackup backup|restore}
 * itself, open to all. Dungeon Backup 0.3.0 registers nothing, and this becomes a no-op.</p>
 *
 * <p><b>The requirement, not a cancel.</b> A cancelled {@code CommandEvent} would still leave the
 * command in every client's autocomplete. Rewriting the Brigadier {@code requirement} to "nobody"
 * removes it from the tree sent to clients <em>and</em> makes it fail to parse — an unknown command,
 * for every source. Same reflection, for the same reason, as
 * {@code advancement.SelfRevokeCommandAccess}.</p>
 */
public final class BackupCommandLock {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Every root a Dungeon Backup build has registered for DT's data. */
    static final List<String> ROOTS = List.of("dungeonbackup", "dtbackup", "dtrestore");

    private static final Predicate<CommandSourceStack> NOBODY = source -> false;

    private BackupCommandLock() {}

    /** Close whichever of {@link #ROOTS} exist. Call after every mod has registered its commands. */
    public static int close(CommandDispatcher<CommandSourceStack> dispatcher) {
        int closed = 0;
        Field requirement = null;
        for (String root : ROOTS) {
            CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(root);
            if (node == null) continue;
            if (requirement == null) requirement = requirementField();
            if (requirement == null) return closed;
            try {
                requirement.set(node, NOBODY);
                closed++;
            } catch (IllegalAccessException | RuntimeException e) {
                LOGGER.warn("[DungeonTrain] Could not close /{}: {}", root, e.toString());
            }
        }
        if (closed > 0) {
            LOGGER.info("[DungeonTrain] Closed {} backup command(s) — backups and restores are automatic", closed);
        }
        return closed;
    }

    private static Field requirementField() {
        try {
            Field field = CommandNode.class.getDeclaredField("requirement");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Brigadier CommandNode.requirement not reachable ({}) — "
                + "the backup commands stay open", e.toString());
            return null;
        }
    }
}
