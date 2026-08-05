package games.brennan.dungeontrain.event;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player-attributed detection for the three drifting-carriage advancements that happen while a player
 * is doing something to a carriage:
 *
 * <ul>
 *   <li>{@code drift_edit_new} — changed a drifting carriage nobody has touched yet (a fresh local
 *       build, on its way to its first upload).</li>
 *   <li>{@code drift_edit_other} — changed one leased from the pool that somebody else authored.</li>
 *   <li>{@code drift_gift_left} — left an item in a container aboard one, for whoever leases it next.</li>
 * </ul>
 *
 * <p>(The fourth, {@code drift_own_return}, fires from {@link SharedCarriageEnterEvents} — it is about
 * arriving, not editing.)</p>
 *
 * <p><b>Why not the mixin.</b> {@code SableBlockChangeGuardMixin} already sees every carriage block
 * change, but Sable's hook carries no player, and these advancements are all about <i>who</i> did it.
 * NeoForge's own block events carry the actor, and fire in the same coordinate space the mixin resolves
 * in — sub-level plot space, which is what {@link SharedCarriageRegistry#resolve} expects.</p>
 *
 * <p><b>Consent.</b> All three are gated on {@link SharedCarriageGate#canContribute(ServerPlayer)}.
 * Without network consent the edit never leaves this machine, and every one of these advancements
 * promises a stranger will see it. Server-side only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedCarriageAdvancementEvents {

    /**
     * Item count seen in a container the moment a player opened it, keyed by player. Written on the
     * open click, consumed on the matching close.
     */
    private static final Map<UUID, OpenContainer> OPEN_CONTAINER = new ConcurrentHashMap<>();

    /** A container a player has open: where it is, which carriage it belongs to, and what was in it. */
    private record OpenContainer(BlockPos pos, UUID subLevelId, int itemCount) {}

    private SharedCarriageAdvancementEvents() {}

    // ---------------- Edits ----------------

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        creditEdit(player, event.getLevel(), event.getPos());
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        creditEdit(player, event.getLevel(), event.getPos());
    }

    /** Fire whichever edit advancement this carriage warrants, or nothing if it isn't a drifting one. */
    private static void creditEdit(ServerPlayer player, net.minecraft.world.level.LevelAccessor levelAccess, BlockPos pos) {
        if (!(levelAccess instanceof ServerLevel level)) return;
        if (!SharedCarriageGate.canContribute(player)) return;
        SharedCarriageRegistry.Instance inst = resolveCarriage(level, pos);
        if (inst == null || inst.isCulled()) return;
        // A fresh local build has no author yet — this player is about to become its first. A pooled
        // build they authored themselves is neither: changing your own work back is not a milestone.
        if (!inst.leasedFromPool) {
            trigger(player, "drift_edit_new");
        } else if (!inst.isAuthoredBy(player.getUUID())) {
            trigger(player, "drift_edit_other");
        }
    }

    // ---------------- Leaving something in a chest ----------------

    /**
     * Snapshot a carriage container's contents as the player opens it, so the close can tell whether
     * they left something behind. Cheap: everything that isn't a container inside a registered drifting
     * carriage short-circuits before the block-entity read.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        BlockPos pos = event.getPos();
        SharedCarriageRegistry.Instance inst = resolveCarriage(level, pos);
        if (inst == null || inst.isCulled()) return;
        Container container = containerAt(level, pos);
        if (container == null) return;
        OPEN_CONTAINER.put(player.getUUID(), new OpenContainer(pos.immutable(), inst.subLevelId, countItems(container)));
    }

    /**
     * On close, re-read the container the player opened. If it now holds MORE than it did, they left
     * something for whoever leases this carriage next:
     *
     * <ol>
     *   <li>queue the cell for upload — container contents change no block state, so Sable's block-change
     *       hook never sees this, and without the queue the gift is never sent (a fresh carriage would
     *       never even get its first submit); and</li>
     *   <li>award the advancement.</li>
     * </ol>
     *
     * <p>Deliberately one-directional: taking items out does NOT queue anything. Ordinary looting must
     * not dirty a carriage — the same rule the block-change hook keeps for breaking a loot container.</p>
     */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        OpenContainer opened = OPEN_CONTAINER.remove(player.getUUID());
        if (opened == null) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        Container container = containerAt(level, opened.pos());
        if (container == null) return;
        if (!isGift(opened.itemCount(), countItems(container))) return;
        SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.resolve(
                opened.subLevelId(), opened.pos().getX(), opened.pos().getY(), opened.pos().getZ());
        if (inst == null || inst.isCulled()) return;
        inst.enqueue(opened.pos());
        if (SharedCarriageGate.canContribute(player)) trigger(player, "drift_gift_left");
    }

    /**
     * Whether the contents went UP between open and close — i.e. the player left something behind.
     * {@code before} is null when no open was recorded, which can never count as a gift.
     */
    static boolean isGift(Integer before, int after) {
        return before != null && after > before;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OPEN_CONTAINER.remove(event.getEntity().getUUID());
    }

    // ---------------- Helpers ----------------

    /**
     * The drifting carriage a block at {@code pos} belongs to, or null. Same resolution path as
     * {@code SableBlockChangeGuardMixin}: sub-level container → plot → sub-level id → footprint match.
     */
    private static SharedCarriageRegistry.Instance resolveCarriage(ServerLevel level, BlockPos pos) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return null;
        ChunkPos cpos = new ChunkPos(pos);
        if (container.getChunkHolder(cpos) == null) return null; // ordinary world chunk
        LevelPlot plot = container.getPlot(cpos);
        if (plot == null || !(plot.getSubLevel() instanceof ServerSubLevel serverSub)) return null;
        UUID subLevelId = serverSub.getUniqueId();
        if (!SharedCarriageRegistry.hasSubLevel(subLevelId)) return null;
        return SharedCarriageRegistry.resolve(subLevelId, pos.getX(), pos.getY(), pos.getZ());
    }

    private static Container containerAt(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    /** Total items held, summed across slots — the coarse measure the gift check compares. */
    private static int countItems(Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty()) total += stack.getCount();
        }
        return total;
    }

    private static void trigger(ServerPlayer player, String actionId) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, actionId);
    }
}
