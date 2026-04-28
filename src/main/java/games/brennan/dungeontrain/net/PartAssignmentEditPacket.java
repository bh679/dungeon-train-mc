package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.PartPositionMenuController;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: mutate the carriage variant's part assignment for one
 * kind, then persist via the existing
 * {@link games.brennan.dungeontrain.editor.CarriageVariantPartsStore}.
 *
 * <p>{@code op} encodes the action; {@code name} is the entry being
 * targeted ({@code ""} for {@link Op#CLEAR}); {@code delta} is the
 * weight change (only used by {@link Op#BUMP_WEIGHT}, typically +1 or
 * -1). {@link Op#PREVIEW_ENTRY} re-stamps the named variant's template
 * at the placement under the player's crosshair — assignment list,
 * weights, and side-mode chips are untouched (transient world-only
 * effect). The server validates that the player is actually OP and is
 * standing inside the carriage variant's editor plot before mutating —
 * matches the existing slash-command authorisation model.</p>
 */
public record PartAssignmentEditPacket(Op op, String variantId, CarriagePartKind kind,
                                       String name, int delta) implements CustomPacketPayload {

    public enum Op { ADD, REMOVE, CLEAR, BUMP_WEIGHT, CYCLE_SIDE_MODE, PREVIEW_ENTRY }

    public static final Type<PartAssignmentEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "part_assignment_edit"));

    public static final StreamCodec<FriendlyByteBuf, PartAssignmentEditPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            PartAssignmentEditPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(op.ordinal());
        buf.writeUtf(variantId);
        buf.writeByte(kind.ordinal());
        buf.writeUtf(name == null ? "" : name);
        buf.writeVarInt(delta);
    }

    public static PartAssignmentEditPacket decode(FriendlyByteBuf buf) {
        byte opOrd = buf.readByte();
        Op op = Op.values()[opOrd];
        String id = buf.readUtf(64);
        byte kindOrd = buf.readByte();
        CarriagePartKind kind = CarriagePartKind.values()[kindOrd];
        String name = buf.readUtf(64);
        int delta = buf.readVarInt();
        return new PartAssignmentEditPacket(op, id, kind, name, delta);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PartAssignmentEditPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                PartPositionMenuController.applyEdit(sender, packet);
            }
        });
    }
}
