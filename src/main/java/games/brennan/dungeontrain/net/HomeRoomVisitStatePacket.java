package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.homeroom.HomeTvVisit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: whether this player is on a home room visit, and where its TV is.
 *
 * <p>A state the server owns both edges of, like {@link PortalTestSessionPacket}: active as the player
 * is stood in front of the TV, none as the visit ends. The screen box is every block of the TV, so the
 * client can tell the home TV from any other and knows when its block entity has arrived.</p>
 */
public record HomeRoomVisitStatePacket(boolean active, BlockPos screenMin, BlockPos screenMax, Direction facing)
    implements CustomPacketPayload {

    public static final Type<HomeRoomVisitStatePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "home_room_visit_state"));

    public static final StreamCodec<FriendlyByteBuf, HomeRoomVisitStatePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.active);
                buf.writeBlockPos(packet.screenMin);
                buf.writeBlockPos(packet.screenMax);
                buf.writeEnum(packet.facing);
            },
            buf -> new HomeRoomVisitStatePacket(buf.readBoolean(), buf.readBlockPos(), buf.readBlockPos(),
                buf.readEnum(Direction.class)));

    public static HomeRoomVisitStatePacket none() {
        return new HomeRoomVisitStatePacket(false, BlockPos.ZERO, BlockPos.ZERO, Direction.NORTH);
    }

    public static HomeRoomVisitStatePacket active(BoundingBox screen, Direction facing) {
        return new HomeRoomVisitStatePacket(true,
            new BlockPos(screen.minX(), screen.minY(), screen.minZ()),
            new BlockPos(screen.maxX(), screen.maxY(), screen.maxZ()), facing);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(HomeRoomVisitStatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> HomeTvVisit.onState(packet));
    }
}
