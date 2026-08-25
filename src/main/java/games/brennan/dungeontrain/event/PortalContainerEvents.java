package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.portal.PortalContainerLink;
import games.brennan.dungeontrain.portal.PortalPairIndex;
import games.brennan.dungeontrain.portal.PortalRemoteViewers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Makes a portal corridor's two copies of a container behave as one: opening either opens the same
 * inventory, and breaking either drops it once. See {@link PortalContainerLink} for why the mirror
 * alone cannot do this and what the failure looked like.
 *
 * <p><b>Both handlers run at {@link EventPriority#HIGH}, and for the break that is load-bearing.</b>
 * {@link PortalEditEvents#onBlockBreak} sits on the same event at the default priority and mirrors
 * AIR into the partner cell <i>during</i> {@code BreakEvent} — before the block has actually been
 * removed. Gathering first is the only thing that gets the contents across before that clear takes
 * them.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PortalContainerEvents {

    private PortalContainerEvents() {}

    /**
     * A click on a corridor container: consolidate the pair, and if this is the shell copy, open the
     * real one instead.
     *
     * <p>Fires on every right-click in the world, so it short-circuits on
     * {@link PortalPairIndex#isEmpty()} before touching coordinates — the same guard
     * {@code PortalEditMirror} opens with.</p>
     *
     * <p>The consolidation happens whichever copy was clicked, including the canonical one: that is
     * what picks up a full shulker box placed on the twin side, whose contents landed in the twin's
     * own block entity because {@code BlockItem} writes them after the mirror has already run.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.isCanceled()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (PortalPairIndex.isEmpty()) return;

        BlockPos pos = event.getPos();
        PortalContainerLink.Cell cell = PortalContainerLink.linkOf(level, pos);
        if (cell == null) return;
        if (PortalContainerLink.containerAt(level, pos) == null) return;

        BlockPos canonical = cell.canonical();
        PortalContainerLink.gatherInto(level, canonical, cell.shell());

        // Clicked the copy that holds the items: vanilla opens it, and vanilla's reach check is the
        // right one, so there is nothing left to do.
        if (cell.canonicalHere()) return;

        // Only redirect a click vanilla would itself have turned into an open. Sneaking with
        // something in hand means "place a block against this", which must stay a placement — the
        // same condition Item.useOn / BlockState.useWithoutItem is gated on upstream.
        boolean handsEmpty = player.getMainHandItem().isEmpty() && player.getOffhandItem().isEmpty();
        if (player.isSecondaryUseActive() && !handsEmpty) return;

        BlockState canonicalState = level.getBlockState(canonical);
        MenuProvider provider = canonicalState.getMenuProvider(level, canonical);
        if (provider == null) return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
        player.openMenu(provider);
        // After openMenu, not before: opening fires PlayerContainerEvent.Open, which clears the entry.
        PortalRemoteViewers.opened(player, pos);
    }

    /**
     * A corridor container is about to be broken: pull the pair's contents into the copy being mined,
     * so vanilla's drop carries them and the mirror's silent clear of the partner takes nothing.
     *
     * <p>Symmetric — it does not matter which copy the player mined. One drop either way, and the
     * other copy is disabled rather than duplicated.</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (PortalPairIndex.isEmpty()) return;

        BlockPos pos = event.getPos();
        PortalContainerLink.Cell cell = PortalContainerLink.linkOf(level, pos);
        if (cell == null) return;
        PortalContainerLink.gatherInto(level, pos, cell.partner());
    }

    // ---------------- Remote-viewer bookkeeping ----------------

    /**
     * Any container opening for this player invalidates the previous remote view.
     *
     * <p>This also fires for the redirected open above — from inside {@code openMenu}, before it
     * returns — which is exactly why {@link #onRightClickBlock} records the viewer afterwards.</p>
     */
    @SubscribeEvent
    public static void onContainerOpen(PlayerContainerEvent.Open event) {
        PortalRemoteViewers.closed(event.getEntity());
    }

    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        PortalRemoteViewers.closed(event.getEntity());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PortalRemoteViewers.closed(event.getEntity());
    }
}
