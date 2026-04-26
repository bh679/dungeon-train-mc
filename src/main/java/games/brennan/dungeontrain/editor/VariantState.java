package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * One entry in a v4 variants sidecar: a {@link BlockState} plus optional
 * {@link CompoundTag} {@code BlockEntity} payload, plus a per-entry
 * {@code weight} (default 1, ≥ 1). Pure data — equality is by value so
 * the picker / overlay / list code can compare entries safely.
 *
 * <p>Schema history:
 * <ul>
 *   <li>v1 — bare BlockState string only.</li>
 *   <li>v2 — promoted to {@code (state, blockEntityNbt)} so block-entity
 *       blocks (chests, signs, banners, bookshelves, …) can round-trip.</li>
 *   <li>v3 — added per-entry {@code weight} for weighted picking and a
 *       per-entry {@code locked} flag (since dropped — locking moved to
 *       the cell level via the per-cell lockId map; a v3 file with
 *       {@code locked:true} entries loads with the flag silently
 *       ignored).</li>
 *   <li>v4 — adds a per-cell {@code lockId} stored alongside the
 *       state list. Per-entry weight stays here; lock semantics live on
 *       the cell, not the entry.</li>
 * </ul></p>
 */
public record VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight) {

    public VariantState {
        if (state == null) throw new IllegalArgumentException("state");
        if (weight < 1) weight = 1;
        if (blockEntityNbt != null && blockEntityNbt.isEmpty()) {
            blockEntityNbt = null;
        }
        if (blockEntityNbt != null) {
            // Defensive copy so callers can't mutate the entry's NBT after handoff.
            blockEntityNbt = blockEntityNbt.copy();
        }
    }

    /**
     * Two-arg overload for v2-era callers — defaults {@code weight=1}.
     * Kept so the existing capture-and-append flow in
     * {@link VariantBlockInteractions} compiles unchanged.
     */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt) {
        this(state, blockEntityNbt, 1);
    }

    /** State-only constructor — equivalent to a v1 entry (weight 1, no NBT). */
    public static VariantState of(BlockState state) {
        return new VariantState(state, null, 1);
    }

    public boolean hasBlockEntityData() {
        return blockEntityNbt != null;
    }

    /** True when the entry has no extras over v1 — drives bare-string vs object-form JSON serialisation. */
    public boolean isPlainBareString() {
        return blockEntityNbt == null && weight == 1;
    }

    /** Return a copy with {@code weight} replaced (clamped ≥ 1 by the canonical constructor). */
    public VariantState withWeight(int newWeight) {
        return new VariantState(state, blockEntityNbt, newWeight);
    }
}
