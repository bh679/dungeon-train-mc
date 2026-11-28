package games.brennan.dungeontrain.portal.tribute;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an owner builds in their own tribute room while it stands on the train, and saving it to their
 * room ({@link TributeRoomStore}).
 *
 * <p>Per standing room (by pair key): the cells the owner placed since it stood, which are the only ones
 * they may take out again ({@code TributeRoomGuard}), and the cells changed since the last save. Both are
 * room-local offsets, so they hold across the room being re-stamped further down the line.</p>
 *
 * <p>A save is a delta: the changed cells are read from the level here, then folded onto the room as
 * it is stored <em>now</em>, on the store's IO thread. A death visit saved in between keeps everything
 * it built, and nothing the train dressed the room with (the tuned TV, the heads, the button) is ever
 * written back. Saved a few seconds after the last change, and at once when the owner dies or leaves,
 * the room goes, or the server stops.</p>
 */
public final class TributeRoomLiveEdits {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Quiet time after the last change before the room is saved. */
    static final int SAVE_DELAY_TICKS = 100;
    private static final int CHECK_INTERVAL_TICKS = 20;

    /** One standing room's edits. Sets are concurrent: written on the server thread, never shared further. */
    private static final class Edits {
        final String owner;
        final Set<BlockPos> placed = ConcurrentHashMap.newKeySet();
        final Set<BlockPos> pending = ConcurrentHashMap.newKeySet();
        volatile String ownerName = "";
        volatile long lastChangeTick;

        Edits(String owner) {
            this.owner = owner;
        }
    }

    private static final Map<Integer, Edits> EDITS = new ConcurrentHashMap<>();

    private TributeRoomLiveEdits() {}

    // ---- the rule's facts ------------------------------------------------------

    /** Whether {@code player} may build in {@code standing}: it is their room, stood from the key their run saves under. */
    public static boolean isOwner(ServerLevel level, ServerPlayer player, TributeRoomRegistry.Standing standing) {
        return standing != null && !standing.owner().isEmpty()
            && standing.owner().equals(TributeRoomStore.dashless(player.getUUID()))
            && standing.key().equals(TributeRoomKeys.keyFor(level, player));
    }

    /** Whether the owner placed the block at {@code pos} in {@code standing} since it stood. */
    public static boolean placedByOwner(TributeRoomRegistry.Standing standing, BlockPos pos) {
        Edits edits = EDITS.get(standing.pairKey());
        return edits != null && edits.placed.contains(offsetOf(standing, pos));
    }

    // ---- recording -------------------------------------------------------------

    /** The owner placed a block at {@code pos}: theirs to take out again, and saved. */
    public static void recordPlaced(ServerPlayer player, TributeRoomRegistry.Standing standing, BlockPos pos) {
        Edits edits = editsFor(standing, player);
        if (edits == null) return;
        BlockPos offset = offsetOf(standing, pos);
        edits.placed.add(offset);
        edits.pending.add(offset);
    }

    /** The owner took out a block they placed: no longer theirs to take, and its removal is saved. */
    public static void recordBroken(ServerPlayer player, TributeRoomRegistry.Standing standing, BlockPos pos) {
        Edits edits = editsFor(standing, player);
        if (edits == null) return;
        BlockPos offset = offsetOf(standing, pos);
        edits.placed.remove(offset);
        edits.pending.add(offset);
    }

    private static Edits editsFor(TributeRoomRegistry.Standing standing, ServerPlayer player) {
        if (standing.owner().isEmpty()) return null;
        Edits edits = EDITS.compute(standing.pairKey(),
            (k, e) -> e != null && e.owner.equals(standing.owner()) ? e : new Edits(standing.owner()));
        edits.ownerName = player.getGameProfile().getName();
        edits.lastChangeTick = player.getServer() == null ? 0 : player.getServer().getTickCount();
        return edits;
    }

    private static BlockPos offsetOf(TributeRoomRegistry.Standing standing, BlockPos pos) {
        return pos.subtract(standing.origin());
    }

    // ---- structure hooks -------------------------------------------------------

    /** The pair was stamped again: the same owner's room keeps its edits, anything else starts afresh. */
    static void onRoomChanged(int pairKey, String owner) {
        Edits edits = EDITS.get(pairKey);
        if (edits != null && !edits.owner.equals(owner)) EDITS.remove(pairKey);
    }

