package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.CinematicPreloadGate;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: the server has given up placing this player on the train, so no
 * {@link CinematicIntroPacket} is coming. Lets {@link CinematicPreloadGate} drop the hold that
 * {@link CinematicPreloadBeginPacket} armed straight away, rather than keeping the loading screen
 * up until the gate's own hard cap, a good 50 s after the server stopped trying.
 */
public record CinematicPreloadEndPacket() implements CustomPacketPayload {

    public static final Type<CinematicPreloadEndPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "cinematic_preload_end"));

    public static final StreamCodec<FriendlyByteBuf, CinematicPreloadEndPacket> STREAM_CODEC =
        StreamCodec.unit(new CinematicPreloadEndPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound, so the direct reference to the client-package gate is safe (as in the begin packet). */
    public static void handle(CinematicPreloadEndPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(CinematicPreloadGate::abandonPlacing);
    }
}
