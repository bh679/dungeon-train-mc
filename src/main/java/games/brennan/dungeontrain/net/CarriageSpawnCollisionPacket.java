package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CarriageSpawnCollisionState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Server → client snapshot of the most recent post-spawn collision-check
 * box per train. Sent every appender broadcast tick by
 * {@link games.brennan.dungeontrain.event.CarriageGroupGapTicker}; consumed
 * by {@link CarriageSpawnCollisionState} for the green/red wireframe drawn
 * by {@link games.brennan.dungeontrain.client.CarriageGroupGapDebugRenderer}.
 *
 * <p>Each {@link Entry} carries the new ship's {@code newShipId} (anchor
 * for client-side pose lookup), the world-space origin (lowest-X corner
 * of the 1×3×5 check box), the box's full {@code (sizeX, sizeY, sizeZ)},
 * and the boolean {@code colliding} flag — green when {@code false}, red
 * when {@code true}. The {@code collidingPIdx} carries the offender's
 * pIdx for debugging.</p>
 */
public record CarriageSpawnCollisionPacket(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(
        UUID newShipId,
        int shipyardOriginX,
        int shipyardOriginY,
        int shipyardOriginZ,
        int sizeX,
        int sizeY,
        int sizeZ,
        boolean colliding,
        int collidingPIdx
    ) {}

    public static final Type<CarriageSpawnCollisionPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "carriage_spawn_collision"));

    public static final StreamCodec<FriendlyByteBuf, CarriageSpawnCollisionPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            CarriageSpawnCollisionPacket::decode
        );

    public static CarriageSpawnCollisionPacket empty() {
        return new CarriageSpawnCollisionPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUUID(e.newShipId());
            buf.writeVarInt(e.shipyardOriginX());
            buf.writeVarInt(e.shipyardOriginY());
            buf.writeVarInt(e.shipyardOriginZ());
            buf.writeVarInt(e.sizeX());
            buf.writeVarInt(e.sizeY());
            buf.writeVarInt(e.sizeZ());
            buf.writeBoolean(e.colliding());
            buf.writeVarInt(e.collidingPIdx());
        }
    }

    public static CarriageSpawnCollisionPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Entry(
                buf.readUUID(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt()
            ));
        }
        return new CarriageSpawnCollisionPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CarriageSpawnCollisionPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CarriageSpawnCollisionState.applySnapshot(packet));
    }
}
