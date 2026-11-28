package games.brennan.dungeontrain.perks.effects.archivist.follow;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client to server: a Favourite Author action. {@code value} is a player name ({@link Op#FOLLOW_NAME}),
 * a community book or photo id ({@link Op#FOLLOW_BOOK}, {@link Op#FOLLOW_PHOTO}), an author token
 * ({@link Op#UNFOLLOW}), a name ({@link Op#DISMISS}: drop a not-found lookup), or nothing
 * ({@link Op#REFRESH}: the menu opened). The server validates everything ({@link FollowService}).
 */
public record FollowRequestPacket(Op op, String value) implements CustomPacketPayload {

    public enum Op { FOLLOW_NAME, FOLLOW_BOOK, FOLLOW_PHOTO, UNFOLLOW, DISMISS, REFRESH }

    public static final Type<FollowRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "follow_request"));

    public static final StreamCodec<FriendlyByteBuf, FollowRequestPacket> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeEnum(p.op);
            buf.writeUtf(p.value, 64);
        },
        buf -> new FollowRequestPacket(buf.readEnum(Op.class), buf.readUtf(64)));

    public FollowRequestPacket {
        value = value == null ? "" : value;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FollowRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) FollowService.request(player, packet.op(), packet.value());
        });
    }
}
