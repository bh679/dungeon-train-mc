package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.editor.PackageInfo;
import games.brennan.dungeontrain.editor.PackageRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client → server: the Package menu is open and the client needs the
 * current dtpacks state. Server responds with a
 * {@link PackageListSyncPacket} addressed to the requesting player.
 *
 * <p>Empty payload — the server already knows who's asking via
 * {@link DtPayloadContext#player()}.</p>
 */
public record PackageListRequestPacket() implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<PackageListRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "package_list_request"));

    public static final StreamCodec<FriendlyByteBuf, PackageListRequestPacket> STREAM_CODEC =
        StreamCodec.of((buf, packet) -> {}, buf -> new PackageListRequestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Server-side handler: assemble the current state into a sync packet
     * and send it back to the requester. Walks each package's working
     * folder to enumerate content basenames by subdir — the contents
     * pane on the client renders directly from that.
     *
     * <p>Limited to OP players for parity with the rest of the editor
     * menu — non-OP players have no business inspecting or mutating the
     * package set.</p>
     */
    public static void handle(PackageListRequestPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) {
                LOGGER.debug("[DungeonTrain] PackageListRequest: dropping — no ServerPlayer in context");
                return;
            }
            if (!player.hasPermissions(2)) {
                LOGGER.debug("[DungeonTrain] PackageListRequest: dropping non-OP request from {}",
                    player.getName().getString());
                return;
            }
            PackageListSyncPacket sync = build();
            LOGGER.debug("[DungeonTrain] PackageListRequest: sending sync to {} ({} packages)",
                player.getName().getString(), sync.entries().size());
            DungeonTrainNet.sendTo(player, sync);
        });
    }

    /** Build a sync packet snapshotting the current registry state. */
    public static PackageListSyncPacket build() {
        List<PackageInfo> all = PackageRegistry.all();
        PackageInfo active = PackageRegistry.active();
        List<PackageListSyncPacket.Entry> entries = new ArrayList<>(all.size());
        for (PackageInfo p : all) {
            Map<String, List<String>> contents = enumerateContents(p.workingDir());
            entries.add(new PackageListSyncPacket.Entry(
                p.name(),
                p.hasZip(),
                PackageRegistry.isEnabledByName(p.name()),
                p.name().equals(active.name()),
                contents
            ));
        }
        return new PackageListSyncPacket(entries);
    }

    /**
     * Walk the package's working folder and return a map of
     * {@code subdir → sorted basenames}. Used by the contents pane
     * (one section per subdir, one row per basename).
     *
     * <p>Subdirs that nest (parts/&lt;kind&gt;, pillars/&lt;section&gt;,
     * tunnels/&lt;variant&gt;) are flattened into
     * {@code <kind>:<basename>} entries under the top-level subdir slug
     * — same convention the legacy user-templates pane used.</p>
     */
    private static Map<String, List<String>> enumerateContents(Path workingDir) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (!Files.isDirectory(workingDir)) return out;
        for (PackageContents.Section section : PackageContents.SECTIONS) {
            List<String> names = collectNames(workingDir.resolve(section.subdir()));
            if (!names.isEmpty()) {
                out.put(section.subdir(), names);
            }
        }
        return out;
    }

    private static List<String> collectNames(Path dir) {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String filename = entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    // parts/<kind>/, pillars/<section>/, tunnels/<variant>/ — descend one level.
                    try (DirectoryStream<Path> inner = Files.newDirectoryStream(entry)) {
                        for (Path file : inner) {
                            if (!Files.isRegularFile(file)) continue;
                            String stripped = stripExt(file.getFileName().toString());
                            if (stripped != null) out.add(filename + ":" + stripped);
                        }
                    } catch (IOException ignored) {}
                } else if (Files.isRegularFile(entry)) {
                    String stripped = stripExt(filename);
                    if (stripped != null) out.add(stripped);
                }
            }
        } catch (IOException ignored) {}
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static String stripExt(String filename) {
        int dot = filename.indexOf('.');
        if (dot <= 0) return null;
        return filename.substring(0, dot).toLowerCase(Locale.ROOT);
    }
}
