package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Server → client: the template-blocks world-space menu's current contents
 * for the plot the player is editing.
 *
 * <p>An "active" packet ({@code active == true}) tells the client to render
 * the panel anchored at {@code anchorPos} with axes {@code anchorRight} /
 * {@code anchorUp}, listing every block used in the plot {@code key} with its
 * usage {@code count}. An "inactive" packet closes the menu. Mirrors
 * {@link BlockVariantSyncPacket}.</p>
 *
 * <p>Block ids are sent as registry strings (e.g. {@code minecraft:oak_stairs});
 * the client resolves them against {@code BuiltInRegistries.BLOCK}, which
 * vanilla guarantees identical on both sides.</p>
 */
public record TemplateBlocksSyncPacket(
    String key,
    boolean active,
    Vec3 anchorPos,
    Vec3 anchorRight,
    Vec3 anchorUp,
    List<Entry> entries
) implements CustomPacketPayload {

    /** One row: a block id and how many times it is used across the plot. */
    public record Entry(String blockId, int count) {}

    public static final Type<TemplateBlocksSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "template_blocks_sync"));

    public static final StreamCodec<FriendlyByteBuf, TemplateBlocksSyncPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            TemplateBlocksSyncPacket::decode
        );

    public static TemplateBlocksSyncPacket empty() {
        return new TemplateBlocksSyncPacket(
            "", false, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Collections.emptyList());
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(key, 128);
        buf.writeBoolean(active);
        if (!active) return;
        writeVec3(buf, anchorPos);
        writeVec3(buf, anchorRight);
        writeVec3(buf, anchorUp);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.blockId(), 256);
            buf.writeVarInt(e.count());
        }
    }

    public static TemplateBlocksSyncPacket decode(FriendlyByteBuf buf) {
        String key = buf.readUtf(128);
        boolean active = buf.readBoolean();
        if (!active) {
            return new TemplateBlocksSyncPacket(
                key, false, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Collections.emptyList());
        }
        Vec3 anchor = readVec3(buf);
        Vec3 right = readVec3(buf);
        Vec3 up = readVec3(buf);
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String blockId = buf.readUtf(256);
            int count = buf.readVarInt();
            entries.add(new Entry(blockId, count));
        }
        return new TemplateBlocksSyncPacket(key, true, anchor, right, up, entries);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TemplateBlocksSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() ->
            games.brennan.dungeontrain.client.menu.templateblocks.TemplateBlocksMenu.applySync(packet));
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
