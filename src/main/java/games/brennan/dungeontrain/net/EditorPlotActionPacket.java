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
import games.brennan.dungeontrain.train.CarriageTemplate;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.tunnel.TunnelTemplate;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
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
            case SAVE -> CarriageEditor.save(sender, variant);
            case RESET -> {
                CarriageEditor.clearPlot(overworld, variant, dims);
                CarriageTemplateStore.delete(variant);
                if (!variant.isBuiltin()) CarriageVariantRegistry.unregister(variant.id());
            }
            case CLEAR -> {
                BlockPos origin = CarriageEditor.plotOrigin(variant, dims);
                if (origin != null) CarriageTemplate.eraseAt(overworld, origin, dims);
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
            case SAVE -> CarriageContentsEditor.save(sender, contents);
            case RESET -> {
                // Mirrors EditorCommand.runContentsReset: clear plot + delete
                // template store entry. Custom contents also unregister.
                CarriageContentsEditor.clearPlot(overworld, contents, dims);
                games.brennan.dungeontrain.editor.CarriageContentsStore.delete(contents);
                if (!contents.isBuiltin()) CarriageContentsRegistry.unregister(contents.id());
            }
            case CLEAR -> {
                BlockPos origin = CarriageContentsEditor.plotOrigin(contents, dims);
                if (origin != null) {
                    games.brennan.dungeontrain.train.CarriageContentsTemplate.eraseAt(overworld, origin, dims);
                }
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
                if (packet.action == Action.SAVE) {
                    PillarEditor.save(sender, s);
                } else if (packet.action == Action.ENTER_INSIDE) {
                    PillarEditor.enter(sender, s, false);
                }
                LOGGER.info("[DungeonTrain] EditorPlotAction: {} {} pillar '{}/{}'",
                    sender.getName().getString(), packet.action, s.id(), packet.modelName);
                return;
            }
        }
        // Pillar adjuncts.
        for (games.brennan.dungeontrain.track.PillarAdjunct a : games.brennan.dungeontrain.track.PillarAdjunct.values()) {
            if (("adjunct_" + a.id()).equals(modelId)) {
                if (packet.action == Action.SAVE) {
                    PillarEditor.save(sender, a);
                } else if (packet.action == Action.ENTER_INSIDE) {
                    PillarEditor.enter(sender, a, false);
                }
                LOGGER.info("[DungeonTrain] EditorPlotAction: {} {} adjunct '{}/{}'",
                    sender.getName().getString(), packet.action, a.id(), packet.modelName);
                return;
            }
        }
        // Tunnel variants.
        for (TunnelTemplate.TunnelVariant tv : TunnelTemplate.TunnelVariant.values()) {
            if (("tunnel_" + tv.name().toLowerCase(Locale.ROOT)).equals(modelId)) {
                if (packet.action == Action.SAVE) {
                    TunnelEditor.save(sender, tv);
                } else if (packet.action == Action.ENTER_INSIDE) {
                    TunnelEditor.enter(sender, tv, false);
                }
                LOGGER.info("[DungeonTrain] EditorPlotAction: {} {} tunnel '{}/{}'",
                    sender.getName().getString(), packet.action, tv.name(), packet.modelName);
                return;
            }
        }
        // Track tile and reset/clear for tracks: not in iteration 1 — those
        // require methods that today only operate on the player's current
        // position. Logged for visibility so the missing wiring is obvious.
        LOGGER.info("[DungeonTrain] EditorPlotAction: tracks {} {} not yet wired (modelId='{}')",
            packet.action, "model", modelId);
    }
}
