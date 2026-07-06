package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: Stage Blocks panel ops. {@code OPEN}/{@code CLOSE} toggle the panel for
 * {@code stageId} (server replies with {@link StageBlocksSyncPacket}); {@code REPLACE_BLOCK}
 * runs the stage-wide {@code fromBlockId} → {@code toBlockId} rewrite via
 * {@link games.brennan.dungeontrain.editor.StageBlockReplacer}; {@code TOGGLE_HIDE_UNUSED}
 * flips the global {@link games.brennan.dungeontrain.editor.EditorPartsStageFilter} and
 * repaints the parts grid. Stage duplication is <b>not</b> an op here — it dispatches the
 * {@code /dungeontrain editor stage duplicate} command via the typed-input screen.
 *
 * <p>The server validates OP≥2 on every op and, for {@code REPLACE_BLOCK}, that the panel the
 * player has open matches {@code stageId} — mirrors
 * {@link games.brennan.dungeontrain.editor.BlockVariantMenuController}'s authorisation model.</p>
 */
public record StagePanelEditPacket(Op op, String stageId, String fromBlockId, String toBlockId)
        implements CustomPacketPayload {

    // NOTE: ordinals are the wire format (encode writes op.ordinal()) — only ever APPEND.
    public enum Op { OPEN, CLOSE, REPLACE_BLOCK, TOGGLE_HIDE_UNUSED }

    public static final Type<StagePanelEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "stage_panel_edit"));

    public static final StreamCodec<FriendlyByteBuf, StagePanelEditPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            StagePanelEditPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(op.ordinal());
        buf.writeUtf(stageId == null ? "" : stageId);
        buf.writeUtf(fromBlockId == null ? "" : fromBlockId);
        buf.writeUtf(toBlockId == null ? "" : toBlockId);
    }

    public static StagePanelEditPacket decode(FriendlyByteBuf buf) {
        Op op = Op.values()[buf.readByte()];
        String stageId = buf.readUtf(64);
        String from = buf.readUtf(256);
        String to = buf.readUtf(256);
        return new StagePanelEditPacket(op, stageId, from, to);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StagePanelEditPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                games.brennan.dungeontrain.editor.StagePanelController.applyEdit(sender, packet);
            }
        });
    }
}
