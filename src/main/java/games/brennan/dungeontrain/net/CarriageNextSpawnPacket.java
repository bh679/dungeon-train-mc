package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CarriageNextSpawnState;
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
 * Server → client snapshot of the next planned spawn for every train that
 * has a queued anchor. Sent every appender broadcast tick by
 * {@link games.brennan.dungeontrain.event.CarriageGroupGapTicker}; consumed
 * by {@link CarriageNextSpawnState} for the wireframe-preview overlay drawn
 * by {@link games.brennan.dungeontrain.client.CarriageGroupGapDebugRenderer}.
 *
 * <p>Each {@link Entry} carries the {@code referenceShipId} (the lead or
 * tail carriage the planner used as the world-position reference), the
 * world-space planned origin (the {@code -X}/{@code -Y}/{@code -Z} corner
 * of the would-be sub-level), and the sub-level's full
 * {@code (sizeX, sizeY, sizeZ)} — so the client can draw a wireframe AABB
 * exactly matching the planned footprint.</p>
 *
 * <p>The renderer transforms the world origin through the reference
 * ship's interpolated render-pose every frame so the wireframe rides the
 * reference carriage smoothly between server snapshots — same approach
 * the gap-line cubes use.</p>
 */
public record CarriageNextSpawnPacket(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(
        UUID referenceShipId,
        double worldOriginX,
        double worldOriginY,
        double worldOriginZ,
        int sizeX,
        int sizeY,
        int sizeZ,
        int newAnchor
    ) {}

    public static final Type<CarriageNextSpawnPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "carriage_next_spawn"));

    public static final StreamCodec<FriendlyByteBuf, CarriageNextSpawnPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            CarriageNextSpawnPacket::decode
        );

    public static CarriageNextSpawnPacket empty() {
        return new CarriageNextSpawnPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUUID(e.referenceShipId());
            buf.writeDouble(e.worldOriginX());
            buf.writeDouble(e.worldOriginY());
            buf.writeDouble(e.worldOriginZ());
            buf.writeVarInt(e.sizeX());
            buf.writeVarInt(e.sizeY());
            buf.writeVarInt(e.sizeZ());
            buf.writeVarInt(e.newAnchor());
        }
    }

    public static CarriageNextSpawnPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Entry(
                buf.readUUID(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt()
            ));
        }
        return new CarriageNextSpawnPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CarriageNextSpawnPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CarriageNextSpawnState.applySnapshot(packet));
    }
}
