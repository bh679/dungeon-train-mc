package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.portal.ClientPortalTwinBoxes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server → client: the world-space boxes of the twin corridors currently standing.
 *
 * <p>Sent so the client can decline to <i>predict</i> a shulker box into a twin — see
 * {@link games.brennan.dungeontrain.client.portal.ClientShulkerBoxPrediction}, which explains why a
 * server-side refusal alone still leaves the box appearing and vanishing. The carriage half of that
 * problem needs no packet, because Sable already tells the client where its sub-levels are. A twin is
 * ordinary world blocks, so nothing tells the client about it, and this does.</p>
 *
 * <p><b>Why this is affordable when a corridor box would not be.</b>
 * {@link games.brennan.dungeontrain.client.ClientPortalCrossing} rejects a client-testable corridor
 * box because the corridor rides the train: it "would have to be re-sent every tick as the train
 * moved". A twin does not move. It is stamped into the ground once and stands there until its pair
 * is retired, so this is sent only when the set of standing twins actually changes — in practice a
 * few times a minute at most, and a handful of boxes when it does.</p>
 *
 * <p>The box is the corridor volume, matching {@code PortalPairIndex.Entry.localOfTwin} exactly: the
 * origin plus the corridor's own length (which runs past the carriage slot into the cart — see
 * {@code PortalCorridorSize}), height and width. An empty packet clears the client cache and is sent
 * when the last twin goes.</p>
 */
public record PortalTwinBoxesPacket(List<Entry> entries) implements CustomPacketPayload {

    /**
     * One twin corridor: its world origin and its extent in cells.
     *
     * @param originX the twin's origin — the same {@code twinOrigin} the pair index holds
     * @param sizeX   the corridor's length, NOT the carriage's
     */
    public record Entry(int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ) {

        /** Does this box contain the cell {@code (x, y, z)}? The test {@code localOfTwin} makes. */
        public boolean contains(int x, int y, int z) {
            int dx = x - originX;
            int dy = y - originY;
            int dz = z - originZ;
            return dx >= 0 && dy >= 0 && dz >= 0 && dx < sizeX && dy < sizeY && dz < sizeZ;
        }
    }

    public static final Type<PortalTwinBoxesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "portal_twin_boxes"));

    public static final StreamCodec<FriendlyByteBuf, PortalTwinBoxesPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            PortalTwinBoxesPacket::decode
        );

    public static PortalTwinBoxesPacket empty() {
        return new PortalTwinBoxesPacket(Collections.emptyList());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            // writeInt, not writeVarInt: twin origins are routinely negative (a basement twin sits
            // under bedrock) and a long way out, which is the worst case for a varint.
            buf.writeInt(e.originX());
            buf.writeInt(e.originY());
            buf.writeInt(e.originZ());
            buf.writeVarInt(e.sizeX());
            buf.writeVarInt(e.sizeY());
            buf.writeVarInt(e.sizeZ());
        }
    }

    public static PortalTwinBoxesPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Entry(buf.readInt(), buf.readInt(), buf.readInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
        }
        return new PortalTwinBoxesPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PortalTwinBoxesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientPortalTwinBoxes.applySnapshot(packet.entries()));
    }
}
