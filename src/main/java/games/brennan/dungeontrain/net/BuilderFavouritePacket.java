package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: star this, or take the star off it.
 *
 * <p>Either a build ({@code relayId > 0}) or a builder (a non-empty {@code builderUuid}), which are
 * the two things the grid and the creator search offer a star on. One packet rather than two because
 * the authorisation, the gate and the relay call shape are identical and only the row differs.</p>
 *
 * <p><b>Who is starring is never on the wire.</b> Unlike {@link BuilderProfileActionPacket} — which
 * needs the build's durable owner secret, because publishing acts on somebody's build and the
 * credential is what says it may — a favourite is an act BY the person setting it, on their own
 * private list. So the server takes the uuid from the connected player and there is no field here for
 * a client to put a different one in. A packet cannot star as somebody else because it cannot say who
 * it is.</p>
 *
 * <p>Fire-and-forget: nothing is sent back. The screen flips its star the moment it is clicked and the
 * packet follows, because a star that waits on a round trip through the server to the relay before it
 * fills in reads as a broken button. The relay's answer only matters when it is a refusal, and
 * {@link BuilderFavouritesRequestPacket} is what re-reads the truth.</p>
 */
public record BuilderFavouritePacket(int relayId, String builderUuid, boolean favourite, boolean live)
        implements CustomPacketPayload {

    /** Star or un-star one build. */
    public static BuilderFavouritePacket forBuild(int relayId, boolean favourite, boolean live) {
        return new BuilderFavouritePacket(relayId, "", favourite, live);
    }

    /** Star or un-star one builder. */
    public static BuilderFavouritePacket forBuilder(String builderUuid, boolean favourite, boolean live) {
        return new BuilderFavouritePacket(0, builderUuid == null ? "" : builderUuid, favourite, live);
    }

    public static final Type<BuilderFavouritePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_favourite"));

    /** A uuid string is 36 chars; the bound is what a hostile packet may allocate, not a format check. */
    private static final int MAX_UUID = 48;

    public static final StreamCodec<FriendlyByteBuf, BuilderFavouritePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeUtf(packet.builderUuid, MAX_UUID);
                buf.writeBoolean(packet.favourite);
                buf.writeBoolean(packet.live);
            },
            buf -> new BuilderFavouritePacket(buf.readVarInt(), buf.readUtf(MAX_UUID),
                    buf.readBoolean(), buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderFavouritePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            // The same fail-closed gate every other relay write goes through: with profiles off, or
            // without this player's network consent, nothing of theirs reaches the relay — and a
            // favourites list is exactly the kind of thing a player who declined must not be quietly
            // building on somebody's server.
            if (!BuilderRelayUpload.canUpload(player)) return;
            String uuid = player.getUUID().toString();
            String relay = RelayTarget.of(BuilderProfileRequestPacket.liveRequested(packet.live));
            if (packet.relayId > 0) {
                SharedCarriageClient.setFavourite(uuid, packet.relayId, packet.favourite, relay);
            } else if (!packet.builderUuid.isBlank()) {
                // Starring a builder is only reachable from the creator search, which is a dev-build
                // screen — so this arm is gated the same way rather than trusting that nothing on a
                // release build sends it.
                if (!DungeonTrain.isDevBuild()) return;
                SharedCarriageClient.setBuilderFavourite(uuid, packet.builderUuid.trim(),
                        packet.favourite, relay);
            }
        });
    }
}
