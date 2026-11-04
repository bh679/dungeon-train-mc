package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TemplateBlocksEditPacket;
import games.brennan.dungeontrain.net.TemplateBlocksSyncPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import static games.brennan.dungeontrain.editor.EditorFeedback.actionBar;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Server-side driver for the template-blocks world-space menu. Opens on the
 * plot the player is standing in (via {@link BlockVariantPlot#resolveAt}),
 * lists every block used across the plot — base structure blocks plus
 * block-variant candidates — with usage counts, and lets the player preview
 * or bulk-swap a block (keeping orientation) with whatever is in their hand.
 *
 * <p>Companion to {@link BlockVariantMenuController} (which edits a single
 * cell). This menu operates on the whole plot at once so an author can copy
 * an existing model and re-skin every block while keeping the same shape.</p>
 *
 * <p>Persistence mirrors the block-variant menu: swapped <b>base structure</b>
 * blocks are mutated in-world and captured by the ordinary editor Save's
 * {@code fillFromWorld}; swapped <b>variant</b> candidates are written to the
 * sidecar immediately via {@link BlockVariantPlot#save()}.</p>
 */
public final class TemplateBlocksMenuController extends PlayerMenuController<TemplateBlocksMenuController.OpenMenu> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double TOGGLE_REACH = 8.0;

    /** Per-player anchor of the currently-open panel, reused on re-sync so it doesn't jump after a swap. */
    record OpenMenu(String key, Vec3 anchor, Vec3 right, Vec3 up) {}

    public static final TemplateBlocksMenuController INSTANCE = new TemplateBlocksMenuController();

    private TemplateBlocksMenuController() {
        super("TemplateBlocksMenu", "template blocks");
    }

    @Override
    protected String requiresOpKey() {
        return "chat.dungeontrain.editor_bar.template_blocks.requires_op";
    }

    @Override
    protected String keyOf(OpenMenu state) {
        return state.key();
    }

    /** The plot changed behind the controller's back (undo / redo): re-send while the player is still in it. */
    @Override
    protected void resync(ServerPlayer player, OpenMenu open) {
        CarriageDims dims = DungeonTrainWorldData.get(player.serverLevel()).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null || !plot.key().equals(open.key())) return;
        sendSync(player, plot);
    }

    public static void toggle(ServerPlayer player, boolean open) {
        if (!open) {
            INSTANCE.remove(player);
            DungeonTrainNet.sendTo(player, TemplateBlocksSyncPacket.empty());
            return;
        }
        if (!INSTANCE.requireOp(player)) return;

        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();

        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.not_in_plot"), ChatFormatting.YELLOW);
            return;
        }

        OpenMenu anchor = computeAnchor(player);
        INSTANCE.put(player, new OpenMenu(plot.key(), anchor.anchor(), anchor.right(), anchor.up()));
        sendSync(player, plot);
    }

    /**
     * Anchor the panel on the looked-at block face when the player is looking
     * at one, else float it ~2 blocks ahead of the player, billboarded to
     * face them. Returns an {@link OpenMenu} carrying only the anchor basis
     * (its {@code key} is a placeholder overwritten by the caller).
     */
    private static OpenMenu computeAnchor(ServerPlayer player) {
        HitResult hit = player.pick(TOGGLE_REACH, 1.0f, false);
        if (hit instanceof BlockHitResult bhit && bhit.getType() != HitResult.Type.MISS) {
            Direction face = bhit.getDirection();
            BlockPos worldPos = bhit.getBlockPos();
            Vec3 normal = MenuPanelGeometry.normalOf(face);
            Vec3 anchor = MenuPanelGeometry.faceAnchor(worldPos, face);
            Vec3 up = MenuPanelGeometry.computeUp(normal, player);
            return new OpenMenu("", anchor, MenuPanelGeometry.right(up, normal), up);
        }
        // Free anchor — face the player.
        Vec3 look = player.getViewVector(1.0f).normalize();
        Vec3 anchor = player.getEyePosition().add(look.scale(2.0));
        Vec3 normal = look.reverse();
        Vec3 up = MenuPanelGeometry.computeUp(normal, player);
        return new OpenMenu("", anchor, MenuPanelGeometry.right(up, normal), up);
    }

    /** Build the block list for {@code plot} and push it to the player at the stored anchor. */
    private static void sendSync(ServerPlayer player, BlockVariantPlot plot) {
        OpenMenu open = INSTANCE.open(player);
        if (open == null) return;
        List<TemplateBlocksSyncPacket.Entry> entries = buildBlockList(player.serverLevel(), plot);
        DungeonTrainNet.sendTo(player, new TemplateBlocksSyncPacket(
            plot.key(), true, open.anchor(), open.right(), open.up(), entries));
    }

    /**
     * Tally every block used in the plot. Positions <b>without</b> a variant
     * entry contribute their live world block (air skipped); positions
     * <b>with</b> a variant entry contribute each candidate block (mob /
     * empty-placeholder sentinels skipped) instead of the current preview
     * block, so nothing is double-counted. Sorted by count desc, then id asc.
     */
    private static List<TemplateBlocksSyncPacket.Entry> buildBlockList(ServerLevel level, BlockVariantPlot plot) {
        Map<Block, Integer> counts = new LinkedHashMap<>();
        BlockPos origin = plot.origin();
        Vec3i f = plot.footprint();
        Set<BlockPos> flagged = plot.allFlaggedPositions();

        // Base structure — non-variant positions.
        BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
        for (int x = 0; x < f.getX(); x++) {
            for (int y = 0; y < f.getY(); y++) {
                for (int z = 0; z < f.getZ(); z++) {
                    local.set(x, y, z);
                    if (flagged.contains(local)) continue;
                    BlockState state = level.getBlockState(origin.offset(x, y, z));
                    if (state.isAir()) continue;
                    counts.merge(state.getBlock(), 1, Integer::sum);
                }
            }
        }
        // Variant candidates.
        for (BlockPos flaggedLocal : flagged) {
            List<VariantState> states = plot.statesAt(flaggedLocal);
            if (states == null) continue;
            for (VariantState vs : states) {
                if (vs.isMob()) continue;
                if (CarriageVariantBlocks.isEmptyPlaceholder(vs.state())) continue;
                counts.merge(vs.state().getBlock(), 1, Integer::sum);
            }
        }

        List<TemplateBlocksSyncPacket.Entry> entries = new ArrayList<>(counts.size());
        for (Map.Entry<Block, Integer> e : counts.entrySet()) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(e.getKey());
            entries.add(new TemplateBlocksSyncPacket.Entry(id.toString(), e.getValue()));
        }
        entries.sort((a, b) -> {
            int byCount = Integer.compare(b.count(), a.count());
            return byCount != 0 ? byCount : a.blockId().compareTo(b.blockId());
        });
        return entries;
    }

    /** Apply a {@link TemplateBlocksEditPacket}, with OP + plot validation. */
    public static void applyEdit(ServerPlayer player, TemplateBlocksEditPacket packet) {
        if (INSTANCE.authorise(player, packet.key()) == null) return;
        ServerLevel level = player.serverLevel();
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BlockVariantPlot plot = BlockVariantPlot.resolveAt(player, dims);
        if (plot == null || !plot.key().equals(packet.key())) {
            LOGGER.warn("[DungeonTrain] TemplateBlocksMenu edit rejected: player {} not in plot for '{}'",
                player.getName().getString(), packet.key());
            return;
        }
        INSTANCE.armUndo(player);
        Block target = resolveBlock(packet.blockId());
        if (target == null) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.unknown_block", packet.blockId()), ChatFormatting.YELLOW);
            return;
        }

        switch (packet.op()) {
            case PREVIEW_BLOCK -> previewBlock(level, plot, target);
            case SWAP_BLOCK -> {
                // A reskin rewrites base blocks and variant pools together, and
                // the author means it as one action — so record it as one step.
                EditorRegionDiff.record(player, "Block swap", plot.key(),
                    () -> swapBlock(player, level, plot, target));
                sendSync(player, plot);
            }
        }
    }

    /**
     * Show {@code target} in-world at every variant cell that lists it as a
     * candidate. Transient world-only effect — the sidecar is unchanged.
     */
    private static void previewBlock(ServerLevel level, BlockVariantPlot plot, Block target) {
        // Guarded: a preview rewrite must not pulse an observer facing the cell (ObserverBlockStampMixin).
        CarriageStampGuard.run(() -> previewBlockGuarded(level, plot, target));
    }

    private static void previewBlockGuarded(ServerLevel level, BlockVariantPlot plot, Block target) {
        BlockPos origin = plot.origin();
        for (BlockPos flaggedLocal : plot.allFlaggedPositions()) {
            List<VariantState> states = plot.statesAt(flaggedLocal);
            if (states == null) continue;
            for (VariantState vs : states) {
                if (vs.isMob()) continue;
                if (vs.state().getBlock() != target) continue;
                SilentBlockOps.setBlockSilentNoCascade(
                    level, origin.offset(flaggedLocal), vs.state(), vs.blockEntityNbt());
                break;
            }
        }
    }

    /**
     * Replace every occurrence of {@code target} — base structure blocks and
     * block-variant candidates — with the player's held block, preserving
     * orientation. Base blocks change in-world; variant sidecars are saved
     * immediately.
     */
    private static void swapBlock(ServerPlayer player, ServerLevel level, BlockVariantPlot plot, Block target) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.template_blocks.hold_block_to_swap"), ChatFormatting.YELLOW);
            return;
        }
        Block newBlock = blockItem.getBlock();
        if (newBlock == target) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.held_block_same"), ChatFormatting.YELLOW);
            return;
        }
        boolean newHasBe = newBlock.defaultBlockState().hasBlockEntity();
        CompoundTag heldBeNbt = null;
        if (newHasBe) {
            CustomData heldData = held.get(DataComponents.BLOCK_ENTITY_DATA);
            heldBeNbt = heldData == null ? null : heldData.copyTag();
        }

        BlockPos origin = plot.origin();
        Vec3i f = plot.footprint();
        Set<BlockPos> flagged = plot.allFlaggedPositions();
        int swapped = 0;

        // Base structure — non-variant positions.
        for (int x = 0; x < f.getX(); x++) {
            for (int y = 0; y < f.getY(); y++) {
                for (int z = 0; z < f.getZ(); z++) {
                    BlockPos local = new BlockPos(x, y, z);
                    if (flagged.contains(local)) continue;
                    BlockPos worldPos = origin.offset(x, y, z);
                    BlockState old = level.getBlockState(worldPos);
                    if (old.getBlock() != target) continue;
                    BlockState reskinned = transferProperties(old, newBlock);
                    SilentBlockOps.setBlockSilentNoCascade(level, worldPos, reskinned, heldBeNbt);
                    swapped++;
                }
            }
        }

        // Variant candidates.
        boolean variantsDirty = false;
        for (BlockPos flaggedLocal : flagged) {
            List<VariantState> states = plot.statesAt(flaggedLocal);
            if (states == null) continue;
            List<VariantState> rebuilt = new ArrayList<>(states.size());
            boolean cellDirty = false;
            for (VariantState vs : states) {
                if (!vs.isMob() && vs.state().getBlock() == target) {
                    BlockState reskinned = transferProperties(vs.state(), newBlock);
                    rebuilt.add(vs.withState(reskinned, heldBeNbt));
                    cellDirty = true;
                    swapped++;
                } else {
                    rebuilt.add(vs);
                }
            }
            if (cellDirty) {
                plot.put(flaggedLocal, rebuilt);
                variantsDirty = true;
            }
        }
        if (variantsDirty) {
            try {
                plot.save();
            } catch (IOException e) {
                LOGGER.error("[DungeonTrain] TemplateBlocksMenu swap save failed for {}: {}",
                    plot.key(), e.toString());
                actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.save_failed", e.getClass().getSimpleName()), ChatFormatting.RED);
                return;
            }
        }

        if (swapped == 0) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.template_blocks.no_occurrences"), ChatFormatting.YELLOW);
        } else {
            actionBar(player, swapped == 1
                    ? Component.translatable("chat.dungeontrain.editor_bar.template_blocks.swapped_one", swapped)
                    : Component.translatable("chat.dungeontrain.editor_bar.template_blocks.swapped_many", swapped),
                ChatFormatting.GREEN);
        }
    }

    /**
     * Build a {@code newBlock} state carrying {@code from}'s property values
     * wherever the two blocks share a property (facing, half, axis, slab
     * type …), so a swap keeps the original orientation/shape. Properties the
     * new block lacks fall back to its defaults.
     */
    static BlockState transferProperties(BlockState from, Block newBlock) {
        BlockState result = newBlock.defaultBlockState();
        for (Property<?> p : from.getProperties()) {
            result = copyProperty(result, from, p);
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState to, BlockState from, Property<T> p) {
        if (!to.hasProperty(p)) return to;
        return to.setValue(p, from.getValue(p));
    }

    @Nullable
    private static Block resolveBlock(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null) return null;
        Block block = BuiltInRegistries.BLOCK.get(id);
        // BuiltInRegistries.BLOCK.get returns AIR for unknown ids — reject that
        // unless the caller genuinely meant air (which we never list).
        if (block == net.minecraft.world.level.block.Blocks.AIR && !id.getPath().equals("air")) return null;
        return block;
    }

}
