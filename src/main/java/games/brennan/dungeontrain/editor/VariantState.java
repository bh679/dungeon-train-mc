package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * One entry in a v2 variants sidecar: a {@link BlockState} plus optional
 * {@link CompoundTag} {@code BlockEntity} payload. Pure data — equality is by
 * value so the picker / overlay / list code can compare entries safely.
 *
 * <p>The v1 sidecar format only carried a {@code BlockState}; v2 promotes that
 * to {@code (state, blockEntityNbt)} so block-entity blocks (chests, signs,
 * banners, bookshelves, …) can round-trip with their NBT contents. Entries
 * without {@code BlockEntity} data have {@code blockEntityNbt == null} and
 * serialize as bare BlockState strings — keeping v1 files diff-clean after a
 * no-op resave.</p>
 */
public record VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt) {

    public VariantState {
        if (state == null) throw new IllegalArgumentException("state");
        if (blockEntityNbt != null && blockEntityNbt.isEmpty()) {
            blockEntityNbt = null;
        }
        if (blockEntityNbt != null) {
            // Defensive copy so callers can't mutate the entry's NBT after handoff.
            blockEntityNbt = blockEntityNbt.copy();
        }
    }

    /** State-only constructor — equivalent to a v1 entry. */
    public static VariantState of(BlockState state) {
        return new VariantState(state, null);
    }

    public boolean hasBlockEntityData() {
        return blockEntityNbt != null;
    }
}
