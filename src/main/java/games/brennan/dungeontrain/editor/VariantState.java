package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * One entry in a v6 variants sidecar: a {@link BlockState} plus optional
 * {@link CompoundTag} {@code BlockEntity} payload, plus a per-entry
 * {@code weight} (default 1, ≥ 1), plus a per-entry {@link VariantRotation}
 * (default {@link VariantRotation#NONE}), plus an optional per-entry
 * {@code linkedLootPrefabId} pointing into {@code LootPrefabStore}.
 * Pure data — equality is by value so the picker / overlay / list code can
 * compare entries safely.
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
 *   <li>v5 — adds a per-entry {@link VariantRotation} so each candidate
 *       can declare a fixed / random / options-restricted facing applied
 *       at spawn time. Default rotation is omitted from JSON so v3/v4
 *       files round-trip diff-clean.</li>
 *   <li>v6 — adds an optional per-entry {@code lootPrefab} string. When
 *       set, the entry was added from a saved loot-prefab item and the
 *       editor shows the prefab id as the row label; at spawn time, the
 *       linked prefab's pool rolls the container's contents, overriding
 *       the cell-level link in {@code ContainerContentsStore}. v5 files
 *       load with {@code linkedLootPrefabId == null} and re-save
 *       diff-clean.</li>
 * </ul></p>
 */
public record VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight,
                           VariantRotation rotation, @Nullable String linkedLootPrefabId) {

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
        if (rotation == null) rotation = VariantRotation.NONE;
        if (linkedLootPrefabId != null && linkedLootPrefabId.isEmpty()) {
            linkedLootPrefabId = null;
        }
    }

    /** Four-arg overload defaulting {@code linkedLootPrefabId} to {@code null}. */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight, VariantRotation rotation) {
        this(state, blockEntityNbt, weight, rotation, null);
    }

    /** Three-arg overload defaulting rotation to {@link VariantRotation#NONE}. */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight) {
        this(state, blockEntityNbt, weight, VariantRotation.NONE, null);
    }

    /**
     * Two-arg overload for v2-era callers — defaults {@code weight=1} and
     * rotation to {@link VariantRotation#NONE}. Kept so the existing
     * capture-and-append flow in {@link VariantBlockInteractions} compiles
     * unchanged.
     */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt) {
        this(state, blockEntityNbt, 1, VariantRotation.NONE, null);
    }

    /** State-only constructor — equivalent to a v1 entry (weight 1, no NBT, default rotation, no link). */
    public static VariantState of(BlockState state) {
        return new VariantState(state, null, 1, VariantRotation.NONE, null);
    }

    public boolean hasBlockEntityData() {
        return blockEntityNbt != null;
    }

    /** True when this entry was added from a saved loot-prefab item. */
    public boolean hasLootPrefabLink() {
        return linkedLootPrefabId != null;
    }

    /** True when the entry has no extras over v1 — drives bare-string vs object-form JSON serialisation. */
    public boolean isPlainBareString() {
        return blockEntityNbt == null && weight == 1 && rotation.isDefault() && linkedLootPrefabId == null;
    }

    /** Return a copy with {@code weight} replaced (clamped ≥ 1 by the canonical constructor). */
    public VariantState withWeight(int newWeight) {
        return new VariantState(state, blockEntityNbt, newWeight, rotation, linkedLootPrefabId);
    }

    /** Return a copy with {@code rotation} replaced. */
    public VariantState withRotation(VariantRotation newRotation) {
        return new VariantState(state, blockEntityNbt, weight, newRotation, linkedLootPrefabId);
    }

    /** Return a copy with {@code linkedLootPrefabId} replaced ({@code null} clears the link). */
    public VariantState withLinkedLootPrefabId(@Nullable String newLinkedLootPrefabId) {
        return new VariantState(state, blockEntityNbt, weight, rotation, newLinkedLootPrefabId);
    }
}
