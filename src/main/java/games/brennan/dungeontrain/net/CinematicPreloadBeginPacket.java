package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CinematicPreloadGate;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client, sent at <em>login</em> when the spawn intro cinematic is going
 * to play: open the loading screen and freeze the local player the moment they
 * enter the world, before the server has finished placing them on the train.
 *
 * <p><b>Why.</b> The server can only teleport the player onto the train once it
 * has settled (a few ticks after join). Until then the player is standing at the
 * ground spawn pose with a live camera and falls through not-yet-collided terrain
 * ("stuck falling"). This packet arms {@link CinematicPreloadGate} in its
 * <em>placing</em> phase: it holds the loading screen up and locks the player's
 * position until the follow-up {@link CinematicIntroPacket} arrives (placement
 * done), which hands off to the chunk-wait. {@code placeTimeoutTicks} is the
 * safety cap — if the train never appears the client releases and drops the
 * player into the world.</p>
 */
public record CinematicPreloadBeginPacket(int placeTimeoutTicks) implements CustomPacketPayload {

    public static final Type<CinematicPreloadBeginPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "cinematic_preload_begin"));

    public static final StreamCodec<FriendlyByteBuf, CinematicPreloadBeginPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> buf.writeVarInt(packet.placeTimeoutTicks),
            buf -> new CinematicPreloadBeginPacket(buf.readVarInt())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Client-bound handler — only ever runs on the physical client, so the direct
     * reference to {@link CinematicPreloadGate} (a client-package class) is safe
     * (mirrors {@code CinematicIntroPacket.handle}).
     */
    public static void handle(CinematicPreloadBeginPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> CinematicPreloadGate.arm(packet.placeTimeoutTicks()));
    }
}
