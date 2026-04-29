package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.editor.ContainerContentsMenuController;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: mutate the container-contents pool at
 * {@code (plotKey, localPos)}.
 *
 * <ul>
 *   <li>{@link Op#ADD} — append an entry. {@code itemId} is the item
 *       registry name (e.g. {@code "minecraft:diamond"}); count/weight
 *       default to 1.</li>
 *   <li>{@link Op#REMOVE} — drop entry at {@code entryIndex}.</li>
 *   <li>{@link Op#CLEAR} — drop the whole pool.</li>
 *   <li>{@link Op#BUMP_WEIGHT} / {@link Op#BUMP_COUNT} — adjust by
 *       {@code delta} (signed; clamped server-side).</li>
 * </ul>
 *
 * <p>Server validates OP + plot membership before mutating.</p>
 */
public record ContainerContentsEditPacket(Op op, String plotKey, BlockPos localPos,
                                          int entryIndex, String itemId, int delta) implements CustomPacketPayload {

    public enum Op { ADD, REMOVE, CLEAR, BUMP_WEIGHT, BUMP_COUNT, BUMP_FILL_MIN, BUMP_FILL_MAX }

    public static final Type<ContainerContentsEditPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "container_contents_edit"));

    public static final StreamCodec<FriendlyByteBuf, ContainerContentsEditPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            ContainerContentsEditPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(op.ordinal());
        buf.writeUtf(plotKey, 128);
        buf.writeVarInt(localPos.getX());
        buf.writeVarInt(localPos.getY());
        buf.writeVarInt(localPos.getZ());
        buf.writeVarInt(entryIndex);
        buf.writeUtf(itemId == null ? "" : itemId, 256);
        buf.writeVarInt(delta);
    }

    public static ContainerContentsEditPacket decode(FriendlyByteBuf buf) {
        byte ord = buf.readByte();
        Op op = Op.values()[ord];
        String key = buf.readUtf(128);
        BlockPos local = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        int idx = buf.readVarInt();
        String itemId = buf.readUtf(256);
        int delta = buf.readVarInt();
        return new ContainerContentsEditPacket(op, key, local, idx, itemId, delta);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ContainerContentsEditPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player p = ctx.player();
            if (p instanceof ServerPlayer sender) {
                ContainerContentsMenuController.applyEdit(sender, packet);
            }
        });
    }
}
