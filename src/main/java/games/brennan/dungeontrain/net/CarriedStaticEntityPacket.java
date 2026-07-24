package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientCarriedStatics;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: tells the client that entity {@code entityId} is a train-carried "static"
 * contents entity (End Crystal / painting / item frame) and hands it the entity's fixed
 * <b>shipyard/plot coordinate</b>.
 *
 * <p><b>Why.</b> These entity types never run the per-tick hooks Sable uses to carry riders (End
 * Crystal overrides {@code tick()} without {@code super}; hanging entities never move), so the mod
 * repositions them itself. Doing that purely server-side and networking the world position on the
 * per-entity channel leaves the crystal shimmering against the carriage — the entity-position
 * packets and Sable's sub-level-pose packets arrive on different ticks and interpolate out of
 * phase. Instead the client positions the entity itself, each tick, from the <i>same</i> sub-level
 * pose that draws the blocks — zero phase offset. To do that the client needs the entity's constant
 * plot coordinate, which this packet delivers. See {@link ClientCarriedStatics} and
 * {@link games.brennan.dungeontrain.train.TrainStaticContentsCarrier}.</p>
 *
 * <p>Sent on {@code PlayerEvent.StartTracking} (once per player per entity; re-fires on reload /
 * re-entering range). The plot coordinate is constant for the entity's life, so one delivery per
 * tracking session is enough.</p>
 */
public record CarriedStaticEntityPacket(int entityId, double plotX, double plotY, double plotZ)
        implements CustomPacketPayload {

    public static final Type<CarriedStaticEntityPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "carried_static_entity"));

    public static final StreamCodec<FriendlyByteBuf, CarriedStaticEntityPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            CarriedStaticEntityPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeDouble(plotX);
        buf.writeDouble(plotY);
        buf.writeDouble(plotZ);
    }

    public static CarriedStaticEntityPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        return new CarriedStaticEntityPacket(id, x, y, z);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler — the direct {@link ClientCarriedStatics} reference is client-only. */
    public static void handle(CarriedStaticEntityPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            ClientCarriedStatics.register(packet.entityId(), packet.plotX(), packet.plotY(), packet.plotZ()));
    }
}
