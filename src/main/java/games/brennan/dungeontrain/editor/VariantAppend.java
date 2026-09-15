package games.brennan.dungeontrain.editor;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The item-to-variant capture and the "append to a cell's pool" rule, shared by every path that
 * adds a variant from something in the author's hand.
 *
 * <p>Pulled out of {@link VariantBlockInteractions}, which authors one cell per right-click, so
 * that a bulk author — {@link games.brennan.dungeontrain.compat.EffortlessBuildingVariants},
 * which appends to every cell an Effortless Building shape covers — applies exactly the same
 * seeding, cap and orientation rules. Nothing here touches a sidecar or the event bus: callers
 * decide where the resulting pool goes and how a rejection is shown.</p>
 */
public final class VariantAppend {

    /** Soft cap — commands can write more, but the hand-authoring paths stop here to keep feedback readable. */
    public static final int MAX_VARIANTS_PER_POSITION = 16;

    private VariantAppend() {}

    /**
     * Outcome of {@link #append}: either the new candidate list for the cell, or the reason the
     * add was refused, in the words the right-click path has always shown the author.
     */
    public record Result(@Nullable List<VariantState> pool, @Nullable String rejectMessage) {
        public boolean accepted() { return pool != null; }

        static Result of(List<VariantState> pool) { return new Result(pool, null); }
        static Result reject(String message) { return new Result(null, message); }
    }

    /**
     * Derive the {@link VariantState} the held stack contributes, or {@code null} when the item
     * is not one that authors a variant (an empty hand, a tool, an empty bucket, …) — in which
     * case the caller should fall through to whatever the item ordinarily does.
     *
     * <p>Block items go through a {@link BlockPlaceContext} built on {@code hit}, so directional
     * blocks (stairs, logs, doors, repeaters, …) get the facing vanilla would have placed. Their
     * block-entity NBT is read from the stack's {@code BLOCK_ENTITY_DATA} (vanilla Ctrl+Pick
     * stamps this), falling back to the block already at {@code clicked} when it is the same
     * block — the "edited a chest in-world, now copy it to a variant" loop. Spawn eggs become mob
     * entries; filled buckets contribute the fluid's <b>source</b> state.</p>
     */
    public static @Nullable VariantState captureHeld(ServerLevel level, ServerPlayer player, InteractionHand hand,
                                                     ItemStack held, BlockHitResult hit, BlockPos clicked) {
        if (held.isEmpty()) return null;
        if (held.getItem() instanceof BlockItem blockItem) {
            return captureBlock(level, player, hand, blockItem, held, hit, clicked);
        }
        if (held.getItem() instanceof SpawnEggItem egg) {
            return captureMob(egg, held, player);
        }
        BlockState bucketSource = VariantLiquids.sourceStateFrom(held);
        if (bucketSource != null) {
            // Liquids have no directional properties and no block entity, so there is nothing
            // to orient or carry: NONE rotation, null NBT.
            return new VariantState(bucketSource, null, 1, VariantRotation.NONE);
        }
        return null;
    }

    private static VariantState captureBlock(ServerLevel level, ServerPlayer player, InteractionHand hand,
                                             BlockItem blockItem, ItemStack held, BlockHitResult hit,
                                             BlockPos clicked) {
        BlockPlaceContext ctx = new BlockPlaceContext(new UseOnContext(level, player, hand, held, hit));
        BlockState newState = blockItem.getBlock().getStateForPlacement(ctx);
        if (newState == null) newState = blockItem.getBlock().defaultBlockState();

        CompoundTag beNbt = null;
        if (newState.hasBlockEntity()) {
            CustomData heldData = held.get(DataComponents.BLOCK_ENTITY_DATA);
            beNbt = heldData == null ? null : heldData.copyTag();
            if (beNbt == null) {
                BlockState clickedState = level.getBlockState(clicked);
                if (clickedState.is(newState.getBlock())) {
                    BlockEntity be = level.getBlockEntity(clicked);
                    if (be != null) beNbt = be.saveWithoutMetadata(level.registryAccess());
                }
            }
        }
        return new VariantState(newState, beNbt, 1, RotationApplier.lockToCurrent(newState));
    }

