package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.net.DungeonTrainNet;
import games.brennan.dungeontrain.net.StageBlocksSyncPacket;
import games.brennan.dungeontrain.net.StagePanelEditPacket;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side driver for the Stage Blocks panel (the "stage V menu") — the sibling billboard
 * beside the Stages panel that shows a stage's aggregated blocks + per-part strips and hosts the
 * replace / hide-unused actions. Mirrors {@link BlockVariantMenuController}'s
 * toggle/applyEdit/sendSync shape with a per-player {@code OPEN} map.
 *
 * <p><b>Deliberate divergence</b> from the block-variant menu: no
 * {@link BlockVariantPlot#resolveAt} plot check — the Stages panel sits at the editor door
 * outside any plot, and its existing gate edits already run plot-free via
 * {@code /dt editor stage …}. OP≥2 plus an actively-stamped editor category is the gate.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class StagePanelController {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Player → the stage id their panel is open on (lowercased). */
    private static final Map<UUID, String> OPEN = new ConcurrentHashMap<>();

    /**
     * The {@link StageBlockIndex#generation()} the open panels were last synced at — the same
     * change signal the row strips key on, so a part save / sidecar edit / stage duplicate made
     * OUTSIDE the panel (in a plot, or via chat commands) refreshes every open panel too, not
     * just the two panel-op paths. Checked once per level tick by {@link #resyncIfStale}.
     */
    private static volatile long lastSyncedGeneration = -1;

    private StagePanelController() {}

    /** Resync every open panel when the stage-blocks index has changed since the last sync. */
    public static void resyncIfStale(MinecraftServer server) {
        long gen = StageBlockIndex.generation();
        if (gen == lastSyncedGeneration) return;
        lastSyncedGeneration = gen;
        resyncAllOpen(server);
    }

    /** Apply a {@link StagePanelEditPacket} op, with OP validation. Runs on the server thread. */
    public static void applyEdit(ServerPlayer player, StagePanelEditPacket packet) {
        if (!player.hasPermissions(2)) {
            actionBar(player, "Stage panel requires OP", ChatFormatting.RED);
            return;
        }
        switch (packet.op()) {
            case OPEN -> open(player, packet.stageId());
            case CLOSE -> close(player);
            case SWAP_BLOCK -> swapBlock(player, packet);
            case TOGGLE_HIDE_UNUSED -> toggleHideUnused(player);
        }
    }

    private static void open(ServerPlayer player, String rawStageId) {
        String stageId = rawStageId == null ? "" : rawStageId.toLowerCase(Locale.ROOT);
        if (!StageStore.exists(stageId)) {
            actionBar(player, "No such stage: " + rawStageId, ChatFormatting.YELLOW);
            return;
        }
        if (EditorStampedCategoryState.current().isEmpty()) {
            actionBar(player, "Enter an editor category first", ChatFormatting.YELLOW);
            return;
        }
        OPEN.put(player.getUUID(), stageId);
        sendSync(player, stageId);
    }

    private static void close(ServerPlayer player) {
        OPEN.remove(player.getUUID());
        DungeonTrainNet.sendTo(player, StageBlocksSyncPacket.closed());
    }

    /**
     * Auto-open the panel for {@code player} on {@code stageId} (the stage-select hook) — silent
     * no-op when the stage is gone or no editor category is stamped, so a command-block / console
     * {@code stage select} doesn't spam or NPE. Unlike the {@code OPEN} packet op it emits no
     * "enter a category first" hint (selection can happen outside CARRIAGES).
     */
    public static void openFor(ServerPlayer player, String stageId) {
        if (player == null) return;
        String id = stageId == null ? "" : stageId.toLowerCase(Locale.ROOT);
        if (!StageStore.exists(id) || EditorStampedCategoryState.current().isEmpty()) return;
        OPEN.put(player.getUUID(), id);
        sendSync(player, id);
    }

    /** Close the panel for {@code player} (the stage-deselect hook). No-op when none is open. */
    public static void closeFor(ServerPlayer player) {
        if (player == null || !OPEN.containsKey(player.getUUID())) return;
        close(player);
    }

    /**
     * Stage-wide swap of the clicked block with the player's <b>held block</b>, orientation-
     * preserving — the "click to replace from hand" flow (reuses #636's held-item capture, applied
     * across every part the stage uses via {@link StageBlockReplacer}). No target-from-list, no
     * confirm: a single click swaps.
     */
    private static void swapBlock(ServerPlayer player, StagePanelEditPacket packet) {
        String stageId = packet.stageId() == null ? "" : packet.stageId().toLowerCase(Locale.ROOT);
        if (!stageId.equals(OPEN.get(player.getUUID()))) {
            actionBar(player, "Open the stage's panel first", ChatFormatting.YELLOW);
            return;
        }
        // Editor-presence gate (the OPEN map isn't cleared on editor exit) — never rewrite part files
        // for a player who has left the editor. Mirrors open()'s stamped-category check.
        if (EditorStampedCategoryState.current().isEmpty()) {
            actionBar(player, "Enter an editor category first", ChatFormatting.YELLOW);
            return;
        }
        Optional<Block> from = blockById(packet.blockId());
        if (from.isEmpty()) {
            actionBar(player, "Unknown block: " + packet.blockId(), ChatFormatting.RED);
            return;
        }
        // Replacement comes from the hand (mirrors #636's SWAP_BLOCK) — must be a placeable block.
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (held.isEmpty() || !(held.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) {
            actionBar(player, "Hold a block to replace with", ChatFormatting.YELLOW);
            return;
        }
        Block to = blockItem.getBlock();
        if (to == from.get()) {
            actionBar(player, "Held block is the same as the selected block", ChatFormatting.YELLOW);
            return;
        }
        net.minecraft.nbt.CompoundTag heldBeNbt = null;
        if (to.defaultBlockState().hasBlockEntity()) {
            net.minecraft.world.item.component.CustomData data =
                held.get(net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA);
            heldBeNbt = data == null ? null : data.copyTag();
        }
        String fromId = packet.blockId();
        String toId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(to).toString();
        try {
            StageBlockReplacer.Result r = StageBlockReplacer.replaceAcrossStage(
                player.getServer().overworld(), stageId, from.get(), to, heldBeNbt);
            if (r.isEmpty()) {
                player.sendSystemMessage(Component.literal("Editor: no occurrences of " + fromId
                    + " in stage '" + stageId + "'.").withStyle(ChatFormatting.YELLOW));
            } else {
                player.sendSystemMessage(Component.literal("Editor: swapped " + fromId + " → " + toId
                    + " across " + r.partsTouched().size() + " part(s) ("
                    + r.paletteStatesRewritten() + " structural, " + r.sidecarStatesRewritten()
                    + " variant state(s)). Plots re-stamped — unsaved plot edits were reset.")
                    .withStyle(ChatFormatting.GREEN));
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Stage panel swap failed for '{}': {}", stageId, e.toString());
            actionBar(player, "Swap failed: " + e.getMessage(), ChatFormatting.RED);
        }
        // Data changed for everyone — refresh every open panel, not just the actor's.
        resyncAllOpen(player.getServer());
    }

    private static void toggleHideUnused(ServerPlayer player) {
        boolean active = EditorPartsStageFilter.toggle();
        // Bulk-set per-part visibility from the toggle: ON snapshots the focused stage's parts as
        // the only displayed ones; OFF displays all. Manual per-part checkboxes and new parts stay
        // put afterward — the snapshot only fires on this toggle.
        if (active) {
            EditorPartVisibility.hideUnused(EditorStageSelection.effective());
        } else {
            EditorPartVisibility.showAll();
        }
        ServerLevel overworld = player.getServer().overworld();
        if (EditorStampedCategoryState.current().orElse(null) == EditorCategory.CARRIAGES) {
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
            CarriagePartEditor.stampAllPlots(overworld, dims);
        }
        actionBar(player, active ? "Parts grid showing only the focused stage's parts"
                : "Parts grid showing all parts",
            active ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        // The flag is global — reflect the new button state on every open panel.
        resyncAllOpen(player.getServer());
    }

    /** Compose + send the panel snapshot for {@code stageId} to {@code player}. */
    public static void sendSync(ServerPlayer player, String stageId) {
        // The editor category was cleared (editor exit) — the panel has no context; close it.
        if (EditorStampedCategoryState.current().isEmpty()) {
            close(player);
            return;
        }
        ServerLevel overworld = player.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos anchor = EditorTypeMenus.stagePanelAnchor(dims);
        if (anchor == null) {
            close(player);
            return;
        }
        StageBlockIndex.StageBlocks blocks = StageBlockIndex.blocksForStage(overworld, stageId);
        // Display names are unbounded (greedyString rename) — clamp to the wire budget so a long
        // name can never desync the client (decode allows 256; header text truncates fine).
        String stageName = StageStore.get(stageId)
            .map(games.brennan.dungeontrain.template.Stage::name).orElse(stageId);
        if (stageName.length() > 64) stageName = stageName.substring(0, 64);

        List<StageBlockIndex.BlockUse> aggregated = blocks.aggregated();
        List<StageBlocksSyncPacket.BlockCount> cappedBlocks = new ArrayList<>();
        int blockLimit = Math.min(aggregated.size(), StageBlocksSyncPacket.BLOCKS_CAP);
        for (int i = 0; i < blockLimit; i++) {
            StageBlockIndex.BlockUse u = aggregated.get(i);
            cappedBlocks.add(new StageBlocksSyncPacket.BlockCount(u.blockId(), u.count()));
        }

        List<StageBlocksSyncPacket.PartEntry> parts = new ArrayList<>(blocks.parts().size());
        for (StageBlockIndex.PartBlocks pb : blocks.parts()) {
            List<String> ids = pb.blockIds();
            List<String> capped = ids.size() <= StageBlocksSyncPacket.PART_STRIP_CAP
                ? ids
                : List.copyOf(ids.subList(0, StageBlocksSyncPacket.PART_STRIP_CAP));
            parts.add(new StageBlocksSyncPacket.PartEntry(
                (byte) pb.part().kind().ordinal(), pb.part().name(), capped, ids.size()));
        }

        DungeonTrainNet.sendTo(player, new StageBlocksSyncPacket(true, stageId, stageName, anchor,
            cappedBlocks, aggregated.size(), parts, EditorPartsStageFilter.isActive()));
    }

    /** Re-send the snapshot to every player with an open panel (shared data changed). */
    public static void resyncAllOpen(MinecraftServer server) {
        if (server == null) return;
        for (Map.Entry<UUID, String> e : OPEN.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) {
                OPEN.remove(e.getKey());
                continue;
            }
            if (StageStore.exists(e.getValue())) {
                sendSync(p, e.getValue());
            } else {
                close(p);
            }
        }
    }

    /** Close the panel for every player showing {@code stageId} — the stage-deleted hook. */
    public static void closeForStage(MinecraftServer server, String stageId) {
        if (server == null || stageId == null) return;
        String key = stageId.toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, String> e : OPEN.entrySet()) {
            if (!key.equals(e.getValue())) continue;
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p != null) {
                close(p);
            } else {
                OPEN.remove(e.getKey());
            }
        }
    }

    private static Optional<Block> blockById(String id) {
        ResourceLocation loc = ResourceLocation.tryParse(id == null ? "" : id);
        return loc == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(loc);
    }

    private static void actionBar(ServerPlayer player, String message, ChatFormatting color) {
        player.displayClientMessage(Component.literal(message).withStyle(color), true);
    }

        public static void onServerStopped(net.minecraft.server.MinecraftServer server) {
        OPEN.clear();
        lastSyncedGeneration = -1;
    }

    /** Drop the disconnecting player's open-panel entry (the client resets via LoggingOut). */
    @SubscribeEvent
    public static void onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        OPEN.remove(event.getEntity().getUUID());
    }
}
