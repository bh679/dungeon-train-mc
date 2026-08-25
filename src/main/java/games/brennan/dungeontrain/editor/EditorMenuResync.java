package games.brennan.dungeontrain.editor;

import net.minecraft.server.level.ServerPlayer;

/**
 * Push a fresh sync to every world-space editor menu this player has open,
 * after the undo history has changed the state underneath them.
 *
 * <p><b>Why this is needed at all.</b> Each menu caches what it last sent and
 * only re-sends when its own dedup key moves — a hover change, a click, a
 * reopen. Every ordinary edit path therefore ends with an explicit sync of its
 * own. An undo writes the same state through a different door: files, sidecar
 * caches and block cells, with no menu involved. Without this the data reverts
 * correctly but the open panel keeps showing the pre-undo values, which reads
 * exactly like a broken undo.</p>
 *
 * <p>Note these are <b>resyncs</b>, not {@code forget()} calls: forgetting a
 * menu clears its open-state and (for the part menu) pushes an empty packet,
 * which closes the panel instead of refreshing it.</p>
 *
 * <p>The HUD status bar and the floating plot labels need no help — both dedup
 * on a key derived from the values themselves, so once the owning store's cache
 * has been invalidated their next tick recomputes a different key and re-pushes
 * on its own.</p>
 */
public final class EditorMenuResync {

    private EditorMenuResync() {}

    /**
     * Refresh every open editor menu for {@code player}. Each call is a no-op
     * when that menu is closed, so this is safe to call after any history step
     * regardless of what the step touched.
     */
    public static void afterHistoryStep(ServerPlayer player) {
        PartPositionMenuController.resyncOpen(player);
        BlockVariantMenuController.resyncOpen(player);
        ContainerContentsMenuController.resyncOpen(player);
        TemplateBlocksMenuController.resyncOpen(player);
        // Stage panels are shared rather than per-player — another author may be
        // looking at the stage this step just changed.
        StagePanelController.resyncAllOpen(player.server);
    }
}
