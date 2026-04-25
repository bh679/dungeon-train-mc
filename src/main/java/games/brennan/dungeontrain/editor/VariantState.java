package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * One entry in a v3 variants sidecar: a {@link BlockState} plus optional
 * {@link CompoundTag} {@code BlockEntity} payload, plus a per-entry
 * {@code weight} (default 1) and {@code locked} flag (default false). Pure
 * data — equality is by value so the picker / overlay / list code can
 * compare entries safely.
 *
 * <p>Schema history:
 * <ul>
 *   <li>v1 — bare BlockState string only.</li>
 *   <li>v2 — promoted to {@code (state, blockEntityNbt)} so block-entity
 *       blocks (chests, signs, banners, bookshelves, …) can round-trip.</li>
 *   <li>v3 — adds {@code weight} (≥1) and {@code locked} (boolean) for
 *       weighted picking and a hard pin to one variant. v1 / v2 entries load
 *       with {@code weight=1, locked=false}; the writer emits weight/locked
 *       only when they differ from the default, keeping older files
 *       diff-clean on no-op resave.</li>
 * </ul></p>
 *
 * <p>Lock semantics (see {@link CarriageVariantBlocks#resolve}): if any
 * entry in a cell is {@code locked}, the picker short-circuits and returns
 * the first locked entry. Otherwise it does a weighted seeded pick using
 * the per-entry {@code weight}.</p>
 */
public record VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight, boolean locked) {

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
     * Two-arg overload for v2-era callers — defaults {@code weight=1,
     * locked=false}. Kept so the existing capture-and-append flow in
     * {@link VariantBlockInteractions} compiles unchanged; new authoring
     * paths should pass weight / locked explicitly via the canonical
     * constructor.
     */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt) {
        this(state, blockEntityNbt, 1, false);
    }

    /** State-only constructor — equivalent to a v1 entry (weight 1, unlocked, no NBT). */
    public static VariantState of(BlockState state) {
        return new VariantState(state, null, 1, false);
    }

    public boolean hasBlockEntityData() {
        return blockEntityNbt != null;
    }

    /** True when any of {NBT, non-default weight, locked} is set — drives the JSON object/string-form choice. */
    public boolean isPlainBareString() {
        return blockEntityNbt == null && weight == 1 && !locked;
    }

    /** Return a copy with {@code weight} replaced (clamped ≥ 1 by the canonical constructor). */
    public VariantState withWeight(int newWeight) {
        return new VariantState(state, blockEntityNbt, newWeight, locked);
    }

    /** Return a copy with {@code locked} replaced. */
    public VariantState withLocked(boolean newLocked) {
        return new VariantState(state, blockEntityNbt, weight, newLocked);
    }
}
