package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.LifeDisqualificationClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Server → client: which advancements are ruled out for the local player's current life.
 *
 * <p>{@code disqualified} is always the <em>full</em> current set (the client replaces its mirror
 * wholesale — no delta bookkeeping to drift). {@code newlyLost} is the subset that flipped in the
 * event that caused this send, empty on a plain resync (login, respawn); the client raises a toast
 * for any of those it is tracking. {@code streakReset} names streak advancements whose count just
 * restarted (chest opened, block broken, repeat chest) — a softer toast for any tracked, never a
 * disqualification. See {@link games.brennan.dungeontrain.advancement.LifeDisqualification}.</p>
 */
public record LifeDisqualifiedPacket(List<ResourceLocation> disqualified,
                                     List<ResourceLocation> newlyLost,
                                     List<ResourceLocation> streakReset) implements CustomPacketPayload {

    public static final Type<LifeDisqualifiedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "life_disqualified"));

    public static final StreamCodec<FriendlyByteBuf, LifeDisqualifiedPacket> STREAM_CODEC = StreamCodec.composite(
        ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), LifeDisqualifiedPacket::disqualified,
        ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), LifeDisqualifiedPacket::newlyLost,
        ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), LifeDisqualifiedPacket::streakReset,
        LifeDisqualifiedPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler — only ever runs on the physical client (mirrors {@link AdvancementsHintPacket#handle}). */
    public static void handle(LifeDisqualifiedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> LifeDisqualificationClient.apply(packet.disqualified(), packet.newlyLost(), packet.streakReset()));
    }
}
