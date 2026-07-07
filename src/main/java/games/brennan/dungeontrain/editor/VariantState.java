package games.brennan.dungeontrain.editor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.jetbrains.annotations.Nullable;

/**
 * One entry in a v7 variants sidecar: a {@link BlockState} plus optional
 * {@link CompoundTag} {@code BlockEntity} payload, plus a per-entry
 * {@code weight} (default 1, ≥ 1), plus a per-entry {@link VariantRotation}
 * (default {@link VariantRotation#NONE}), plus an optional per-entry
 * {@code linkedLootPrefabId} pointing into {@code LootPrefabStore}, plus an
 * optional per-entry {@code entityId} for mob spawn entries.
 *
 * <p>When {@code entityId != null}, the entry is a <b>mob entry</b>: at spawn
 * time the picker treats {@code state} as the empty-placeholder sentinel
 * (cell becomes AIR) and a separate entity pass spawns the mob. The
 * {@code blockEntityNbt} field is reused as the optional <b>entity NBT</b>
 * (custom name, equipment, tags). The constructor force-stamps {@code state}
 * to the COMMAND_BLOCK sentinel so every existing applier site routes the
 * cell through the AIR branch automatically.</p>
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
 *   <li>v7 — adds an optional per-entry {@code entityId} for mob spawn
 *       entries. When set, the cell rolls AIR at spawn and a deferred
 *       entity pass creates the mob (subject to the existing 48-block
 *       player-distance gate). v6 files load with {@code entityId == null}
 *       and re-save diff-clean.</li>
 *   <li>v8 — adds an optional per-entry {@link VariantDifficulty} band
 *       ({@code min} / {@code max} difficulty tier) consulted only for mob
 *       entries: at spawn the cell drops out-of-band eggs from its candidate
 *       pool before the weighted pick. The default band {@code (0, ALL)} is
 *       omitted from JSON, so v7 files load and re-save diff-clean.</li>
 * </ul></p>
 */
