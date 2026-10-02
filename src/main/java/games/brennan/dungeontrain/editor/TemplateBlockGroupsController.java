package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.TemplateBlockGroupsEditPacket;
import games.brennan.dungeontrain.net.TemplateBlockGroupsSyncPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side of the X editor's Blocks page: lists the blocks of the plot the player is standing in
 * as {@link TemplateBlockGroups} cells and re-skins one cell with the held block.
 *
 * <p>Each player keeps their cells for one plot until they save — {@link #onSaved} drops them, so
 * the next sync is one cell per block again. Counting matches the V template-blocks menu
 * ({@link TemplateBlocksMenuController}): structure blocks at plain positions, and each candidate
 * of every block-variant cell.</p>
 */
public final class TemplateBlockGroupsController {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record Session(String key, List<TemplateBlockGroups.Group<Block>> groups) {}

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private static final Comparator<Block> BY_ID =
        Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString());

    private TemplateBlockGroupsController() {}

    public static void forget(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    /** The player saved: their cells merge. Re-sent straight away if they had the page open. */
    public static void onSaved(ServerPlayer player) {
        if (SESSIONS.remove(player.getUUID()) != null) sync(player);
    }

    /** Re-send after an undo or redo changed the plot underneath the page. No-op when never opened. */
    public static void resyncOpen(ServerPlayer player) {
        if (SESSIONS.containsKey(player.getUUID())) sync(player);
    }

    public static void applyEdit(ServerPlayer player, TemplateBlockGroupsEditPacket packet) {
        if (!player.hasPermissions(2)) {
            LOGGER.warn("[DungeonTrain] Template block groups edit rejected: player {} not OP",
                player.getName().getString());
            return;
        }
        switch (packet.op()) {
            case REQUEST -> sync(player);
            case RESKIN -> reskin(player, packet.key(), packet.group());
        }
    }

    /** Reconcile this player's cells with the plot they stand in and send them; an empty key when in none. */
    private static void sync(ServerPlayer player) {
        BlockVariantPlot plot = plotOf(player);
        if (plot == null) {
            DungeonTrainNet.sendTo(player, TemplateBlockGroupsSyncPacket.none());
            return;
        }
        List<TemplateBlockGroups.Group<Block>> groups = currentGroups(player, plot);
        List<TemplateBlockGroupsSyncPacket.Entry> entries = new ArrayList<>(groups.size());
        for (TemplateBlockGroups.Group<Block> g : groups) {
            entries.add(new TemplateBlockGroupsSyncPacket.Entry(
                BuiltInRegistries.BLOCK.getKey(g.block()).toString(), g.count()));
        }
        DungeonTrainNet.sendTo(player, new TemplateBlockGroupsSyncPacket(plot.key(), entries));
    }

    /** The player's cells for {@code plot}, brought up to date with the world — built fresh for a new plot. */
    private static List<TemplateBlockGroups.Group<Block>> currentGroups(ServerPlayer player, BlockVariantPlot plot) {
        Map<TemplateBlockGroups.Member, Block> live = liveUses(player.serverLevel(), plot);
        Session old = SESSIONS.get(player.getUUID());
        List<TemplateBlockGroups.Group<Block>> groups = old != null && old.key().equals(plot.key())
            ? TemplateBlockGroups.reconcile(old.groups(), live)
            : TemplateBlockGroups.build(live, BY_ID);
        SESSIONS.put(player.getUUID(), new Session(plot.key(), groups));
        return groups;
    }

    /** Every use of a block in {@code plot} and what it is now. */
    private static Map<TemplateBlockGroups.Member, Block> liveUses(ServerLevel level, BlockVariantPlot plot) {
        Map<TemplateBlockGroups.Member, Block> live = new LinkedHashMap<>();
        BlockPos origin = plot.origin();
        Vec3i f = plot.footprint();
        Set<BlockPos> flagged = plot.allFlaggedPositions();
        BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
        for (int x = 0; x < f.getX(); x++) {
            for (int y = 0; y < f.getY(); y++) {
                for (int z = 0; z < f.getZ(); z++) {
                    local.set(x, y, z);
                    if (flagged.contains(local)) continue;
                    BlockState state = level.getBlockState(origin.offset(x, y, z));
                    if (state.isAir()) continue;
                    live.put(TemplateBlockGroups.Member.base(local), state.getBlock());
                }
            }
        }
        for (BlockPos flaggedLocal : flagged) {
            List<VariantState> states = plot.statesAt(flaggedLocal);
            if (states == null) continue;
            for (int i = 0; i < states.size(); i++) {
                VariantState vs = states.get(i);
                if (vs.isMob() || CarriageVariantBlocks.isEmptyPlaceholder(vs.state())) continue;
                live.put(TemplateBlockGroups.Member.variant(flaggedLocal, i), vs.state().getBlock());
            }
        }
        return live;
    }

    private static void reskin(ServerPlayer player, String key, int group) {
        BlockVariantPlot plot = plotOf(player);
        if (plot == null || !plot.key().equals(key)) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.block_groups.stand_in_template"), ChatFormatting.YELLOW);
            sync(player);
            return;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof BlockItem blockItem)) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.hold_block_to_replace"), ChatFormatting.YELLOW);
            return;
        }
        List<TemplateBlockGroups.Group<Block>> groups = currentGroups(player, plot);
        if (group < 0 || group >= groups.size()) {
            sync(player);
            return;
        }
        TemplateBlockGroups.Group<Block> target = groups.get(group);
        Block newBlock = blockItem.getBlock();
        if (newBlock == target.block()) {
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.block_groups.already_block", newBlock.getName()), ChatFormatting.YELLOW);
            return;
        }
        CompoundTag heldBeNbt = heldBlockEntityNbt(held, newBlock);
        ServerLevel level = player.serverLevel();
        // One click, one undo step — structure blocks and variant pools together.
        EditorRegionDiff.record(player, "Block swap", plot.key(),
            () -> CarriageStampGuard.run(() -> rewrite(player, level, plot, target, newBlock, heldBeNbt)));
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && session.key().equals(plot.key())) {
            SESSIONS.put(player.getUUID(),
                new Session(plot.key(), TemplateBlockGroups.reskin(session.groups(), group, newBlock)));
        }
        actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.block_groups.replaced", target.count(), target.block().getName(), newBlock.getName()), ChatFormatting.GREEN);
        sync(player);
    }

    @Nullable
    private static CompoundTag heldBlockEntityNbt(ItemStack held, Block newBlock) {
        if (!newBlock.defaultBlockState().hasBlockEntity()) return null;
        CustomData data = held.get(DataComponents.BLOCK_ENTITY_DATA);
        return data == null ? null : data.copyTag();
    }

    /** Write {@code newBlock} over every use in {@code target}, keeping each one's orientation. */
    private static void rewrite(ServerPlayer player, ServerLevel level, BlockVariantPlot plot,
                                TemplateBlockGroups.Group<Block> target, Block newBlock,
                                @Nullable CompoundTag heldBeNbt) {
        BlockPos origin = plot.origin();
        Map<BlockPos, List<Integer>> variantIndices = new HashMap<>();
        for (TemplateBlockGroups.Member m : target.members()) {
            if (m.isVariant()) {
                variantIndices.computeIfAbsent(m.local(), k -> new ArrayList<>()).add(m.variantIndex());
                continue;
            }
            BlockPos worldPos = origin.offset(m.local());
            BlockState old = level.getBlockState(worldPos);
            if (old.getBlock() != target.block()) continue;
            SilentBlockOps.setBlockSilentNoCascade(level, worldPos,
                TemplateBlocksMenuController.transferProperties(old, newBlock), heldBeNbt);
        }
        if (variantIndices.isEmpty()) return;
        variantIndices.forEach((local, indices) -> rewriteVariantCell(level, plot, local, indices,
            target.block(), newBlock, heldBeNbt));
        try {
            plot.save();
        } catch (IOException e) {
            LOGGER.error("[DungeonTrain] Template block groups save failed for {}: {}", plot.key(), e.toString());
            actionBar(player, Component.translatable("chat.dungeontrain.editor_bar.common.save_failed", e.getClass().getSimpleName()), ChatFormatting.RED);
        }
    }

    /** Re-skin the listed candidates of one variant cell, and the block standing there if it shows one of them. */
    private static void rewriteVariantCell(ServerLevel level, BlockVariantPlot plot, BlockPos local,
                                           List<Integer> indices, Block oldBlock, Block newBlock,
                                           @Nullable CompoundTag heldBeNbt) {
        List<VariantState> states = plot.statesAt(local);
        if (states == null) return;
        List<VariantState> rebuilt = new ArrayList<>(states);
        for (int i : indices) {
            if (i < 0 || i >= rebuilt.size()) continue;
            VariantState vs = rebuilt.get(i);
            if (vs.isMob() || vs.state().getBlock() != oldBlock) continue;
            rebuilt.set(i, vs.withState(TemplateBlocksMenuController.transferProperties(vs.state(), newBlock), heldBeNbt));
        }
        plot.put(local, rebuilt);
        // The block on show is one candidate's preview — keep it in step when it is one we changed.
        boolean stillShown = rebuilt.stream().anyMatch(vs -> !vs.isMob() && vs.state().getBlock() == oldBlock);
        BlockPos worldPos = plot.origin().offset(local);
        BlockState shown = level.getBlockState(worldPos);
        if (shown.getBlock() == oldBlock && !stillShown) {
            SilentBlockOps.setBlockSilentNoCascade(level, worldPos,
                TemplateBlocksMenuController.transferProperties(shown, newBlock), heldBeNbt);
        }
    }

    @Nullable
    private static BlockVariantPlot plotOf(ServerPlayer player) {
        CarriageDims dims = DungeonTrainWorldData.get(player.serverLevel()).dims();
        return BlockVariantPlot.resolveAt(player, dims);
    }

    private static void actionBar(ServerPlayer player, Component text, ChatFormatting colour) {
        player.displayClientMessage(text.copy().withStyle(colour), true);
    }
}
