package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.snapshot.RideSnapshotDirector;
import games.brennan.dungeontrain.client.snapshot.SnapshotFraming;
import games.brennan.dungeontrain.client.snapshot.SnapshotTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: a moment worth a ride photo has happened that only the server can see — the
 * player changed a drifting carriage, or arrived inside a Train Dimension. The client queues the
 * matching {@link SnapshotTag} and takes the shot on a later tick.
 *
 * <p>A cue is a <em>suggestion</em>, not an order. It still passes through every gate in
 * {@code RideSnapshotDirector}: the per-tag cooldown, the performance gate, and the render-time
 * framing/lighting checks. A cue the client can't honour right away is retried for a few seconds
 * and then dropped.</p>
 *
 * <p>The reason text rides along because it varies within a cue (a block edit and a left gift are
 * both {@link SnapshotCue#BUILDING}); it feeds the opt-in snapshot chat log only.</p>
 *
 * <p><b>Framing.</b> A {@link SnapshotCue#THRESHOLD} cue also carries the room copy the player is
 * standing in ({@code confine}) and the corridor volumes inside it ({@code exclude}), so the camera
 * can be kept out of the neighbouring copies and out of the twin corridors — see
 * {@link SnapshotFraming}. Empty for cues that don't need it, which is the whole-world default.</p>
 */
public record SnapshotCuePacket(SnapshotCue cue, String reason, Box confine, List<Box> exclude)
        implements CustomPacketPayload {

    /** Cap on the decoded reason — it only ever feeds a debug chat line. */
    private static final int MAX_REASON = 120;
    /**
     * Cap on exclusion boxes. A room copy holds at most the two twin corridors' three volumes each,
     * plus whatever extra corridors a neighbouring copy anchored inside it; a dozen is generous.
     */
    private static final int MAX_EXCLUDE = 12;

    private static final SnapshotCue[] CUES = SnapshotCue.values();

    /** An inclusive world block volume. Sent as integers because every one of these is block-aligned. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        /** As an {@link AABB} — {@code max + 1} because the block at {@code max} is inside the volume. */
        public AABB toAabb() {
            return new AABB(minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0);
        }
    }

    public SnapshotCuePacket {
        exclude = List.copyOf(exclude);
    }

    /** A cue with no framing constraints — the camera is free to go wherever it can see from. */
    public SnapshotCuePacket(SnapshotCue cue, String reason) {
        this(cue, reason, null, List.of());
    }

    public static final Type<SnapshotCuePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "snapshot_cue"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SnapshotCuePacket> STREAM_CODEC =
            StreamCodec.of(SnapshotCuePacket::encode, SnapshotCuePacket::decode);

    private static void encode(RegistryFriendlyByteBuf buf, SnapshotCuePacket p) {
        buf.writeVarInt(p.cue().ordinal());
        buf.writeUtf(p.reason() == null ? "" : p.reason(), MAX_REASON);
        buf.writeBoolean(p.confine() != null);
        if (p.confine() != null) writeBox(buf, p.confine());
        int count = Math.min(p.exclude().size(), MAX_EXCLUDE);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) writeBox(buf, p.exclude().get(i));
    }

    private static SnapshotCuePacket decode(RegistryFriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        String reason = buf.readUtf(MAX_REASON);
        Box confine = buf.readBoolean() ? readBox(buf) : null;
        int count = Math.min(Math.max(0, buf.readVarInt()), MAX_EXCLUDE);
        List<Box> exclude = new ArrayList<>(count);
        for (int i = 0; i < count; i++) exclude.add(readBox(buf));
        // An unknown ordinal means the sender knows a cue this build does not. Dropping it to null
        // lets the handler ignore it, rather than mis-tagging a photo as whatever sits at index 0.
        SnapshotCue cue = ordinal >= 0 && ordinal < CUES.length ? CUES[ordinal] : null;
        return new SnapshotCuePacket(cue, reason, confine, exclude);
    }

    private static void writeBox(RegistryFriendlyByteBuf buf, Box box) {
        buf.writeVarInt(box.minX());
        buf.writeVarInt(box.minY());
        buf.writeVarInt(box.minZ());
        buf.writeVarInt(box.maxX());
        buf.writeVarInt(box.maxY());
        buf.writeVarInt(box.maxZ());
    }

    private static Box readBox(RegistryFriendlyByteBuf buf) {
        return new Box(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
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
        SnapshotFraming framing = SnapshotFraming.of(packet.confine(), packet.exclude());
        ctx.enqueueWork(() -> RideSnapshotDirector.cue(tag, packet.reason(), framing));
    }
}
