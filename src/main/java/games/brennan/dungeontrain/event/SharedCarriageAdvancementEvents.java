package games.brennan.dungeontrain.event;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.advancement.ModAdvancementTriggers;
import games.brennan.dungeontrain.net.SnapshotCue;
import games.brennan.dungeontrain.net.SnapshotCuePacket;
import games.brennan.dungeontrain.train.SharedCarriageLookup;
import games.brennan.dungeontrain.train.SharedCarriageRegistry;
import games.brennan.dungeontrain.train.StorageContents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;

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
 * <p>The container close hook also feeds storage edits to the upload (see {@link #closeAction}): every
 * change — gift, withdrawal or rearrangement — is flagged locally and travels in one batch when the
 * player leaves the carriage or the next block edit on it is flushed.</p>
 *
 * <p>(The fourth, {@code drift_own_return}, fires from {@link SharedCarriageEnterEvents} — it is about
 * arriving, not editing.)</p>
 *
 * <p><b>Why not the mixin.</b> {@code SableBlockChangeGuardMixin} already sees every carriage block
 * change, but Sable's hook carries no player, and these advancements are all about <i>who</i> did it.
 * NeoForge's own block events carry the actor, and fire in the same coordinate space the mixin resolves
 * in — sub-level plot space, which is what {@link SharedCarriageLookup} expects.</p>
 *
 * <p><b>Consent.</b> All three are gated on {@link SharedCarriageGate#canContribute(ServerPlayer)}.
 * Without network consent the edit never leaves this machine, and every one of these advancements
 * promises a stranger will see it. Server-side only.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class SharedCarriageAdvancementEvents {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * What a container held the moment a player opened it, keyed by player. Written on the open click,
     * consumed on the matching close.
     */
    private static final Map<UUID, OpenContainer> OPEN_CONTAINER = new ConcurrentHashMap<>();

    /** Minimum wall-clock gap between two ride-photo cues for one player. */
    static final long CUE_THROTTLE_MS = 5000L;

    /** Player → when they were last cued for a {@link SnapshotCue#BUILDING} photo. */
    private static final Map<UUID, Long> LAST_CUE_MS = new ConcurrentHashMap<>();

    /**
     * A container a player has open: where it is, which carriage it belongs to, and what was in it —
     * both the coarse item count (the gift check) and a slot-by-slot signature (any change at all).
     * The level is carried explicitly — a carriage's blocks live in the plot level, which is not
     * necessarily the level the player themselves is standing in.
     */
    private record OpenContainer(ResourceKey<Level> levelKey, BlockPos pos, UUID subLevelId,
                                 int itemCount, long contentsSig, double value) {}

    /** What the close hook does with a storage edit — see {@link #closeAction}. */
    enum CloseAction { PARK, NONE }

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
        SharedCarriageRegistry.Instance inst = SharedCarriageLookup.byBlockPos(level, pos);
        if (inst == null || inst.isCulled()) return;
        // Ahead of the consent gate on purpose: a ride photo never leaves this machine, so the
        // player gets their record of building in a drifting carriage whether or not the build
        // itself is allowed to travel. Only the advancements below promise a stranger will see it.
        cuePhoto(player, "changed a drifting carriage");
        if (!SharedCarriageGate.canContribute(player)) return;
        // A fresh local build has no author yet — this player is about to become its first. A pooled
        // build they authored themselves is neither: changing your own work back is not a milestone.
        String actionId = !inst.leasedFromPool ? "drift_edit_new"
                : !inst.isAuthoredBy(player.getUUID()) ? "drift_edit_other"
                : null;
        LOGGER.debug("[DungeonTrain] drifting-carriage edit by {} at {} pIdx={} leased={} ownAuthor={} → {}",
                player.getGameProfile().getName(), pos, inst.pIdx, inst.leasedFromPool,
                inst.isAuthoredBy(player.getUUID()), actionId);
        if (actionId != null) trigger(player, actionId);
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
        SharedCarriageRegistry.Instance inst = SharedCarriageLookup.byBlockPos(level, pos);
        if (inst == null || inst.isCulled()) return;
        StorageContents.Snapshot before = StorageContents.read(level, pos);
        if (before == null) return;
        OPEN_CONTAINER.put(player.getUUID(), new OpenContainer(level.dimension(), pos.immutable(),
                inst.subLevelId, before.itemCount(), before.sig(), before.value()));
    }

    /**
     * On close, re-read the storage the player opened. Its contents change no block state, so Sable's
     * block-change hook never sees them — this is the only place the edit is noticed (see
     * {@link #closeAction} for what happens to it).
     *
     * <p>If it now holds MORE than it did, the player also left something for whoever leases this
     * carriage next, which awards the gift advancement. Taking items out never does.</p>
     */
    @SubscribeEvent
    public static void onContainerClose(PlayerContainerEvent.Close event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        OpenContainer opened = OPEN_CONTAINER.remove(player.getUUID());
        if (opened == null) return;
        if (player.getServer() == null) return;
        ServerLevel level = player.getServer().getLevel(opened.levelKey());
        if (level == null) return;
        StorageContents.Snapshot after = StorageContents.read(level, opened.pos());
        if (after == null) return;
        SharedCarriageRegistry.Instance inst = SharedCarriageRegistry.resolve(
                opened.subLevelId(), opened.pos().getX(), opened.pos().getY(), opened.pos().getZ());
        if (inst == null || inst.isCulled()) return;
        // Ahead of the action check: a swap for something better changes no count but still gives.
        DriftGenerosity.onClose(player, inst, opened.levelKey(), opened.pos(), opened.value(), after.value());
        CloseAction action = closeAction(opened.itemCount(), after.itemCount(), opened.contentsSig(), after.sig());
        if (action == CloseAction.NONE) return;
        // Every half shares the combined signature and count, so each is parked against the same baseline.
        for (BlockPos cell : StorageContents.cells(level, opened.pos())) {
            inst.parkContainer(cell, opened.contentsSig(), opened.itemCount());
        }
        LOGGER.debug("[DungeonTrain] storage changed in drifting carriage pIdx={} at {} by {} (parked until they leave or a block edit flushes).",
                inst.pIdx, opened.pos(), player.getGameProfile().getName());
        if (!isGift(opened.itemCount(), after.itemCount())) return;
        cuePhoto(player, "left a gift in a drifting carriage");
        if (SharedCarriageGate.canContribute(player)) trigger(player, "drift_gift_left");
    }

    /**
     * Whether the contents went UP between open and close — i.e. the player left something behind.
     * {@code before} is null when no open was recorded, which can never count as a gift.
     */
    static boolean isGift(Integer before, int after) {
        return before != null && after > before;
    }

    /**
     * What a storage edit between open and close leads to.
     *
     * <ul>
     *   <li>{@link CloseAction#PARK} — the contents differ (giving, taking, swapping, rearranging):
     *       flagged locally. {@code SharedCarriageEnterEvents} releases it when the player leaves, and
     *       {@code SharedCarriageEvents} with the next block-edit flush, so a visit costs at most one
     *       relay delta. Taking items always travels on a carriage already on the relay — the next world
     *       must not be handed the same loot again (see {@code Instance.releaseParked}).</li>
     *   <li>{@link CloseAction#NONE} — nothing changed, or no open was recorded.</li>
     * </ul>
     */
    static CloseAction closeAction(Integer beforeCount, int afterCount, long beforeSig, long afterSig) {
        if (beforeCount == null) return CloseAction.NONE;
        return beforeSig != afterSig ? CloseAction.PARK : CloseAction.NONE;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        OPEN_CONTAINER.remove(event.getEntity().getUUID());
        LAST_CUE_MS.remove(event.getEntity().getUUID());
        DriftGenerosity.forget(event.getEntity().getUUID());
    }

    // ---------------- Helpers ----------------

    private static void trigger(ServerPlayer player, String actionId) {
        ModAdvancementTriggers.GAMEPLAY_ACTION.get().trigger(player, actionId);
    }

    // ---------------- Ride photo ----------------

    /**
     * Ask {@code player}'s client for a {@link SnapshotCue#BUILDING} ride photo, at most once per
     * {@link #CUE_THROTTLE_MS}.
     *
     * <p>The throttle is about the wire, not the photo: building means setting blocks several times
     * a second, and every one of them reaches {@code creditEdit}. The client's own per-tag cooldown
     * decides whether a shot is actually taken — this only stops a packet riding along with each
     * block. Deliberately generous: a builder's session yields a photo of the work, not a burst.</p>
     */
    private static void cuePhoto(ServerPlayer player, String reason) {
        long now = System.currentTimeMillis();
        if (!cueDue(LAST_CUE_MS.get(player.getUUID()), now)) return;
        LAST_CUE_MS.put(player.getUUID(), now);
        PacketDistributor.sendToPlayer(player, new SnapshotCuePacket(SnapshotCue.BUILDING, reason));
    }

    /**
     * Has {@code player}'s cue throttle elapsed? {@code lastMs} is null when they have not been cued
     * this session. A {@code now} before the last stamp means the wall clock moved backwards, which
     * counts as due rather than locking the player out until it catches up.
     */
    static boolean cueDue(Long lastMs, long nowMs) {
        return lastMs == null || nowMs < lastMs || nowMs - lastMs >= CUE_THROTTLE_MS;
    }
}