    /**
     * Build a mob {@link VariantState} from a vanilla spawn egg. The entry's {@code state} is
     * auto-stamped to the COMMAND_BLOCK sentinel by the canonical constructor, so existing
     * applier branches AIR the cell at spawn and a parallel entity pass spawns the mob. Entity
     * NBT from the egg's {@code EntityTag} (anvil-rename / NBT-give) is preserved so a tagged
     * egg round-trips into the variant.
     */
    private static @Nullable VariantState captureMob(SpawnEggItem egg, ItemStack held, ServerPlayer player) {
        EntityType<?> type = egg.getType(held);
        if (type == null) {
            player.displayClientMessage(
                Component.literal("Spawn egg has no entity type — cannot add as variant.")
                    .withStyle(ChatFormatting.YELLOW), true);
            return null;
        }
        ResourceLocation eid = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (eid == null) {
            player.displayClientMessage(
                Component.literal("Cannot resolve entity id for spawn egg.")
                    .withStyle(ChatFormatting.YELLOW), true);
            return null;
        }
        // Vanilla stores the spawn payload in BLOCK_ENTITY_DATA on the egg (ID:"<entity>" plus
        // CustomName / Tags / ArmorItems…); the ID is stripped since the entity type is the
        // authoritative source and EntityType.create writes its own.
        CompoundTag mobNbt = null;
        CustomData beData = held.get(DataComponents.BLOCK_ENTITY_DATA);
        if (beData != null && !beData.isEmpty()) {
            CompoundTag raw = beData.copyTag();
            if (raw.contains("EntityTag", Tag.TAG_COMPOUND)) {
                mobNbt = raw.getCompound("EntityTag").copy();
            } else if (!raw.isEmpty()) {
                mobNbt = raw;
            }
            if (mobNbt != null) mobNbt.remove("id");
        }
        return VariantState.ofMob(eid, mobNbt, 1, VariantRotation.NONE);
    }

    /**
     * Capture the cell's existing block as a {@link VariantState} so the first add seeds the
     * candidate list with {@code [base, added]}. Block-entity payloads are read from the world so
     * an authored chest / sign / banner round-trips with its contents. Returns {@code null} for
     * air — {@link #append} turns that into the "place a base block first" rejection.
     *
     * <p>A liquid base is normalised to its source state: a captured {@code level=3} flow has
     * nothing feeding it once stamped into a carriage and would drain to air.</p>
     */
    public static @Nullable VariantState captureBase(ServerLevel level, BlockPos pos, BlockState rawBaseState) {
        if (rawBaseState.isAir()) return null;
        BlockState baseState = VariantLiquids.toSource(rawBaseState);
        CompoundTag beNbt = null;
        if (baseState.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) beNbt = be.saveWithoutMetadata(level.registryAccess());
        }
        return new VariantState(baseState, beNbt, 1, RotationApplier.lockToCurrent(baseState));
    }

    /**
     * The append rule: seed with the base block on first edit (or with the empty-space sentinel
     * when a mob is added to an air cell, so the picker can roll between "stay air" and "spawn
     * mob"), enforce the soft cap, and orient a block entry against its predecessors so its
     * facing matches the most recent existing block with a direction — the same behaviour as
     * the world-space block-variant menu's ADD.
     *
     * <p>Duplicates are allowed — appending the same state twice gives it 2× weight.</p>
     */
    public static Result append(@Nullable List<VariantState> existing, @Nullable VariantState baseVariant,
                                VariantState newVariant, BlockState baseState) {
        List<VariantState> updated = new ArrayList<>();
        if (existing == null) {
            if (baseVariant == null || baseState.isAir()) {
                if (!newVariant.isMob()) {
                    return Result.reject("Target block is air — place a base block first.");
                }
                updated.add(VariantState.of(Blocks.COMMAND_BLOCK.defaultBlockState()));
            } else {
                updated.add(baseVariant);
            }
        } else {
            if (existing.size() >= MAX_VARIANTS_PER_POSITION) {
                return Result.reject("Variant list full (" + MAX_VARIANTS_PER_POSITION
                    + ") — clear or reset this position.");
            }
            updated.addAll(existing);
        }
        // Mob entries skip orientation: their rotation field is a Y-rot at spawn rather than a
        // block FACING, and the state field is the sentinel anyway.
        if (newVariant.isMob()) {
            updated.add(newVariant);
            return Result.of(updated);
        }
        RotationApplier.OrientedState oriented =
            RotationApplier.orientToPredecessors(newVariant.state(), updated);
        updated.add(new VariantState(
            oriented.state(), newVariant.blockEntityNbt(),
            newVariant.weight(), oriented.rotation()));
        return Result.of(updated);
    }

    /** Short label for feedback lines: registry id, plus "(mob)" / "(empty-space)" / "(+nbt)" as they apply. */
    public static String label(VariantState added) {
        if (added.isMob()) {
            return added.entityId() + " (mob)" + (added.hasBlockEntityData() ? " (+nbt)" : "");
        }
        ResourceLocation name = BuiltInRegistries.BLOCK.getKey(added.state().getBlock());
        if (CarriageVariantBlocks.isEmptyPlaceholder(added.state())) return name + " (empty-space)";
        return name + (added.hasBlockEntityData() ? " (+nbt)" : "");
    }
}
