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

    private final LongOpenHashSet positions;

    private PlacedRestTracker(LongOpenHashSet positions) {
        this.positions = positions;
    }

    public static PlacedRestTracker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(() -> new PlacedRestTracker(new LongOpenHashSet()),
                (tag, registries) -> load(tag)),
            NAME);
    }

    private static PlacedRestTracker load(CompoundTag tag) {
        LongOpenHashSet set = new LongOpenHashSet();
        for (long packed : tag.getLongArray(TAG_POSITIONS)) set.add(packed);
        return new PlacedRestTracker(set);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray(TAG_POSITIONS, positions.toLongArray());
        return tag;
    }

    /** Records the anchor, or both halves of the bed, whose block {@code state} stands at {@code pos}. */
    public void markPlaced(BlockPos pos, BlockState state) {
        boolean changed = positions.add(pos.asLong());
        if (state.getBlock() instanceof BedBlock) {
            changed |= positions.add(otherHalf(pos, state).asLong());
        }
        if (changed) setDirty();
    }

    /** Forgets the anchor, or both halves of the bed, whose block {@code state} stands at {@code pos}. */
    public void forget(BlockPos pos, BlockState state) {
        boolean changed = positions.remove(pos.asLong());
        if (state.getBlock() instanceof BedBlock) {
            changed |= positions.remove(otherHalf(pos, state).asLong());
        }
        if (changed) setDirty();
    }

    public boolean isPlayerPlaced(BlockPos pos) {
        return positions.contains(pos.asLong());
    }

    static BlockPos otherHalf(BlockPos pos, BlockState state) {
        return pos.relative(BedBlock.getConnectedDirection(state));
    }
}
