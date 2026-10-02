package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.TemplateBlockGroupsController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the X editor's Blocks page.
 *
 * <ul>
 *   <li>{@link Op#REQUEST} — send back the cells of the plot the player is standing in
 *       ({@code key} and {@code group} are ignored).</li>
 *   <li>{@link Op#RESKIN} — replace every block of cell {@code group} of plot {@code key} with the
 *       player's held block, keeping orientation. The cell stays its own cell until the template
 *       is saved.</li>
 * </ul>
 *
 * <p>Enum ordinals are the wire format — only ever append new values.</p>
 */
public record TemplateBlockGroupsEditPacket(Op op, String key, int group) implements CustomPacketPayload {

    public enum Op { REQUEST, RESKIN }

    public static final Type<TemplateBlockGroupsEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "template_block_groups_edit"));

    public static final StreamCodec<FriendlyByteBuf, TemplateBlockGroupsEditPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), TemplateBlockGroupsEditPacket::decode);

    public static TemplateBlockGroupsEditPacket request() {
        return new TemplateBlockGroupsEditPacket(Op.REQUEST, "", -1);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(op.ordinal());
        buf.writeUtf(key, 128);
        buf.writeVarInt(group);
    }

    public static TemplateBlockGroupsEditPacket decode(FriendlyByteBuf buf) {
        int ordinal = buf.readByte();
        Op[] ops = Op.values();
        if (ordinal < 0 || ordinal >= ops.length) {
            throw new IllegalArgumentException("Unknown template block groups op " + ordinal);
        }
        return new TemplateBlockGroupsEditPacket(ops[ordinal], buf.readUtf(128), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TemplateBlockGroupsEditPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (!(p instanceof ServerPlayer sender)) return;
            if (packet.op() == Op.RESKIN) {
                games.brennan.dungeontrain.editor.EditorEditRecorder.notePendingConfig(sender, "template blocks");
            }
            TemplateBlockGroupsController.applyEdit(sender, packet);
        });
    }
}
