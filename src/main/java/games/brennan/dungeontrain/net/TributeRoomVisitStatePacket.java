package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.tributeroom.TributeTvVisit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * S2C: whether this player is on a tribute room visit, and where its TV is.
 *
 * <p>A state the server owns both edges of, like {@link PortalTestSessionPacket}: active as the player
 * is stood in front of the TV, none as the visit ends. The screen box is every block of the TV, so the
 * client can tell the tribute TV from any other and knows when its block entity has arrived. The tubes are
 * the way out: the view goes black with the distance walked into one, and the far end boards anew.</p>
 */
public record TributeRoomVisitStatePacket(boolean active, BlockPos screenMin, BlockPos screenMax, Direction facing,
                                       List<BoundingBox> tubes)
    implements CustomPacketPayload {

    public static final Type<TributeRoomVisitStatePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tribute_room_visit_state"));

    public static final StreamCodec<FriendlyByteBuf, TributeRoomVisitStatePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBoolean(packet.active);
                buf.writeBlockPos(packet.screenMin);
                buf.writeBlockPos(packet.screenMax);
                buf.writeEnum(packet.facing);
                buf.writeVarInt(packet.tubes.size());
                for (BoundingBox tube : packet.tubes) {
                    buf.writeBlockPos(new BlockPos(tube.minX(), tube.minY(), tube.minZ()));
                    buf.writeBlockPos(new BlockPos(tube.maxX(), tube.maxY(), tube.maxZ()));
                }
            },
            buf -> {
                boolean active = buf.readBoolean();
                BlockPos min = buf.readBlockPos();
                BlockPos max = buf.readBlockPos();
                Direction facing = buf.readEnum(Direction.class);
                int n = buf.readVarInt();
                List<BoundingBox> tubes = new ArrayList<>(n);
                for (int i = 0; i < n; i++) tubes.add(BoundingBox.fromCorners(buf.readBlockPos(), buf.readBlockPos()));
                return new TributeRoomVisitStatePacket(active, min, max, facing, List.copyOf(tubes));
            });

    public static TributeRoomVisitStatePacket none() {
        return new TributeRoomVisitStatePacket(false, BlockPos.ZERO, BlockPos.ZERO, Direction.NORTH, List.of());
    }

    /** @param tubes the visit's entrance and exit tubes: walking into one darkens the view, and its far end ends the visit */
    public static TributeRoomVisitStatePacket active(BoundingBox screen, Direction facing, List<BoundingBox> tubes) {
        return new TributeRoomVisitStatePacket(true,
            new BlockPos(screen.minX(), screen.minY(), screen.minZ()),
            new BlockPos(screen.maxX(), screen.maxY(), screen.maxZ()), facing, List.copyOf(tubes));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TributeRoomVisitStatePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> TributeTvVisit.onState(packet));
    }
}
