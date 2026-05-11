package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PackageListClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server → client: a snapshot of the dtpacks state.
 *
 * <p>One {@link Entry} per package (including the synthetic unsaved
 * pseudo-package). Each entry includes a per-subdir map of content
 * basenames so the contents pane can render without making an
 * additional round trip.</p>
 *
 * <p>Wire format limits: package names ≤ 64 chars, basenames ≤ 96 chars,
 * subdir slugs ≤ 32 chars. UTF readUtf/writeUtf checks enforce these.
 * The total payload size is bounded by NeoForge's default protocol
 * frame size (~2 MB) which comfortably handles dozens of packages with
 * hundreds of files each.</p>
 */
public record PackageListSyncPacket(List<Entry> entries) implements CustomPacketPayload {

    public record Entry(String name, boolean hasZip, boolean enabled, boolean isActive,
                        Map<String, List<String>> contentsBySubdir) {}

    public static final Type<PackageListSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "package_list_sync"));

    public static final StreamCodec<FriendlyByteBuf, PackageListSyncPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            PackageListSyncPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeUtf(e.name(), 64);
            buf.writeBoolean(e.hasZip());
            buf.writeBoolean(e.enabled());
            buf.writeBoolean(e.isActive());
            buf.writeVarInt(e.contentsBySubdir().size());
            for (Map.Entry<String, List<String>> sub : e.contentsBySubdir().entrySet()) {
                buf.writeUtf(sub.getKey(), 32);
                List<String> names = sub.getValue();
                buf.writeVarInt(names.size());
                for (String name : names) {
                    buf.writeUtf(name, 96);
                }
            }
        }
    }

    public static PackageListSyncPacket decode(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String name = buf.readUtf(64);
            boolean hasZip = buf.readBoolean();
            boolean enabled = buf.readBoolean();
            boolean isActive = buf.readBoolean();
            int subdirs = buf.readVarInt();
            Map<String, List<String>> contents = new LinkedHashMap<>(subdirs);
            for (int s = 0; s < subdirs; s++) {
                String subdir = buf.readUtf(32);
                int items = buf.readVarInt();
                List<String> names = new ArrayList<>(items);
                for (int j = 0; j < items; j++) {
                    names.add(buf.readUtf(96));
                }
                contents.put(subdir, names);
            }
            out.add(new Entry(name, hasZip, enabled, isActive, contents));
        }
        return new PackageListSyncPacket(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Stash the latest snapshot on the client. The next menu rebuild reads it. */
    public static void handle(PackageListSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PackageListClient.setSnapshot(packet));
    }
}
