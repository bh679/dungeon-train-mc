package games.brennan.dungeontrain.nap;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The beds (both halves) and respawn anchors a <b>player placed</b> in one level, by packed block
 * position. A bed or anchor is a natural nap spot exactly when it is not in here ({@link NapEligibility}):
 * world generation, carriage and room stamping never pass through {@code BlockEvent.EntityPlaceEvent},
 * players always do.
 *
 * <p>Breaking a natural bed and placing its item again therefore makes it a player-made bed, which is
 * Brennan's "broken or player made beds won't work". Carriage beds live in Sable plot space, whose
 * block coordinates don't move with the train, so the one set covers ground and carriage beds.</p>
 *
 * <p>Entries are dropped when the bed is broken ({@link NapBedEvents}). One left behind by a bed
 * that vanished another way can only make a later natural bed at that exact spot look player-made,
 * which costs a prompt, never grants one.</p>
 */
public final class PlacedRestTracker extends SavedData {

    public static final String NAME = "dungeontrain_placed_beds";
    private static final String TAG_POSITIONS = "positions";
    private static final String TAG_MOVED = "moved";

    /** How a player-placed spot came to be. */
    public enum Kind {
        /** Placed from a bed or anchor item the player made or found. */
        MADE,
        /** Placed from a bed item a natural bed dropped: the train's own bed, moved. */
        MOVED
    }

    private final LongOpenHashSet positions;
    private final LongOpenHashSet moved;

    private PlacedRestTracker(LongOpenHashSet positions, LongOpenHashSet moved) {
        this.positions = positions;
        this.moved = moved;
    }

    public static PlacedRestTracker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(() -> new PlacedRestTracker(new LongOpenHashSet(), new LongOpenHashSet()),
                (tag, registries) -> load(tag)),
            NAME);
    }

    private static PlacedRestTracker load(CompoundTag tag) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (long packed : tag.getLongArray(TAG_POSITIONS)) set.add(packed);
        LongOpenHashSet movedSet = new LongOpenHashSet();
        for (long packed : tag.getLongArray(TAG_MOVED)) movedSet.add(packed);
        return new PlacedRestTracker(set, movedSet);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray(TAG_POSITIONS, positions.toLongArray());
        tag.putLongArray(TAG_MOVED, moved.toLongArray());
        return tag;
    }

    /** Records the anchor, or both halves of the bed, whose block {@code state} stands at {@code pos}. */
    public void markPlaced(BlockPos pos, BlockState state, Kind kind) {
        for (BlockPos p : spots(pos, state)) {
            positions.add(p.asLong());
            if (kind == Kind.MOVED) moved.add(p.asLong());
            else moved.remove(p.asLong());
        }
        setDirty();
    }

    /** Forgets the anchor, or both halves of the bed, whose block {@code state} stands at {@code pos}. */
    public void forget(BlockPos pos, BlockState state) {
        boolean changed = false;
        for (BlockPos p : spots(pos, state)) {
            changed |= positions.remove(p.asLong());
            moved.remove(p.asLong());
        }
        if (changed) setDirty();
    }

    public boolean isPlayerPlaced(BlockPos pos) {
        return positions.contains(pos.asLong());
    }

    /** How the spot at {@code pos} was placed, or null when no player placed it (natural). */
    public Kind kindAt(BlockPos pos) {
        if (!positions.contains(pos.asLong())) return null;
        return moved.contains(pos.asLong()) ? Kind.MOVED : Kind.MADE;
    }

    private static BlockPos[] spots(BlockPos pos, BlockState state) {
        return state.getBlock() instanceof BedBlock
            ? new BlockPos[] {pos, otherHalf(pos, state)}
            : new BlockPos[] {pos};
    }

    static BlockPos otherHalf(BlockPos pos, BlockState state) {
        return pos.relative(BedBlock.getConnectedDirection(state));
    }
}
