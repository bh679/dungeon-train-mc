package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: everything this player has starred.
 *
 * <p>The answer to {@link BuilderFavouritesRequestPacket}, and a packet for the reason
 * {@link BuilderProfilePacket} is one — the relay client lives on the server side, so the screen
 * cannot ask the relay itself.</p>
 *
 * <p>Two lists, because a favourite is two different things. The builds are
 * {@link BuilderProfilePacket.Entry}s, the same rows My Builds draws, carrying their own owner because
 * this listing spans them. The builders are {@link Builder}s, the same shape the creator search
 * answers with, so picking one opens a profile exactly as picking a search result does.</p>
 *
 * <p>{@code status} distinguishes an empty list from a relay that couldn't be reached and from a
 * player who has not granted network consent. Collapsing those would tell somebody who declined that
 * they have no favourites, as though the question had been asked and answered.</p>
 */
public record BuilderFavouritesPacket(BuilderProfilePacket.Status status,
                                      List<BuilderProfilePacket.Entry> builds,
                                      List<Builder> builders) implements CustomPacketPayload {

    /**
     * One starred builder — uuid, what to call them, and how much they have built.
     *
     * <p>Deliberately the same three fields {@link BuilderCreatorResultsPacket.Creator} carries: this
     * list and a search result are the same kind of row and are drawn by the same code. The name and
     * the count come from the builder's current rows rather than from when the star was set, so a
     * builder who has since renamed or built more is listed as they are now.</p>
     */
    public record Builder(String uuid, String name, int builds) {}

    public static final Type<BuilderFavouritesPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_favourites"));

    private static final int MAX_STRING = 64;
    private static final int MAX_BUILDERS = 256;

    public static final StreamCodec<FriendlyByteBuf, BuilderFavouritesPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeEnum(packet.status);
                // The build rows go through BuilderProfilePacket's own codec rather than a second copy
                // of it — one writer, so the two packets cannot disagree about what an Entry looks like.
                BuilderProfilePacket.writeEntries(buf, packet.builds);
                int n = Math.min(packet.builders.size(), MAX_BUILDERS);
                buf.writeVarInt(n);
                for (int i = 0; i < n; i++) {
                    Builder b = packet.builders.get(i);
                    buf.writeUtf(b.uuid(), MAX_STRING);
                    buf.writeUtf(b.name(), MAX_STRING);
                    buf.writeVarInt(b.builds());
                }
            },
            buf -> {
                BuilderProfilePacket.Status status = buf.readEnum(BuilderProfilePacket.Status.class);
                List<BuilderProfilePacket.Entry> builds = BuilderProfilePacket.readEntries(buf);
                int n = Math.min(buf.readVarInt(), MAX_BUILDERS);
                List<Builder> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(new Builder(buf.readUtf(MAX_STRING), buf.readUtf(MAX_STRING), buf.readVarInt()));
                }
                return new BuilderFavouritesPacket(status, builds, List.copyOf(out));
            }
        );

    /** A refusal or an outage: no lists, and a status saying which. */
    public static BuilderFavouritesPacket of(BuilderProfilePacket.Status status) {
        return new BuilderFavouritesPacket(status, List.of(), List.of());
    }

    /** Reduce the relay's answer to what the screen draws. */
    public static BuilderFavouritesPacket of(SharedCarriageClient.Favourites favourites) {
        List<BuilderProfilePacket.Entry> builds = new ArrayList<>();
        for (SharedCarriageClient.ProfileBuild r : favourites.builds()) {
            if (builds.size() >= BuilderProfilePacket.maxEntries()) break;
            builds.add(BuilderProfilePacket.entryOf(r));
        }
        List<Builder> builders = new ArrayList<>();
        for (SharedCarriageClient.Creator c : favourites.builders()) {
            if (builders.size() >= MAX_BUILDERS) break;
            builders.add(new Builder(c.uuid(), c.name(), c.builds()));
        }
        return new BuilderFavouritesPacket(BuilderProfilePacket.Status.OK,
                List.copyOf(builds), List.copyOf(builders));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderFavouritesPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderProfileState.acceptFavourites(packet));
    }
}
