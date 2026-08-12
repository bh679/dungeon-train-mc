package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector;
import games.brennan.dungeontrain.client.snapshot.SnapshotTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: a moment worth a ride photo has happened that only the server can see — the
 * player changed a drifting carriage, or arrived inside a Train Dimension. The client queues the
 * matching {@link SnapshotTag} and takes the shot on a later tick.
 *
 * <p>A cue is a <em>suggestion</em>, not an order. It still passes through every gate in
 * {@code RideSnapshotDirector}: the per-tag cooldown, the performance gate, and the render-time
 * framing/lighting checks. A cue that can't be honoured right now is dropped rather than queued,
 * the same as the director's own pending flags.</p>
 *
 * <p>The reason text rides along because it varies within a cue (a block edit and a left gift are
 * both {@link SnapshotCue#BUILDING}); it feeds the opt-in snapshot chat log only.</p>
 */
public record SnapshotCuePacket(SnapshotCue cue, String reason) implements CustomPacketPayload {

    /** Cap on the decoded reason — it only ever feeds a debug chat line. */
    private static final int MAX_REASON = 120;

    private static final SnapshotCue[] CUES = SnapshotCue.values();

    public static final Type<SnapshotCuePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "snapshot_cue"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotCuePacket> STREAM_CODEC =
            StreamCodec.of(SnapshotCuePacket::encode, SnapshotCuePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SnapshotCuePacket p) {
        buf.writeVarInt(p.cue().ordinal());
        buf.writeUtf(p.reason() == null ? "" : p.reason(), MAX_REASON);
    }

    private static SnapshotCuePacket decode(RegistryFriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        String reason = buf.readUtf(MAX_REASON);
        // An unknown ordinal means the sender knows a cue this build does not. Dropping it to null
        // lets the handler ignore it, rather than mis-tagging a photo as whatever sits at index 0.
        SnapshotCue cue = ordinal >= 0 && ordinal < CUES.length ? CUES[ordinal] : null;
        return new SnapshotCuePacket(cue, reason);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Client-bound handler — only ever runs on the physical client, so the direct references to
     * the client-only snapshot package are safe (mirrors {@link CaptureEchoPacket#handle}).
     */
    public static void handle(SnapshotCuePacket packet, IPayloadContext ctx) {
        SnapshotCue cue = packet.cue();
        if (cue == null) return; // unknown cue from a newer server — nothing sensible to capture
        SnapshotTag tag = switch (cue) {
            case BUILDING -> SnapshotTag.BUILDING;
            case THRESHOLD -> SnapshotTag.THRESHOLD;
        };
        ctx.enqueueWork(() -> RideSnapshotDirector.cue(tag, packet.reason()));
    }
}
