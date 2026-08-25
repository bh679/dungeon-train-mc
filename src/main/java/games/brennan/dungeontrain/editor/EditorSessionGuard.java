package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The two ends of the Train Editor's forced creative window, shared by every editor that owns a
 * session ({@link CarriageEditor} — and through it the pillar / part / contents / track editors —
 * plus {@link TunnelEditor} and {@link PortalRoomEditor}).
 *
 * <p>Editing a plot needs creative, so the editor switches the player into it and switches them
 * back on exit. That switch is DT's doing, not the player's, and it used to cost them the run
 * permanently: the game-mode backstop in {@code CheatDetectionEvents} recorded the ordinary
 * {@code RUN_CHEATED} taint, which has no way back, so one edit meant that world was Free Play
 * forever. Two things here fix that together, and neither is sound without the other:</p>
 *
 * <ol>
 *   <li>{@link #beginCreative} snapshots the inventory before creative can touch it and
 *       {@link #endCreative} puts it back, so <b>nothing leaves the editor</b>. A creative
 *       inventory reachable for the price of one edit would otherwise be a free item tap.</li>
 *   <li>Because of (1), the taint can be recorded as the reversible kind
 *       ({@link RunIntegrity#markEditorCheated}) — turning the authored content off afterwards
 *       hands the run back. It is recorded <em>before</em> the switch so the backstop, which
 *       early-returns on an already-cheated run, doesn't get there first with the permanent one.</li>
 * </ol>
 *
 * <p>Entering from creative is left alone: nothing is forced, so there is nothing to give back,
 * and the run was already Free Play for having been in creative at all.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class EditorSessionGuard {

    /**
     * Players currently inside a forced creative window, across all three session-owning editors.
     * Tracked here rather than asked of each editor in turn so the logout hook below has one
     * question to ask, and so a fourth editor gets the guarantee for free.
     */
    private static final Set<UUID> OPEN = ConcurrentHashMap.newKeySet();

    private EditorSessionGuard() {}

    /**
     * Is {@code player} inside a forced creative window right now? Asked by the game-mode backstop
     * in {@code CheatDetectionEvents}, which has to tell DT's own switch — the one the exemption is
     * granted for — apart from a player switching modes themselves, which revokes it.
     */
    public static boolean isInSession(ServerPlayer player) {
        return OPEN.contains(player.getUUID());
    }

    /**
     * Open the editor's creative window for {@code player}, whose game mode before entering was
     * {@code previous}. Callers store the return value on their session record and hand it back to
     * {@link #endCreative} on exit.
     *
     * @return the inventory to restore on exit, or {@code null} when the player was already in
     *         creative and nothing was forced (nothing to restore, and nothing to forgive)
     */
    public static ListTag beginCreative(ServerPlayer player, GameType previous) {
        if (previous == GameType.CREATIVE) return null;

        ListTag snapshot = player.getInventory().save(new ListTag());
        OPEN.add(player.getUUID());
        // Before the switch, not after — see (2) in the class doc.
        RunIntegrity.markEditorCheated(player,
            Component.translatable("chat.dungeontrain.free_play.cause.editor"));
        player.setGameMode(GameType.CREATIVE);
        return snapshot;
    }

    /**
     * Close the window: put back the inventory taken by {@link #beginCreative}. A {@code null}
     * snapshot means the window was never forced open, so this does nothing.
     *
     * <p>Callers restore position and game mode themselves — those differ per editor. This only
     * owns the inventory, which is the half the exemption depends on.</p>
     */
    public static void endCreative(ServerPlayer player, ListTag snapshot) {
        OPEN.remove(player.getUUID());
        if (snapshot == null) return;
        // Inventory.load clears all three sections before reading, so this is a true replace:
        // whatever was pulled from the creative menu to build with does not come out.
        player.getInventory().load(snapshot);
        player.inventoryMenu.broadcastFullState();
    }

    /**
     * A player who logs out inside the editor never reaches {@link #endCreative} — the editors'
     * session maps are plain in-memory statics that do not survive it, so the snapshot is gone and
     * the creative inventory they are wearing is what comes back next login. That is precisely the
     * thing the exemption promises cannot happen, so the exemption goes. The taint stays: they did
     * edit, and this only decides whether it can ever be handed back.
     */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!OPEN.remove(player.getUUID())) return;
        RunIntegrity.revokeEditorOnlyExemption(player);
    }
}
