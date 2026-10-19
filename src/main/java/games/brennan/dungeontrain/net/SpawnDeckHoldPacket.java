package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.SpawnDeckHold;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server → client: hold the local player on the train deck for a short window
 * after an on-train spawn.
 *
 * <p><b>Why.</b> On login / respawn (especially a fresh world load) the server
 * thread stalls for seconds running the eager-fill appender, while the client
 * thread keeps ticking at 20 TPS and free-falls the local player off the just-
 * teleported deck onto the world track bed under the train. Because the local
 * player's movement is client-authoritative, the cure has to run on the client:
 * for {@code durationTicks} client ticks the client refuses to let the player
 * sink below {@code deckTopY}, so they ride the deck until the server catches up
 * and Sable's collision-carry engages. See {@link SpawnDeckHold}.</p>
 *
 * <p>{@code deckTopY} is the world-Y of the deck surface ({@code trainY + 1});
 * Y is constant for the train's life (velocity is +X only), so a single value
 * is valid for the whole hold even as the train slides forward underneath.</p>
 *
 * <p>{@code trainBlocksPerTick} is the train's +X speed. While the clamp is
 * holding the player up (Sable's collision has not reached the client yet) the
 * client moves them forward by it each tick, so the train cannot slide out from
 * under them. {@code /dtp} sends it; the other on-train spawns send 0.</p>
 */
public record SpawnDeckHoldPacket(double deckTopY, int durationTicks, double trainBlocksPerTick)
    implements CustomPacketPayload {

    /** Default hold length (~6 s at 20 TPS) — comfortably outlasts the spawn-storm stall. */
    public static final int DEFAULT_HOLD_TICKS = 120;

    /** A hold that only clamps Y (login, respawn, portal rescue). */
    public SpawnDeckHoldPacket(double deckTopY, int durationTicks) {
        this(deckTopY, durationTicks, 0.0);
    }

    public static final Type<SpawnDeckHoldPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "spawn_deck_hold"));

    public static final StreamCodec<FriendlyByteBuf, SpawnDeckHoldPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> packet.encode(buf),
            SpawnDeckHoldPacket::decode
        );

    public void encode(FriendlyByteBuf buf) {
        buf.writeDouble(deckTopY);
        buf.writeVarInt(durationTicks);
        buf.writeDouble(trainBlocksPerTick);
    }

    public static SpawnDeckHoldPacket decode(FriendlyByteBuf buf) {
        double y = buf.readDouble();
        int dur = buf.readVarInt();
        double vx = buf.readDouble();
        return new SpawnDeckHoldPacket(y, dur, vx);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Client-bound handler — only ever runs on the physical client, so the
     * direct reference to {@link SpawnDeckHold} (a client-package class) is safe
     * (mirrors {@code CinematicIntroPacket.handle}).
     */
    public static void handle(SpawnDeckHoldPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> SpawnDeckHold.begin(
            packet.deckTopY(), packet.durationTicks(), packet.trainBlocksPerTick()));
    }
}