    /** The pair's room is about to be erased: save what is unsaved while it can still be read, then forget it. */
    static void onRoomGone(int pairKey) {
        TributeRoomRegistry.Standing standing = TributeRoomRegistry.byPair(pairKey);
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (standing != null && server != null) flush(server, standing);
        EDITS.remove(pairKey);
    }

    // ---- saving ----------------------------------------------------------------

    /** Every second: save each room left alone for {@link #SAVE_DELAY_TICKS}. */
    public static void tick(MinecraftServer server) {
        if (EDITS.isEmpty() || server.getTickCount() % CHECK_INTERVAL_TICKS != 0) return;
        for (Map.Entry<Integer, Edits> entry : EDITS.entrySet()) {
            Edits edits = entry.getValue();
            if (edits.pending.isEmpty() || server.getTickCount() - edits.lastChangeTick < SAVE_DELAY_TICKS) continue;
            TributeRoomRegistry.Standing standing = TributeRoomRegistry.byPair(entry.getKey());
            if (standing != null) flush(server, standing);
        }
    }

    /** Save every room {@code owner} has unsaved edits in now: they died or left. */
    public static void flushOwner(MinecraftServer server, UUID owner) {
        if (EDITS.isEmpty()) return;
        String id = TributeRoomStore.dashless(owner);
        for (Map.Entry<Integer, Edits> entry : EDITS.entrySet()) {
            if (!entry.getValue().owner.equals(id)) continue;
            TributeRoomRegistry.Standing standing = TributeRoomRegistry.byPair(entry.getKey());
            if (standing != null) flush(server, standing);
        }
    }

    /** Server stopping: queue every unsaved room. The caller waits on the store afterwards. */
    public static void flushAll(MinecraftServer server) {
        for (Integer pairKey : EDITS.keySet()) {
            TributeRoomRegistry.Standing standing = TributeRoomRegistry.byPair(pairKey);
            if (standing != null) flush(server, standing);
        }
    }

    public static void clear() {
        EDITS.clear();
    }

    /**
     * Read {@code standing}'s changed cells (server thread) and queue the fold onto the stored room. A
     * read that fails puts the cells back, so the next pass tries again.
     */
    private static void flush(MinecraftServer server, TributeRoomRegistry.Standing standing) {
        Edits edits = EDITS.get(standing.pairKey());
        if (edits == null || edits.pending.isEmpty() || !edits.owner.equals(standing.owner())) return;
        ServerLevel level = server.getLevel(standing.dimension());
        if (level == null) return;
        Set<BlockPos> cells = Set.copyOf(edits.pending);
        edits.pending.removeAll(cells);
        CompoundTag delta;
        try {
            delta = CarriageBlockSnapshot.captureLevelCells(level, standing.origin(), standing.size(), cells, 0).tag();
        } catch (Throwable t) {
            edits.pending.addAll(cells);
            LOGGER.warn("[DungeonTrain] tribute room: could not read {}'s edits on the train: {}", edits.ownerName, t.toString());
            return;
        }
        String owner = edits.owner, key = standing.key(), name = edits.ownerName;
        int count = cells.size();
        TributeRoomStore.supplyIo(() -> {
            Optional<TributeRoomBlob> stored = TributeRoomStore.loadByOwner(owner, key);
            if (stored.isEmpty()) {
                LOGGER.warn("[DungeonTrain] tribute room: {} built in their room on the train, but it has no save under '{}' to add to",
                    name, key);
                return false;
            }
            TributeRoomBlob base = stored.get();
            TributeRoomBlob next = base.saved(fold(base.snapshot(), delta),
                name.isBlank() ? base.ownerName() : name, System.currentTimeMillis(), key);
            TributeRoomStore.saveAsync(next);
            LOGGER.info("[DungeonTrain] tribute room: {}'s {} edit(s) on the train saved (rev {})", next.ownerName(), count, next.revision());
            return true;
        });
    }

    /**
     * {@code delta}'s cells folded onto {@code base}, the base's entities kept: a live room holds mobs,
     * drops and riders that are not the owner's build. Only the base's cells change; everything else in it
     * (entities, size, version) is kept exactly as stored. Pure; neither tag is changed.
     */
    static CompoundTag fold(CompoundTag base, CompoundTag delta) {
        CompoundTag cellsOnly = delta.copy();
        cellsOnly.remove("ents");
        CompoundTag out = base.copy();
        out.put("cells", CarriageBlockSnapshot.applyDeltaCells(base, cellsOnly).getList("cells", Tag.TAG_COMPOUND));
        return out;
    }
}
