package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CinematicCameraController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: start the spawn intro cinematic for the joining player.
 *
 * <p>The player's body has already been placed on a flatbed deck of the
 * (moving) train server-side. This packet drives a purely client-side,
 * locked "spectator-style" camera: it detaches the render camera, places it
 * at the OLD ground spawn pose ({@code cam*} + {@code start*}, facing the
 * train), then over {@code durationTicks} flies up by {@code riseHeight} and
 * eases back by {@code pullBack} while continuously aiming at the local
 * player (offset up by {@code lookYOffset}) — so the character and train stay
 * framed as the train carries them forward.</p>
 *
 * <p>No look target is sent: the client tracks its own local player each
 * frame (see {@link CinematicCameraController}). Space skips; any other input
 * reveals a "Press Space to skip" prompt.</p>
 */
public record CinematicIntroPacket(
        double camX, double camY, double camZ,
        float startYaw, float startPitch,
        double riseHeight, double pullBack, double lookYOffset,
        int durationTicks
) implements CustomPacketPayload {

    public static final Type<CinematicIntroPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "cinematic_intro"));

    public static final StreamCodec<FriendlyByteBuf, CinematicIntroPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            CinematicIntroPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(camX);
        buf.writeDouble(camY);
        buf.writeDouble(camZ);
        buf.writeFloat(startYaw);
        buf.writeFloat(startPitch);
        buf.writeDouble(riseHeight);
        buf.writeDouble(pullBack);
        buf.writeDouble(lookYOffset);
        buf.writeVarInt(durationTicks);
    }

    public static CinematicIntroPacket decode(FriendlyByteBuf buf) {
        double cx = buf.readDouble();
        double cy = buf.readDouble();
        double cz = buf.readDouble();
        float yaw = buf.readFloat();
        float pitch = buf.readFloat();
        double rise = buf.readDouble();
        double pull = buf.readDouble();
        double lookY = buf.readDouble();
        int dur = buf.readVarInt();
        return new CinematicIntroPacket(cx, cy, cz, yaw, pitch, rise, pull, lookY, dur);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Client-bound handler — only ever runs on the physical client, so the
     * direct reference to {@link CinematicCameraController} (a client-package
     * class) is safe (mirrors {@code CarriageNextSpawnPacket.handle}).
     */
    public static void handle(CinematicIntroPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CinematicCameraController.start(packet));
    }
}
