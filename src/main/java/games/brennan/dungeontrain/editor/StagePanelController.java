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

    private StagePanelController() {}

    /** Apply a {@link StagePanelEditPacket} op, with OP validation. Runs on the server thread. */
    public static void applyEdit(ServerPlayer player, StagePanelEditPacket packet) {
        if (!player.hasPermissions(2)) {
            actionBar(player, "Stage panel requires OP", ChatFormatting.RED);
            return;
        }
        switch (packet.op()) {
            case OPEN -> open(player, packet.stageId());
            case CLOSE -> close(player);
            case REPLACE_BLOCK -> replaceBlock(player, packet);
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

    private static void replaceBlock(ServerPlayer player, StagePanelEditPacket packet) {
        String stageId = packet.stageId() == null ? "" : packet.stageId().toLowerCase(Locale.ROOT);
        if (!stageId.equals(OPEN.get(player.getUUID()))) {
            actionBar(player, "Open the stage's panel first", ChatFormatting.YELLOW);
            return;
        }
        Optional<Block> from = blockById(packet.fromBlockId());
        Optional<Block> to = blockById(packet.toBlockId());
        if (from.isEmpty() || to.isEmpty()) {
            actionBar(player, "Unknown block: "
                + (from.isEmpty() ? packet.fromBlockId() : packet.toBlockId()), ChatFormatting.RED);
            return;
        }
        try {
            StageBlockReplacer.Result r = StageBlockReplacer.replaceAcrossStage(
                player.getServer().overworld(), stageId, from.get(), to.get());
            if (r.isEmpty()) {
                player.sendSystemMessage(Component.literal("Editor: no occurrences of "
                    + packet.fromBlockId() + " in stage '" + stageId + "'.")
                    .withStyle(ChatFormatting.YELLOW));
            } else {
                player.sendSystemMessage(Component.literal("Editor: replaced " + packet.fromBlockId()
                    + " → " + packet.toBlockId() + " across " + r.partsTouched().size()
                    + " part(s) (" + r.paletteStatesRewritten() + " structural, "
                    + r.sidecarStatesRewritten() + " variant state(s)). Plots re-stamped — unsaved "
                    + "plot edits were reset.").withStyle(ChatFormatting.GREEN));
            }
        } catch (IOException e) {
            LOGGER.warn("[DungeonTrain] Stage panel replace failed for '{}': {}", stageId, e.toString());
            actionBar(player, "Replace failed: " + e.getMessage(), ChatFormatting.RED);
        }
        // Data changed for everyone — refresh every open panel, not just the actor's.
        resyncAllOpen(player.getServer());
    }

    private static void toggleHideUnused(ServerPlayer player) {
        boolean active = EditorPartsStageFilter.toggle();
        ServerLevel overworld = player.getServer().overworld();
        if (EditorStampedCategoryState.current().orElse(null) == EditorCategory.CARRIAGES) {
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
            CarriagePartEditor.stampAllPlots(overworld, dims);
        }
        actionBar(player, active ? "Parts grid filtered to the focused stage" : "Parts grid filter off",
            active ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
        // The flag is global — reflect the new button state on every open panel.
        resyncAllOpen(player.getServer());
    }

    /** Compose + send the panel snapshot for {@code stageId} to {@code player}. */
    public static void sendSync(ServerPlayer player, String stageId) {
        ServerLevel overworld = player.getServer().overworld();
        CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();
        BlockPos anchor = EditorTypeMenus.stagePanelAnchor(dims);
        if (anchor == null) {
            close(player);
            return;
        }
        StageBlockIndex.StageBlocks blocks = StageBlockIndex.blocksForStage(overworld, stageId);
        String stageName = StageStore.get(stageId)
            .map(games.brennan.dungeontrain.template.Stage::name).orElse(stageId);

        List<String> aggregated = blocks.aggregatedBlockIds();
        List<String> cappedBlocks = aggregated.size() <= StageBlocksSyncPacket.BLOCKS_CAP
            ? aggregated
            : List.copyOf(aggregated.subList(0, StageBlocksSyncPacket.BLOCKS_CAP));

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
            cappedBlocks, parts, EditorPartsStageFilter.isActive()));
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

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        OPEN.clear();
    }
}
