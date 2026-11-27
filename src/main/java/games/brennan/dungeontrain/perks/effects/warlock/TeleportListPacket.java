package games.brennan.dungeontrain.perks.effects.warlock;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server to client: who Teleport Player can call now (online players first, then PlayerMobs met this
 * life, newest first) and the ticks left on the cooldown. Sent when the client asks
 * ({@link TeleportCallPacket#LIST}) and after every call.
 */
public record TeleportListPacket(List<Entry> entries, int cooldownTicks) implements CustomPacketPayload {

    /** Most entries sent; the server's lists are smaller than this anyway. */
    static final int MAX_ENTRIES = 256;

    public record Entry(byte kind, UUID id, String name) {
        public static final byte PLAYER = 0, PLAYER_MOB = 1;
    }

    public static final Type<TeleportListPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "teleport_player_list"));

    public static final StreamCodec<FriendlyByteBuf, TeleportListPacket> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            int n = Math.min(MAX_ENTRIES, p.entries.size());
            buf.writeVarInt(n);
            for (int i = 0; i < n; i++) {
                Entry e = p.entries.get(i);
                buf.writeByte(e.kind());
                buf.writeUUID(e.id());
                buf.writeUtf(e.name(), 64);
            }
            buf.writeVarInt(p.cooldownTicks);
        },
        buf -> {
            int n = Math.min(MAX_ENTRIES, buf.readVarInt());
            List<Entry> list = new ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(new Entry(buf.readByte(), buf.readUUID(), buf.readUtf(64)));
            return new TeleportListPacket(List.copyOf(list), buf.readVarInt());
        });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TeleportListPacket packet, IPayloadContext ctx) {
        if (!FMLEnvironment.dist.isClient()) return;
        ctx.enqueueWork(() -> games.brennan.dungeontrain.client.warlock.TeleportCallClient.onList(packet));
    }
}
