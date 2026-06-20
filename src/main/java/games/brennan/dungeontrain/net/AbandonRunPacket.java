package games.brennan.dungeontrain.net;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

/**
 * Client → server: the player chose "Abandon This Run" on the pause menu and
 * wants to end the current run immediately.
 *
 * <p>Empty payload — the server identifies the sender via
 * {@link IPayloadContext#player()} and kills them with the generic kill source
 * (the same path {@code /kill} uses). That fires {@code LivingDeathEvent}, so
 * {@link games.brennan.dungeontrain.event.RunStatsEvents} sends the
 * {@link DeathStatsPacket} and the client opens the death screen —
 * {@link games.brennan.dungeontrain.client.DeathScreenLayoutHandler} swaps it
 * for the narrative recap, exactly like a normal in-run death.</p>
 *
 * <p>{@code genericKill} bypasses invulnerability, so this also ends a creative
 * / Free Play run. The client closes the pause screen <em>before</em> sending
 * (see {@code PauseMenuLayoutHandler}) so the integrated server is unpaused and
 * can process the kill.</p>
 */
public record AbandonRunPacket() implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final Type<AbandonRunPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "abandon_run"));

    public static final StreamCodec<FriendlyByteBuf, AbandonRunPacket> STREAM_CODEC =
        StreamCodec.unit(new AbandonRunPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(AbandonRunPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.isDeadOrDying()) return;
            LOGGER.info("[DungeonTrain] {} abandoned the run from the pause menu", player.getGameProfile().getName());
            player.kill();
        });
    }
}
