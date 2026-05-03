package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CarriageGroupGapState;
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
 * Server → client snapshot describing the X-axis gap between each carriage
 * group and the next-higher-pIdx group in the same train. Pushed every few
 * ticks by {@link games.brennan.dungeontrain.event.CarriageGroupGapTicker}
 * and consumed by {@link CarriageGroupGapState} for two debug surfaces:
 * the HUD next-group distance line under the carriage index, and the
 * world-space wireframe-ghost-cubes-plus-label overlay rendered by
 * {@link games.brennan.dungeontrain.client.CarriageGroupGapDebugRenderer}.
 *
 * <p>Each {@link Entry} identifies the source group by its
 * {@code [anchorPIdx, highestPIdx]} carriage range AND the underlying
 * sub-level UUID (so the client can look up the live render-pose for
 * smooth per-frame transform). Coordinates are in the carriage's
 * <b>shipyard (model) space</b> — the renderer transforms them to world
 * space at draw time using the sub-level's interpolated render-pose, so
 * the ghost cubes and label ride the carriage smoothly between server
 * snapshots instead of jumping every 4 ticks.</p>
 *
 * <p>Coordinate fields:
 * <ul>
 *   <li>{@code (localStartX, localY, localZ)} — the line origin in
 *       <i>this</i> group's shipyard space (the {@code +X} face of the
 *       group, top of carriage, Z centerline).</li>
 *   <li>{@code distance} — float blocks along {@code +X} from
 *       {@code localStartX} to where the next group's trailing face
 *       sits (treating both groups as if they share this group's
 *       reference frame; valid because trains are locked to identity
 *       rotation, so world-X delta = shipyard-X delta).</li>
 * </ul>
 * Distance ≈ 0 when adjacent groups are touching pad-to-pad; positive
 * = gap; negative = overlap. The leading group of each train (no "next
 * group") gets no entry.</p>
 *
 * <p>An empty packet ({@code entries.isEmpty()}) clears the client cache —
 * sent when no train has more than one group (e.g. fresh world, or
 * {@code /dt debug pair} with single-carriage groups).</p>
 */
public record CarriageGroupGapPacket(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(
        int anchorPIdx,
        int highestPIdx,
        UUID subLevelId,
        double localStartX,
        double localY,
        double localZ,
        float distance
    ) {}

    public static final Type<CarriageGroupGapPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "carriage_group_gap"));

    public static final StreamCodec<FriendlyByteBuf, CarriageGroupGapPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            CarriageGroupGapPacket::decode
        );

    public static CarriageGroupGapPacket empty() {
        return new CarriageGroupGapPacket(Collections.emptyList());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeVarInt(e.anchorPIdx());
            buf.writeVarInt(e.highestPIdx());
            buf.writeUUID(e.subLevelId());
            buf.writeDouble(e.localStartX());
            buf.writeDouble(e.localY());
            buf.writeDouble(e.localZ());
            buf.writeFloat(e.distance());
        }
    }

    public static CarriageGroupGapPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Entry(
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUUID(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readDouble(),
                buf.readFloat()
            ));
        }
        return new CarriageGroupGapPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CarriageGroupGapPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CarriageGroupGapState.applySnapshot(packet));
    }
}
