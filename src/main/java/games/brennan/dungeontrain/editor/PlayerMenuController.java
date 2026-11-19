package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What every server-side editor menu controller shares: one open-state record per player, the
 * lifecycle that drops it (editor exit, logout, server stop), the resync the undo history asks for,
 * the OP gate every opening and edit runs, and the undo arm every edit makes before it writes.
 *
 * <p>{@code S} is the controller's own per-player record — where the panel hangs, which plot or
 * stage it is open on. The map is private: subclasses read and write it through
 * {@link #open(ServerPlayer)}, {@link #put} and {@link #remove}, so no controller can forget to
 * drop its entry on the paths {@link MenuControllers} drives.</p>
 *
 * <p>The hooks have the ordinary meaning by default and are overridden by the two controllers
 * whose lifecycles differ: the part menu, whose {@link #forget} must also tell the client to close
 * (the per-tick hover sync would otherwise never re-send), and the stage panel, whose data is
 * shared across players so a history step resyncs <i>every</i> open panel.</p>
 */
public abstract class PlayerMenuController<S> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<UUID, S> open = new ConcurrentHashMap<>();
    private final String name;
    private final String undoLabel;

    /**
     * @param name      a short tag for log lines, e.g. {@code "BlockVariantMenu"}
     * @param undoLabel the history step's label when an edit through this menu is the tick's first
     *                  edit — the words the author reads in the undo feedback line
     */
    protected PlayerMenuController(String name, String undoLabel) {
        this.name = name;
        this.undoLabel = undoLabel;
    }

    public final String name() {
        return name;
    }

    // ------------------------------------------------------------------ open state

    /** This player's open record, or null when their menu is closed. */
    @Nullable
    public final S open(ServerPlayer player) {
        return open.get(player.getUUID());
    }

    public final boolean isOpen(ServerPlayer player) {
        return open.containsKey(player.getUUID());
    }

    protected final void put(ServerPlayer player, S state) {
        open.put(player.getUUID(), state);
    }

    /** Drop the record without telling the client anything. Returns what was there. */
    @Nullable
    protected final S remove(ServerPlayer player) {
        return open.remove(player.getUUID());
    }

    @Nullable
    protected final S removeById(UUID id) {
        return open.remove(id);
    }

    /** Every open entry — for the controllers whose data is shared and must fan out. */
    protected final Map<UUID, S> openEntries() {
        return open;
    }

    // ------------------------------------------------------------------ lifecycle hooks

    /** Per-player editor-exit reset. */
    public void forget(ServerPlayer player) {
        remove(player);
    }

    /** The player is leaving the server: nothing is sent, the client is going away. */
    public void onLogout(ServerPlayer player) {
        remove(player);
    }

    /** Server stop. */
    public void clearAll() {
        open.clear();
    }

    /**
     * Re-send this player's open menu because its state changed behind the controller's back — an
     * undo or redo. No-op when nothing is open.
     */
    public void resyncOpen(ServerPlayer player) {
        S state = open(player);
        if (state != null) resync(player, state);
    }

    /** Compose and send a fresh sync for {@code state}. */
    protected abstract void resync(ServerPlayer player, S state);

    /**
     * What a history step asks of this controller. Per-player menus resync the stepping player's
     * own panel; a controller over shared data overrides this to refresh everyone's.
     */
    public void resyncAfterHistory(ServerPlayer player) {
        resyncOpen(player);
    }

    // ------------------------------------------------------------------ guards

    /**
     * The lang key of the "requires OP" action-bar line for this menu, or null for a menu with no
     * opening gesture of its own (reached from the X menu, or auto-opened by a hover) — such a menu
     * has nothing to answer on the action bar and only ever refuses silently.
     */
    @Nullable
    protected String requiresOpKey() {
        return null;
    }

    /** The key a packet names, read off an open record — what {@link #authorise} compares. */
    protected abstract String keyOf(S state);

    /**
     * The opening gate: true when the player may open this menu, else the "requires OP" line on
     * their action bar and false.
     */
    protected final boolean requireOp(ServerPlayer player) {
        if (player.hasPermissions(2)) return true;
        String key = requiresOpKey();
        if (key == null) return isOp(player);
        EditorFeedback.actionBar(player, Component.translatable(key), ChatFormatting.RED);
        return false;
    }

    /** The silent edit gate: true when OP, else a warning in the log (a packet, not a gesture). */
    protected final boolean isOp(ServerPlayer player) {
        if (player.hasPermissions(2)) return true;
        LOGGER.warn("[DungeonTrain] {} edit rejected: player {} not OP", name, player.getName().getString());
        return false;
    }

    /**
     * The edit gate: the player must be OP and must have this menu open on {@code key} — the key
     * the server itself handed out at open time, so it is a stronger check than where they are
     * standing now, and it is what lets an author step off the plot with the menu still up.
     * Returns the open record, or null (with a warning logged) when the edit is refused.
     */
    @Nullable
    protected final S authorise(ServerPlayer player, String key) {
        if (!isOp(player)) return null;
        S state = open(player);
        if (state == null || !keyOf(state).equals(key)) {
            LOGGER.warn("[DungeonTrain] {} edit rejected: player {} has no open menu for '{}'",
                name, player.getName().getString(), key);
            return null;
        }
        return state;
    }

    // ------------------------------------------------------------------ undo

    /** The label of the history step an edit through this menu produces, when it is the tick's first. */
    public final String undoLabel() {
        return undoLabel;
    }

    /**
     * Arm the undo recorder before an edit writes. Menu state lives in the config tree, so the
     * default snapshots that; the block-variant menu overrides it to snapshot the plot's sidecar.
     * Called by every {@code applyEdit} once its guards have passed, and nowhere else.
     */
    protected void armUndo(ServerPlayer player) {
        EditorEditRecorder.notePendingConfig(player, undoLabel());
    }
}
