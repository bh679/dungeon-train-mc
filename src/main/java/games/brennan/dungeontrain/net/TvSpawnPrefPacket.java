package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.compat.vista.TvSpawnPower;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: whether this player lets spawned TVs come on ({@code LiveFeedClientConfig#tvsSpawnOn},
 * the Live feed row on the Stream and Performance tabs). Sent on login and whenever it changes — see
 * {@code client.TvSpawnPrefSyncClient}. Feeds {@link TvSpawnPower#setAllowed}.
 *
 * <p>Not a trust concern: the only thing a lying client can do is keep TVs off for everyone while it is
 * online, which is cosmetic and which a player could also do by clicking them.</p>
 */
public record TvSpawnPrefPacket(boolean allowSpawnOn) implements CustomPacketPayload {

    public static final Type<TvSpawnPrefPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "tv_spawn_pref"));

    public static final StreamCodec<FriendlyByteBuf, TvSpawnPrefPacket> STREAM_CODEC =
        StreamCodec.of((buf, p) -> buf.writeBoolean(p.allowSpawnOn), buf -> new TvSpawnPrefPacket(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(TvSpawnPrefPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) TvSpawnPower.setAllowed(player.getUUID(), packet.allowSpawnOn);
        });
    }
}
