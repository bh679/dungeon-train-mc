package games.brennan.dungeontrain.perks.effects.warlock;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server to client: a Sacrificial Lightning strike at ({@code x}, {@code y}, {@code z}) that dealt
 * {@code damageHp}. Each client nearby draws it ({@code client.warlock.SacrificialBoltClient}): bolts as
 * thick and as many as the damage says, thunder as loud, and a sky flash. It is only ever a picture,
 * so no bolt entity exists to light blocks or convert mobs. {@code seed} keeps every client's bolts alike.
 */
public record SacrificialBoltPacket(double x, double y, double z, float damageHp, long seed) implements CustomPacketPayload {

    public static final Type<SacrificialBoltPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "sacrificial_bolt"));

    public static final StreamCodec<FriendlyByteBuf, SacrificialBoltPacket> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeDouble(p.x);
            buf.writeDouble(p.y);
            buf.writeDouble(p.z);
            buf.writeFloat(p.damageHp);
            buf.writeLong(p.seed);
        },
        buf -> new SacrificialBoltPacket(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SacrificialBoltPacket packet, IPayloadContext ctx) {
        if (!FMLEnvironment.dist.isClient()) return;
        ctx.enqueueWork(() -> games.brennan.dungeontrain.client.warlock.SacrificialBoltClient.onBolt(packet));
    }
}
