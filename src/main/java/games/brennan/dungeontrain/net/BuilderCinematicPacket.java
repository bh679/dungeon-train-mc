package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CinematicCameraController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: play the Train Builder's arrival shot.
 *
 * <p>A fully-resolved camera move — start point, end point, and the fixed world point to hold
 * the aim on — rather than the join intro's rise/pull-back parameters. The two shots want
 * opposite things: the intro tracks a <em>moving</em> player on a train and only knows where the
 * camera should go relative to them, while this one frames a <em>static</em> template and knows
 * both ends of the move up front.</p>
 *
 * <p>Deliberately a separate packet from {@link CinematicIntroPacket}: the join intro is shipped
 * and load-bearing, and bolting an optional focus target onto its wire format would put every
 * builder-only change in the path of the spawn sequence. They share the client-side machinery
 * ({@link CinematicCameraController} — one clock, one skip key, one release blend), which is the
 * part worth sharing.</p>
 *
 * <p>No preload budget: a builder world is a 96-tall void with one platform in it, and the shot
 * is triggered after the world is already up and rendered, so there is nothing to wait for.</p>
 */
public record BuilderCinematicPacket(
        double camStartX, double camStartY, double camStartZ,
        double camEndX, double camEndY, double camEndZ,
        double focusX, double focusY, double focusZ,
        int durationTicks
) implements CustomPacketPayload {

    public static final Type<BuilderCinematicPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_cinematic"));

    public static final StreamCodec<FriendlyByteBuf, BuilderCinematicPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            BuilderCinematicPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(camStartX);
        buf.writeDouble(camStartY);
        buf.writeDouble(camStartZ);
        buf.writeDouble(camEndX);
        buf.writeDouble(camEndY);
        buf.writeDouble(camEndZ);
        buf.writeDouble(focusX);
        buf.writeDouble(focusY);
        buf.writeDouble(focusZ);
        buf.writeVarInt(durationTicks);
    }

    public static BuilderCinematicPacket decode(FriendlyByteBuf buf) {
        double sx = buf.readDouble();
        double sy = buf.readDouble();
        double sz = buf.readDouble();
        double ex = buf.readDouble();
        double ey = buf.readDouble();
        double ez = buf.readDouble();
        double fx = buf.readDouble();
        double fy = buf.readDouble();
        double fz = buf.readDouble();
        int dur = buf.readVarInt();
        return new BuilderCinematicPacket(sx, sy, sz, ex, ey, ez, fx, fy, fz, dur);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Client-bound handler — only ever runs on the physical client, so the direct reference to
     * {@link CinematicCameraController} (a client-package class) is safe, mirroring
     * {@link CinematicIntroPacket#handle}.
     */
    public static void handle(BuilderCinematicPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CinematicCameraController.startFocusShot(packet));
    }
}
