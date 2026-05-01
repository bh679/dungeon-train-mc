package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server → client: container-contents menu state for one cell.
 *
 * <p>Active packet ({@code localPos != null}): render the menu anchored at
 * {@code anchorPos} with axes {@code anchorRight}/{@code anchorUp},
 * showing {@code entries} as the pool for the targeted container.</p>
 *
 * <p>Inactive packet ({@code localPos == null}): close the menu.</p>
 */
public record ContainerContentsSyncPacket(
    String plotKey,
    @Nullable BlockPos localPos,
    List<Entry> entries,
    int fillMin,
    int fillMax,
    int containerSize,
    Vec3 anchorPos,
    Vec3 anchorRight,
    Vec3 anchorUp
) implements CustomPacketPayload {

    /**
     * Single pool entry on the wire — item registry id, max stack count, weight.
     * {@code count} is a max — roll-time produces a uniform-random value in {@code [1, count]}.
     */
    public record Entry(String itemId, int count, int weight) {}

    public static final Type<ContainerContentsSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "container_contents_sync"));

    public static final StreamCodec<FriendlyByteBuf, ContainerContentsSyncPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            ContainerContentsSyncPacket::decode
        );

    public static ContainerContentsSyncPacket empty() {
        return new ContainerContentsSyncPacket(
            "", null, Collections.emptyList(), 0, -1, 0,
            Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(plotKey, 128);
        if (localPos == null) {
            buf.writeBoolean(false);
            return;
        }
        buf.writeBoolean(true);
        buf.writeVarInt(localPos.getX());
        buf.writeVarInt(localPos.getY());
        buf.writeVarInt(localPos.getZ());
        buf.writeInt(fillMin);
        buf.writeInt(fillMax);
        buf.writeVarInt(containerSize);
        writeVec3(buf, anchorPos);
        writeVec3(buf, anchorRight);
        writeVec3(buf, anchorUp);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.itemId(), 256);
            buf.writeVarInt(e.count());
            buf.writeVarInt(e.weight());
        }
    }

    public static ContainerContentsSyncPacket decode(FriendlyByteBuf buf) {
        String key = buf.readUtf(128);
        boolean active = buf.readBoolean();
        if (!active) {
            return new ContainerContentsSyncPacket(
                key, null, Collections.emptyList(), 0, -1, 0,
                Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);
        }
        BlockPos local = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        int fillMin = buf.readInt();
        int fillMax = buf.readInt();
        int containerSize = buf.readVarInt();
        Vec3 anchor = readVec3(buf);
        Vec3 right = readVec3(buf);
        Vec3 up = readVec3(buf);
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = buf.readUtf(256);
            int count = buf.readVarInt();
            int weight = buf.readVarInt();
            entries.add(new Entry(id, count, weight));
        }
        return new ContainerContentsSyncPacket(key, local, entries, fillMin, fillMax, containerSize, anchor, right, up);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ContainerContentsSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.containercontents.ContainerContentsMenu.applySync(packet));
    }

    private static void writeVec3(FriendlyByteBuf buf, Vec3 v) {
        buf.writeDouble(v.x);
        buf.writeDouble(v.y);
        buf.writeDouble(v.z);
    }

    private static Vec3 readVec3(FriendlyByteBuf buf) {
        return new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
