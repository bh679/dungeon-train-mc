package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.DeathSceneHints;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the death moment's camera needs to know about this death, sent right after the vanilla
 * death so it is on the client before the camera starts (dead tick 2 to 3).
 *
 * @param voidDeath no death spot to look at: fell out of the world
 * @param killerId  entity id of the living thing that killed them, or -1
 * @param contactId entity id of the nearest other creature they recently had to do with, or -1
 * @param targets   features of the train near the body worth a tracking shot, best first (may be empty)
 */
public record DeathScenePacket(boolean voidDeath, int killerId, int contactId, List<Target> targets) implements CustomPacketPayload {

    public static final int NONE = -1;
    public static final int MAX_TARGETS = 8;

    /** A point on a sub-level, in that sub-level's plot space. */
    public record Target(UUID subLevelId, double x, double y, double z, Kind kind) {
        public enum Kind { CONTENTS, GROUP }
    }

    public DeathScenePacket(boolean voidDeath, int killerId, int contactId) {
        this(voidDeath, killerId, contactId, List.of());
    }

    public static final Type<DeathScenePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "death_scene"));

    public static final StreamCodec<FriendlyByteBuf, DeathScenePacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> packet.encode(buf), DeathScenePacket::decode);

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(voidDeath);
        buf.writeVarInt(killerId);
        buf.writeVarInt(contactId);
        int n = Math.min(targets.size(), MAX_TARGETS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            Target t = targets.get(i);
            buf.writeUUID(t.subLevelId());
            buf.writeDouble(t.x());
            buf.writeDouble(t.y());
            buf.writeDouble(t.z());
            buf.writeByte(t.kind().ordinal());
        }
    }

    public static DeathScenePacket decode(FriendlyByteBuf buf) {
        boolean voidDeath = buf.readBoolean();
        int killer = buf.readVarInt();
        int contact = buf.readVarInt();
        int n = Math.min(buf.readVarInt(), MAX_TARGETS);
        List<Target> targets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = buf.readUUID();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            int kind = buf.readByte();
            targets.add(new Target(id, x, y, z, Target.Kind.values()[Math.floorMod(kind, Target.Kind.values().length)]));
        }
        return new DeathScenePacket(voidDeath, killer, contact, List.copyOf(targets));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DeathScenePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> DeathSceneHints.set(packet));
    }
}
