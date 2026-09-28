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
 * Server → client: the builders matching a {@link BuilderCreatorSearchPacket}.
 *
 * <p>{@code query} is echoed back so the search screen can drop an answer to a query the player has
 * since typed past — searches are fired as the field changes, and the network does not promise to
 * answer them in order.</p>
 *
 * <p>{@code found} separates "nobody matched" from "this build cannot search at all" (a release
 * build, an unreachable relay, a player without network consent). Both carry an empty list, and only
 * one of them is worth telling the player to retype.</p>
 */
public record BuilderCreatorResultsPacket(String query, boolean found,
                                          List<Creator> creators) implements CustomPacketPayload {

    /**
     * One builder to offer.
     *
     * @param uuid   what {@link BuilderProfileRequestPacket} then asks about
     * @param name   their display name as of their most recent build, or their uuid when they built
     *               before names were captured
     * @param builds how many builds they have on this relay — the useful thing to sort a list of
     *               strangers by
     */
    public record Creator(String uuid, String name, int builds) {}

    public static final Type<BuilderCreatorResultsPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_creator_results"));

    private static final int MAX_STRING = 64;
    private static final int MAX_ENTRIES = 50;

    public static final StreamCodec<FriendlyByteBuf, BuilderCreatorResultsPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.query, BuilderCreatorSearchPacket.MAX_QUERY);
                buf.writeBoolean(packet.found);
                int n = Math.min(packet.creators.size(), MAX_ENTRIES);
                buf.writeVarInt(n);
                for (int i = 0; i < n; i++) {
                    Creator c = packet.creators.get(i);
                    buf.writeUtf(c.uuid(), MAX_STRING);
                    buf.writeUtf(c.name(), MAX_STRING);
                    buf.writeVarInt(c.builds());
                }
            },
            buf -> {
                String query = buf.readUtf(BuilderCreatorSearchPacket.MAX_QUERY);
                boolean found = buf.readBoolean();
                int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
                List<Creator> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(new Creator(buf.readUtf(MAX_STRING), buf.readUtf(MAX_STRING), buf.readVarInt()));
                }
                return new BuilderCreatorResultsPacket(query, found, List.copyOf(out));
            }
        );

    /** No answer to give: this build cannot search, or the relay could not be asked. */
    public static BuilderCreatorResultsPacket empty(String query) {
        return new BuilderCreatorResultsPacket(query, false, List.of());
    }

    /** The relay's answer — {@code null} being the unreachable case it reports as such. */
    public static BuilderCreatorResultsPacket of(String query, List<SharedCarriageClient.Creator> creators) {
        if (creators == null) return empty(query);
        List<Creator> out = new ArrayList<>(Math.min(creators.size(), MAX_ENTRIES));
        for (SharedCarriageClient.Creator c : creators) {
            if (out.size() >= MAX_ENTRIES) break;
            out.add(new Creator(c.uuid(), c.name(), c.builds()));
        }
        return new BuilderCreatorResultsPacket(query, true, List.copyOf(out));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderCreatorResultsPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderProfileState.creatorResults(packet));
    }
}