public record VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight,
                           VariantRotation rotation, @Nullable String linkedLootPrefabId,
                           @Nullable ResourceLocation entityId, VariantHalf half,
                           VariantDifficulty difficulty) {

    public VariantState {
        if (entityId != null) {
            // Mob entries always sit in the AIR-via-sentinel lane so existing
            // applier branches clear the cell without any new branch.
            state = Blocks.COMMAND_BLOCK.defaultBlockState();
        }
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
        if (half == null) half = VariantHalf.NONE;
        if (difficulty == null) difficulty = VariantDifficulty.NONE;
    }

    /** Seven-arg overload defaulting {@code difficulty} to {@link VariantDifficulty#NONE}. */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight,
                        VariantRotation rotation, @Nullable String linkedLootPrefabId,
                        @Nullable ResourceLocation entityId, VariantHalf half) {
        this(state, blockEntityNbt, weight, rotation, linkedLootPrefabId, entityId, half, VariantDifficulty.NONE);
    }

    /** Six-arg overload defaulting {@code half} to {@link VariantHalf#NONE}. */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight,
                        VariantRotation rotation, @Nullable String linkedLootPrefabId,
                        @Nullable ResourceLocation entityId) {
        this(state, blockEntityNbt, weight, rotation, linkedLootPrefabId, entityId, VariantHalf.NONE);
    }

    /** Five-arg overload defaulting {@code entityId} to {@code null} (block entry). */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight,
                        VariantRotation rotation, @Nullable String linkedLootPrefabId) {
        this(state, blockEntityNbt, weight, rotation, linkedLootPrefabId, null, VariantHalf.NONE);
    }

    /** Four-arg overload defaulting {@code linkedLootPrefabId} and {@code entityId} to {@code null}. */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight, VariantRotation rotation) {
        this(state, blockEntityNbt, weight, rotation, null, null, VariantHalf.NONE);
    }

    /** Three-arg overload defaulting rotation to {@link VariantRotation#NONE}. */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt, int weight) {
        this(state, blockEntityNbt, weight, VariantRotation.NONE, null, null, VariantHalf.NONE);
    }

    /**
     * Two-arg overload for v2-era callers — defaults {@code weight=1} and
     * rotation to {@link VariantRotation#NONE}. Kept so the existing
     * capture-and-append flow in {@link VariantBlockInteractions} compiles
     * unchanged.
     */
    public VariantState(BlockState state, @Nullable CompoundTag blockEntityNbt) {
        this(state, blockEntityNbt, 1, VariantRotation.NONE, null, null, VariantHalf.NONE);
    }

    /** State-only constructor — equivalent to a v1 entry (weight 1, no NBT, default rotation, no link). */
    public static VariantState of(BlockState state) {
        return new VariantState(state, null, 1, VariantRotation.NONE, null, null, VariantHalf.NONE);
    }

    /**
     * Mob-entry factory. The {@code state} field is auto-set to the
     * COMMAND_BLOCK sentinel by the canonical constructor so existing
     * applier branches AIR the cell. {@code entityNbt} carries optional
     * mob NBT (custom name, equipment, tags) — pass {@code null} for a
     * default mob.
     *
     * @param entityId  Registry id of the entity type to spawn (e.g.
     *                  {@code minecraft:zombie}). Required.
     * @param entityNbt Optional NBT applied via
     *                  {@link net.minecraft.world.entity.EntityType#create}.
     * @param weight    Weighted-pick weight (≥ 1).
     * @param rotation  Y-rotation at spawn (rotation field reused for mobs).
     */
    public static VariantState ofMob(ResourceLocation entityId, @Nullable CompoundTag entityNbt,
                                      int weight, VariantRotation rotation) {
        if (entityId == null) throw new IllegalArgumentException("entityId");
        return new VariantState(Blocks.COMMAND_BLOCK.defaultBlockState(),
            entityNbt, weight, rotation, null, entityId, VariantHalf.NONE);
    }

    public boolean hasBlockEntityData() {
        return blockEntityNbt != null;
    }

    /** True when this entry was added from a saved loot-prefab item. */
    public boolean hasLootPrefabLink() {
        return linkedLootPrefabId != null;
    }

    /** True when this entry spawns a mob instead of placing a block. */
    public boolean isMob() {
        return entityId != null;
    }

    /** True when the entry has no extras over v1 — drives bare-string vs object-form JSON serialisation. */
    public boolean isPlainBareString() {
        return blockEntityNbt == null && weight == 1 && rotation.isDefault()
            && linkedLootPrefabId == null && entityId == null && half.isDefault()
            && difficulty.isDefault();
    }

    /** Return a copy with {@code weight} replaced (clamped ≥ 1 by the canonical constructor). */
    public VariantState withWeight(int newWeight) {
        return new VariantState(state, blockEntityNbt, newWeight, rotation, linkedLootPrefabId, entityId, half, difficulty);
    }

    /** Return a copy with {@code rotation} replaced. */
    public VariantState withRotation(VariantRotation newRotation) {
        return new VariantState(state, blockEntityNbt, weight, newRotation, linkedLootPrefabId, entityId, half, difficulty);
    }

    /** Return a copy with {@code linkedLootPrefabId} replaced ({@code null} clears the link). */
    public VariantState withLinkedLootPrefabId(@Nullable String newLinkedLootPrefabId) {
        return new VariantState(state, blockEntityNbt, weight, rotation, newLinkedLootPrefabId, entityId, half, difficulty);
    }

    /** Return a copy with {@code half} replaced. */
    public VariantState withHalf(VariantHalf newHalf) {
        return new VariantState(state, blockEntityNbt, weight, rotation, linkedLootPrefabId, entityId, newHalf, difficulty);
    }

    /** Return a copy with {@code difficulty} replaced (only meaningful for mob entries). */
    public VariantState withDifficulty(VariantDifficulty newDifficulty) {
        return new VariantState(state, blockEntityNbt, weight, rotation, linkedLootPrefabId, entityId, half, newDifficulty);
    }

    /**
     * Return a copy with {@code state} (and its {@code blockEntityNbt})
     * replaced, keeping every other field (weight, rotation, half,
     * difficulty, loot link, entity id) intact. Used by the template
     * block-swap menu to re-skin a candidate while preserving its authored
     * spawn behaviour. Passing a {@code newBeNbt} of {@code null} clears any
     * block-entity payload the old block carried (the new block rarely shares
     * its NBT shape).
     */
    public VariantState withState(BlockState newState, @Nullable CompoundTag newBeNbt) {
        return new VariantState(newState, newBeNbt, weight, rotation, linkedLootPrefabId, entityId, half, difficulty);
    }
}
