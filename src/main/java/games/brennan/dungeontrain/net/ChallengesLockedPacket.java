package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.ChallengesLockedClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/**
 * Server → client: the Challenges-tab advancements that can't be earned right now because this is
 * multiplayer — empty when they can. Always the full set; the client replaces its mirror wholesale.
 * See {@link games.brennan.dungeontrain.advancement.MultiplayerChallengeGate} and
 * {@link games.brennan.dungeontrain.event.MultiplayerChallengeSync}.
 */
public record ChallengesLockedPacket(List<ResourceLocation> locked) implements CustomPacketPayload {

    public static final Type<ChallengesLockedPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "challenges_locked"));

    public static final StreamCodec<FriendlyByteBuf, ChallengesLockedPacket> STREAM_CODEC = StreamCodec.composite(
        ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list()), ChallengesLockedPacket::locked,
        ChallengesLockedPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler — only ever runs on the physical client (mirrors {@link LifeDisqualifiedPacket#handle}). */
    public static void handle(ChallengesLockedPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> ChallengesLockedClient.apply(packet.locked()));
    }
}
