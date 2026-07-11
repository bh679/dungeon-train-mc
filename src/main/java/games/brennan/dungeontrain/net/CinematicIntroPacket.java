package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CinematicCameraController;
import games.brennan.dungeontrain.client.CinematicPreloadGate;
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
 *
 * <p>{@code preloadMaxWaitTicks} is the client-side chunk-preload budget: when
 * {@code > 0} the client shows a short loading screen and holds the cinematic
 * until the terrain around the shot has streamed in (or this many ticks elapse),
 * via {@link CinematicPreloadGate}. {@code 0} starts the cinematic immediately —
 * today's behaviour, used for the on-demand replay where chunks are already
 * loaded. The server sizes its spawn-invulnerability window to cover this wait.</p>
 */
public record CinematicIntroPacket(
        double camX, double camY, double camZ,
        float startYaw, float startPitch,
        double riseHeight, double pullBack, double lookYOffset,
        int durationTicks, int preloadMaxWaitTicks
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
        buf.writeVarInt(preloadMaxWaitTicks);
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
        int preloadWait = buf.readVarInt();
        return new CinematicIntroPacket(cx, cy, cz, yaw, pitch, rise, pull, lookY, dur, preloadWait);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Client-bound handler — only ever runs on the physical client, so the
     * direct references to {@link CinematicCameraController} /
     * {@link CinematicPreloadGate} (client-package classes) are safe (mirrors
     * {@code CarriageNextSpawnPacket.handle}). When a preload budget is set the
     * gate shows a loading screen and defers the cinematic until chunks arrive;
     * otherwise the cinematic starts immediately (replay / preload disabled).
     */
    public static void handle(CinematicIntroPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (packet.preloadMaxWaitTicks() > 0) {
                CinematicPreloadGate.begin(packet);
            } else {
                CinematicCameraController.start(packet);
            }
        });
    }
}
