package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ClientComets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: a spheres-band comet was launched ({@code ended == false}: where from and how fast), or it is
 * gone ({@code ended == true}). A gone comet either {@code landed} (x/y/z is where it hit, so nearby players feel
 * it) or burnt out (no position). The client flies and draws it from those alone ({@link ClientComets}), so a
 * comet costs two packets, not a particle stream per tick.
 */
public record CometPacket(int id, boolean ended, boolean landed, double x, double y, double z,
                          float vx, float vy, float vz) implements CustomPacketPayload {

    public static final Type<CometPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "comet"));

    public static final StreamCodec<FriendlyByteBuf, CometPacket> STREAM_CODEC =
        StreamCodec.of(CometPacket::encode, CometPacket::decode);

    public static CometPacket launched(int id, double x, double y, double z, double vx, double vy, double vz) {
        return new CometPacket(id, false, false, x, y, z, (float) vx, (float) vy, (float) vz);
    }

    public static CometPacket ended(int id) {
        return new CometPacket(id, true, false, 0.0, 0.0, 0.0, 0F, 0F, 0F);
    }

    public static CometPacket landed(int id, double x, double y, double z) {
        return new CometPacket(id, true, true, x, y, z, 0F, 0F, 0F);
    }

    private static void encode(FriendlyByteBuf buf, CometPacket p) {
        buf.writeVarInt(p.id);
        buf.writeBoolean(p.ended);
        if (p.ended) buf.writeBoolean(p.landed);
        if (p.ended && !p.landed) return;
        buf.writeDouble(p.x);
        buf.writeDouble(p.y);
        buf.writeDouble(p.z);
        if (p.ended) return;
        buf.writeFloat(p.vx);
        buf.writeFloat(p.vy);
        buf.writeFloat(p.vz);
    }

    private static CometPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        if (buf.readBoolean()) {
            return buf.readBoolean() ? landed(id, buf.readDouble(), buf.readDouble(), buf.readDouble()) : ended(id);
        }
        return new CometPacket(id, false, false, buf.readDouble(), buf.readDouble(), buf.readDouble(),
            buf.readFloat(), buf.readFloat(), buf.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CometPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ClientComets.accept(packet));
    }
}
