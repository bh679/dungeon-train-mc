package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.PerkClientLives;
import games.brennan.dungeontrain.perks.PerkLifeHistory;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: the perks each of the receiving player's past lives activated
 * ({@link PerkLifeHistory}), for the nap sky's past-life paths. Sent on login and respawn while perks
 * are on ({@code PerkLifecycle}). At most {@link PerkLifeHistory#MAX_LIVES} lives; the reader bounds
 * every count so a bad packet can't allocate much.
 */
public record PerkLivesPacket(PerkLifeHistory history) implements CustomPacketPayload {

    public static final Type<PerkLivesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "perk_lives"));

    public static final StreamCodec<FriendlyByteBuf, PerkLivesPacket> STREAM_CODEC =
        StreamCodec.of(PerkLivesPacket::write, PerkLivesPacket::read);

    private static void write(FriendlyByteBuf buf, PerkLivesPacket p) {
        List<List<String>> lives = p.history == null ? List.of() : p.history.lives();
        buf.writeVarInt(lives.size());
        for (List<String> life : lives) {
            buf.writeVarInt(life.size());
            for (String id : life) buf.writeUtf(id, PerkUnlockPacket.MAX_ID_CHARS);
        }
    }

    private static PerkLivesPacket read(FriendlyByteBuf buf) {
        int n = bounded(buf.readVarInt(), PerkLifeHistory.MAX_LIVES);
        List<List<String>> lives = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int m = bounded(buf.readVarInt(), PerkLifeHistory.MAX_PERKS_PER_LIFE);
            List<String> life = new ArrayList<>(m);
            for (int j = 0; j < m; j++) life.add(buf.readUtf(PerkUnlockPacket.MAX_ID_CHARS));
            lives.add(life);
        }
        return new PerkLivesPacket(PerkLifeHistory.of(lives));
    }

    private static int bounded(int n, int max) {
        if (n < 0 || n > max) throw new IllegalArgumentException("perk lives count out of range: " + n);
        return n;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-bound handler; only runs on the physical client. */
    public static void handle(PerkLivesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> PerkClientLives.apply(packet.history()));
    }
}
