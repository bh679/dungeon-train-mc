package games.brennan.dungeontrain.perks.effects.hoarder;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: one Chest Sense pulse: the containers found, how many ticks to outline them
 * and the range the particle ring spreads to ({@link ChestSenseClientState}). At most {@value #MAX_ENTRIES} entries; a pulse that finds more
 * shows the first ones found.
 */
public record ChestSensePacket(int durationTicks, float range, List<ChestSense.Sensed> sensed) implements CustomPacketPayload {

    static final int MAX_ENTRIES = 512;

    public static final Type<ChestSensePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "chest_sense"));

    public static final StreamCodec<FriendlyByteBuf, ChestSensePacket> STREAM_CODEC =
        StreamCodec.of(ChestSensePacket::write, ChestSensePacket::read);

    public ChestSensePacket {
        sensed = List.copyOf(sensed.size() > MAX_ENTRIES ? sensed.subList(0, MAX_ENTRIES) : sensed);
    }

    private static void write(FriendlyByteBuf buf, ChestSensePacket p) {
        buf.writeVarInt(p.durationTicks);
        buf.writeFloat(p.range);
        buf.writeVarInt(p.sensed.size());
        for (ChestSense.Sensed s : p.sensed) {
            buf.writeBoolean(s.subLevel() != null);
            if (s.subLevel() != null) buf.writeUUID(s.subLevel());
            buf.writeBlockPos(s.pos());
        }
    }

    private static ChestSensePacket read(FriendlyByteBuf buf) {
        int duration = buf.readVarInt();
        float range = buf.readFloat();
        int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
        List<ChestSense.Sensed> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            java.util.UUID sub = buf.readBoolean() ? buf.readUUID() : null;
            list.add(new ChestSense.Sensed(sub, buf.readBlockPos()));
        }
        return new ChestSensePacket(duration, range, list);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ChestSensePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChestSenseClientState.set(packet.sensed(), packet.durationTicks(), packet.range()));
    }
}
