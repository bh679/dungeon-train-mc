package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.net.platform.DtModId;
import games.brennan.dungeontrain.discord.BugReportSink;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import games.brennan.dungeontrain.net.platform.DtPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: gzipped bug-report logs collected on the death screen when the player reports a
 * bug via the feedback survey's "Did you face any bugs in this run?" question (any answer other than
 * "No"). The server archives them under {@code logs/<version>/<user>/} and posts them as Discord
 * attachments to the feedback feed — see {@link BugReportSink}.
 *
 * <p>Each blob is a small gzipped log tail (latest.log / debug.log) or the newest crash report; the
 * client keeps the payload well under the per-blob cap by tailing each source.</p>
 *
 * <p>{@code systemInfo} carries a short, plain-text system-spec summary collected client-side for a
 * "Lag" report (allocated game memory, CPU/GPU, OS, launcher — see {@code SystemSpecCollector}); it
 * is empty for every other answer. The server folds it into the Discord feedback post — see
 * {@link BugReportSink}.</p>
 */
public record BugReportLogsPacket(String optionLabel, String systemInfo, List<LogBlob> files)
        implements CustomPacketPayload {

    /** One collected log file: its display name and gzipped bytes. */
    public record LogBlob(String filename, byte[] bytes) {}

    /** Per-file (gzipped) byte cap the codec enforces; client tails keep real payloads far smaller. */
    private static final int MAX_BLOB = 4 * 1024 * 1024;
    /** Hard cap on the number of attached files (defends decode against a hostile count). */
    private static final int MAX_FILES = 8;
    /** Defensive cap on the system-spec string on decode (the client builds it well under 2 KB). */
    private static final int MAX_SYSINFO = 8 * 1024;

    public static final Type<BugReportLogsPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DtModId.MOD_ID, "bug_report_logs"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BugReportLogsPacket> STREAM_CODEC =
            StreamCodec.of(BugReportLogsPacket::encode, BugReportLogsPacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, BugReportLogsPacket p) {
        buf.writeUtf(p.optionLabel());
        buf.writeUtf(p.systemInfo() == null ? "" : p.systemInfo());
        int count = Math.min(p.files().size(), MAX_FILES);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            LogBlob b = p.files().get(i);
            buf.writeUtf(b.filename());
            buf.writeByteArray(b.bytes());
        }
    }

    private static BugReportLogsPacket decode(RegistryFriendlyByteBuf buf) {
        String optionLabel = buf.readUtf();
        String systemInfo = buf.readUtf(MAX_SYSINFO);
        int count = Math.min(Math.max(0, buf.readVarInt()), MAX_FILES);
        List<LogBlob> files = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = buf.readUtf();
            byte[] bytes = buf.readByteArray(MAX_BLOB);
            files.add(new LogBlob(name, bytes));
        }
        return new BugReportLogsPacket(optionLabel, systemInfo, List.copyOf(files));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BugReportLogsPacket packet, DtPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            BugReportSink.accept(player, packet.optionLabel(), packet.systemInfo(), packet.files());
        });
    }
}
