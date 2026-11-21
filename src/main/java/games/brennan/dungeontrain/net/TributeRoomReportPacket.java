package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.tributeroom.TributeTvShowcase;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * S2C: a tribute room's TV and what it replays — the owner's last death, as the stats packet's bytes
 * ({@code LastDeathReports}), and who they are so a live stream of theirs wins over the replay.
 * Sent as a player walks into a tribute room standing on the train, and on arrival in the editor's
 * test of one.
 */
public record TributeRoomReportPacket(BlockPos screenMin, BlockPos screenMax, Direction facing, String ownerName,
                                   String deathMessage, byte[] report) implements CustomPacketPayload {

    public static final Type<TributeRoomReportPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tribute_room_report"));

    public static final StreamCodec<FriendlyByteBuf, TributeRoomReportPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeBlockPos(packet.screenMin);
                buf.writeBlockPos(packet.screenMax);
                buf.writeEnum(packet.facing);
                buf.writeUtf(packet.ownerName);
                buf.writeUtf(packet.deathMessage);
                buf.writeByteArray(packet.report);
            },
            buf -> new TributeRoomReportPacket(buf.readBlockPos(), buf.readBlockPos(), buf.readEnum(Direction.class),
                buf.readUtf(), buf.readUtf(), buf.readByteArray()));

    public static TributeRoomReportPacket of(BoundingBox screen, Direction facing, String ownerName, String deathMessage, byte[] report) {
        return new TributeRoomReportPacket(new BlockPos(screen.minX(), screen.minY(), screen.minZ()),
            new BlockPos(screen.maxX(), screen.maxY(), screen.maxZ()), facing, ownerName, deathMessage, report);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TributeRoomReportPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> TributeTvShowcase.onReport(packet));
    }
}
