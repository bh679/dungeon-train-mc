package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.CarriageContentsEditor;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.PillarEditor;
import games.brennan.dungeontrain.editor.TunnelEditor;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.Locale;
import java.util.Optional;

/**
 * Client → server: a Save / Reset / Clear click on a floating editor plot
 * panel. Carries the (category, modelId, modelName) so the server can target
 * the correct plot directly without needing the player to first walk into
 * it. Mirrors the same backend methods the keyboard menu's slash-command
 * dispatch ends up calling, so the behaviour is identical to running
 * {@code /dungeontrain editor save} while standing inside the plot.
 *
 * <p>OP-only — server-side permission check matches the slash-command
 * permission level (2). A non-OP player click is silently dropped.</p>
 */
public record EditorPlotActionPacket(
    String category,
    String modelId,
    String modelName,
    Action action
) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Permission level required for editor commands — matches EditorCommand.requiresPermissions. */
    private static final int PERMISSION_LEVEL = 2;

    /**
     * {@link #ENTER_INSIDE} dispatches the per-category {@code enter(...,
     * onTop=false)} method so the player teleports to the floor of the plot
     * (under the cage). The default {@code enter} now lands on top — this
     * action is the explicit "go inside" companion driven by the per-plot
     * panel's Enter button.
     */
    public enum Action { SAVE, RESET, CLEAR, ENTER_INSIDE }

    public static final Type<EditorPlotActionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "editor_plot_action"));

    public static final StreamCodec<FriendlyByteBuf, EditorPlotActionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            EditorPlotActionPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(category, 32);
        buf.writeUtf(modelId, 64);
        buf.writeUtf(modelName, 64);
        buf.writeVarInt(action.ordinal());
    }

    public static EditorPlotActionPacket decode(FriendlyByteBuf buf) {
        String category = buf.readUtf(32);
        String modelId = buf.readUtf(64);
        String modelName = buf.readUtf(64);
        int idx = buf.readVarInt();
        Action[] all = Action.values();
        Action action = idx >= 0 && idx < all.length ? all[idx] : Action.SAVE;
        return new EditorPlotActionPacket(category, modelId, modelName, action);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EditorPlotActionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (!(p instanceof ServerPlayer sender)) return;
            if (!sender.hasPermissions(PERMISSION_LEVEL)) {
                LOGGER.debug("[DungeonTrain] EditorPlotAction: dropping non-OP click from {}",
                    sender.getName().getString());
                return;
            }
            MinecraftServer server = sender.getServer();
            if (server == null) return;
            ServerLevel overworld = server.overworld();
            CarriageDims dims = DungeonTrainWorldData.get(overworld).dims();

            EditorCategory category;
            try {
                category = EditorCategory.valueOf(packet.category);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("[DungeonTrain] EditorPlotAction: unknown category '{}'", packet.category);
                return;
            }

            try {
                switch (category) {
                    case CARRIAGES -> dispatchCarriages(sender, overworld, dims, packet);
                    case CONTENTS -> dispatchContents(sender, overworld, dims, packet);
                    case TRACKS -> dispatchTracks(sender, overworld, dims, packet);
                    case ARCHITECTURE -> {} // no models
                }
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] EditorPlotAction failed (category={} modelId={} action={})",
                    category, packet.modelId, packet.action, t);
            }
        });
    }

    // ----- Carriages -----

    private static void dispatchCarriages(ServerPlayer sender, ServerLevel overworld, CarriageDims dims,
                                          EditorPlotActionPacket packet) throws Exception {
        CarriageVariant variant = CarriageVariantRegistry.find(packet.modelId).orElse(null);
        if (variant == null) {
            LOGGER.warn("[DungeonTrain] EditorPlotAction (carriages): unknown variant '{}'", packet.modelId);
            return;
        }
        switch (packet.action) {
            case SAVE -> {
                CarriageEditor.save(sender, variant);
                sender.sendSystemMessage(Component.literal(
                    "Editor: saved '" + variant.id() + "' template (config-dir).")
                    .copy().withStyle(ChatFormatting.GREEN));
            }
            case RESET -> {
                // Re-stamp from the saved (config-dir) template, restoring the
                // last-saved state. Mirrors `/dt reset` semantics — does NOT
                // delete the template (that was the pre-Phase-4 bug, now
                // corrected to match the slash-command's reset behaviour).
                CarriageEditor.stampPlot(overworld, variant, dims);
                sender.sendSystemMessage(Component.literal(
                    "Editor: reset '" + variant.id() + "' to last saved template.")
                    .copy().withStyle(ChatFormatting.GREEN));
            }
            case CLEAR -> {
                BlockPos origin = CarriageEditor.plotOrigin(variant, dims);
                if (origin != null) CarriagePlacer.eraseAt(overworld, origin, dims);
                sender.sendSystemMessage(Component.literal(
                    "Editor: cleared all blocks in '" + variant.id() + "'.")
                    .copy().withStyle(ChatFormatting.GREEN));
            }
            case ENTER_INSIDE -> CarriageEditor.enter(sender, variant, false);
        }
        LOGGER.info("[DungeonTrain] EditorPlotAction: {} {} carriage '{}'",
            sender.getName().getString(), packet.action, variant.id());
    }

    // ----- Contents -----

    private static void dispatchContents(ServerPlayer sender, ServerLevel overworld, CarriageDims dims,
                                         EditorPlotActionPacket packet) throws Exception {
        Optional<CarriageContents> opt = CarriageContentsRegistry.find(packet.modelId);
        if (opt.isEmpty()) {
            LOGGER.warn("[DungeonTrain] EditorPlotAction (contents): unknown id '{}'", packet.modelId);
            return;
        }
        CarriageContents contents = opt.get();
        switch (packet.action) {
            case SAVE -> {
                CarriageContentsEditor.save(sender, contents);
                sender.sendSystemMessage(Component.literal(
                    "Editor: saved contents '" + contents.id() + "' template (config-dir).")
                    .copy().withStyle(ChatFormatting.GREEN));
            }
            case RESET -> {
                // Re-stamp from the saved (config-dir) template — matches
                // `/dt reset` semantics. Pre-Phase-4 this used to clear+delete
                // the template, which was "remove" behaviour.
                CarriageContentsEditor.stampPlot(overworld, contents, dims);
                sender.sendSystemMessage(Component.literal(
                    "Editor: reset contents '" + contents.id() + "' to last saved template.")
                    .copy().withStyle(ChatFormatting.GREEN));
            }
            case CLEAR -> {
                BlockPos origin = CarriageContentsEditor.plotOrigin(contents, dims);
                if (origin != null) {
                    games.brennan.dungeontrain.train.CarriageContentsPlacer.eraseAt(overworld, origin, dims);
                }
                sender.sendSystemMessage(Component.literal(
                    "Editor: cleared all blocks in contents '" + contents.id() + "'.")
                    .copy().withStyle(ChatFormatting.GREEN));
            }
            case ENTER_INSIDE -> CarriageContentsEditor.enter(sender, contents, null, false);
        }
        LOGGER.info("[DungeonTrain] EditorPlotAction: {} {} contents '{}'",
            sender.getName().getString(), packet.action, contents.id());
    }

    // ----- Tracks (pillars / adjuncts / tunnels) -----

    private static void dispatchTracks(ServerPlayer sender, ServerLevel overworld, CarriageDims dims,
                                       EditorPlotActionPacket packet) throws Exception {
        String modelId = packet.modelId;
        // Track tile.
        if ("tile".equals(modelId) || "track".equals(modelId)) {
            if (packet.action == Action.ENTER_INSIDE) {
                games.brennan.dungeontrain.editor.TrackEditor.enter(sender, false);
            }
            LOGGER.info("[DungeonTrain] EditorPlotAction: {} {} track tile '{}'",
                sender.getName().getString(), packet.action, packet.modelName);
            return;
        }
        // Pillar sections.
        for (games.brennan.dungeontrain.track.PillarSection s : games.brennan.dungeontrain.track.PillarSection.values()) {
            if (("pillar_" + s.id()).equals(modelId)) {
                games.brennan.dungeontrain.template.PillarTemplateId id =
                    new games.brennan.dungeontrain.template.PillarTemplateId(s, packet.modelName);
                String label = "pillar_" + s.id() + " '" + packet.modelName + "'";
                switch (packet.action) {
                    case SAVE -> {
                        PillarEditor.save(sender, id);
                        sender.sendSystemMessage(Component.literal(
                            "Editor: saved " + label + " template (config-dir).")
                            .copy().withStyle(ChatFormatting.GREEN));
                    }
                    case RESET -> {
                        PillarEditor.stampPlot(overworld, s, packet.modelName, dims);
                        sender.sendSystemMessage(Component.literal(
                            "Editor: reset " + label + " to last saved template.")
                            .copy().withStyle(ChatFormatting.GREEN));
                    }
                    case CLEAR -> {
                        PillarEditor.clearPlot(overworld, s, packet.modelName, dims);
                        sender.sendSystemMessage(Component.literal(
                            "Editor: cleared all blocks in " + label + ".")
                            .copy().withStyle(ChatFormatting.GREEN));
                    }
                    case ENTER_INSIDE -> PillarEditor.enter(sender, s, false);
                }
                LOGGER.info("[DungeonTrain] EditorPlotAction: {} {} pillar '{}/{}'",
                    sender.getName().getString(), packet.action, s.id(), packet.modelName);
                return;
            }
        }
        // Pillar adjuncts.
        for (games.brennan.dungeontrain.track.PillarAdjunct a : games.brennan.dungeontrain.track.PillarAdjunct.values()) {
            if (("adjunct_" + a.id()).equals(modelId)) {
                games.brennan.dungeontrain.template.PillarAdjunctTemplateId id =
                    new games.brennan.dungeontrain.template.PillarAdjunctTemplateId(a, packet.modelName);
                String label = "adjunct_" + a.id() + " '" + packet.modelName + "'";
                switch (packet.action) {
                    case SAVE -> {
                        PillarEditor.save(sender, id);
                        sender.sendSystemMessage(Component.literal(
                            "Editor: saved " + label + " template (config-dir).")
                            .copy().withStyle(ChatFormatting.GREEN));
                    }
                    case RESET -> {
                        PillarEditor.stampPlotAdjunct(overworld, a, packet.modelName, dims);
                        sender.sendSystemMessage(Component.literal(
                            "Editor: reset " + label + " to last saved template.")
                            .copy().withStyle(ChatFormatting.GREEN));
                    }
                    case CLEAR -> {
                        PillarEditor.clearPlotAdjunct(overworld, a, packet.modelName, dims);
                        sender.sendSystemMessage(Component.literal(
                            "Editor: cleared all blocks in " + label + ".")
                            .copy().withStyle(ChatFormatting.GREEN));
                    }
                    case ENTER_INSIDE -> PillarEditor.enter(sender, a, false);
                }
                LOGGER.info("[DungeonTrain] EditorPlotAction: {} {} adjunct '{}/{}'",
                    sender.getName().getString(), packet.action, a.id(), packet.modelName);
                return;
            }
        }
        // Tunnel variants.
        for (TunnelPlacer.TunnelVariant tv : TunnelPlacer.TunnelVariant.values()) {
            if (("tunnel_" + tv.name().toLowerCase(Locale.ROOT)).equals(modelId)) {
                games.brennan.dungeontrain.template.TunnelTemplateId id =
                    new games.brennan.dungeontrain.template.TunnelTemplateId(tv, packet.modelName);
                String label = "tunnel_" + tv.name().toLowerCase(Locale.ROOT) + " '" + packet.modelName + "'";
                switch (packet.action) {
                    case SAVE -> {
                        TunnelEditor.save(sender, id);
                        sender.sendSystemMessage(Component.literal(
                            "Editor: saved " + label + " template (config-dir).")
                            .copy().withStyle(ChatFormatting.GREEN));
                    }
                    case RESET -> {
                        TunnelEditor.stampPlot(overworld, tv, packet.modelName);
                        sender.sendSystemMessage(Component.literal(
                            "Editor: reset " + label + " to last saved template.")
                            .copy().withStyle(ChatFormatting.GREEN));
                    }
                    case CLEAR -> {
                        BlockPos origin = TunnelEditor.plotOrigin(id);
                        TunnelPlacer.eraseAt(overworld, origin);
                        sender.sendSystemMessage(Component.literal(
                            "Editor: cleared all blocks in " + label + ".")
                            .copy().withStyle(ChatFormatting.GREEN));
                    }
                    case ENTER_INSIDE -> TunnelEditor.enter(sender, tv, false);
                }
                LOGGER.info("[DungeonTrain] EditorPlotAction: {} {} tunnel '{}/{}'",
                    sender.getName().getString(), packet.action, tv.name(), packet.modelName);
                return;
            }
        }
        // Track tile arm not wired (track tile has only one variant; the
        // action-bar /dt save flow covers it).
        LOGGER.info("[DungeonTrain] EditorPlotAction: tracks {} {} not yet wired (modelId='{}')",
            packet.action, "model", modelId);
    }
}
