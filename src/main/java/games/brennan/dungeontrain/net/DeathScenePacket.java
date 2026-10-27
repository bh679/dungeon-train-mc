package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DeathSceneHints;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * What the death moment's camera needs to know about this death, sent right after the vanilla
 * death so it is on the client before the camera starts (dead tick 2 to 3).
 *
 * @param voidDeath the player fell out of the world: there is no death spot to look at
 * @param killerId  entity id of the living thing that killed them, or -1
 * @param contactId entity id of the nearest other mob they recently fought or interacted with, or -1
 */
public record DeathScenePacket(boolean voidDeath, int killerId, int contactId) implements CustomPacketPayload {

    public static final int NONE = -1;

    public static final Type<DeathScenePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "death_scene"));

    public static final StreamCodec<FriendlyByteBuf, DeathScenePacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), DeathScenePacket::decode);

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(voidDeath);
        buf.writeVarInt(killerId);
        buf.writeVarInt(contactId);
    }

    public static DeathScenePacket decode(FriendlyByteBuf buf) {
        return new DeathScenePacket(buf.readBoolean(), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DeathScenePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DeathSceneHints.set(packet));
    }
}
