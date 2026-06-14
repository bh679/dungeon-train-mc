package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.event.CinematicIntroService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the intro cinematic finished (skipped or timed out) and
 * the player has regained control.
 *
 * <p>Empty payload — the server identifies the sender via
 * {@link IPayloadContext#player()} and clears that player's temporary
 * spawn-invulnerability early. A server-side timer in
 * {@link CinematicIntroService} clears it anyway as a safety net, so a dropped
 * packet or a vanilla client never leaves a player permanently invulnerable.</p>
 */
public record CinematicDonePacket() implements CustomPacketPayload {

    public static final Type<CinematicDonePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "cinematic_done"));

    public static final StreamCodec<FriendlyByteBuf, CinematicDonePacket> STREAM_CODEC =
        StreamCodec.unit(new CinematicDonePacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CinematicDonePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            CinematicIntroService.onClientDone(player);
        });
    }
}
