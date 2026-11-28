package games.brennan.dungeontrain.perks.effects.archivist.follow;

import games.brennan.dungeontrain.DungeonTrain;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the player's followed authors (newest first) with this life's finds, the names
 * the Follow box autocompletes from, and the last name lookup. Sent on login, after every follow
 * change, and when the Followed Authors menu opens. Never carries a book count.
 */
public record FollowsSyncPacket(List<Row> rows, List<String> names, String lookupName, Lookup lookup)
    implements CustomPacketPayload {

    /** One follow as the menu shows it. */
    public record Row(String token, String name, boolean exhausted, int found) {}

    /** The last Follow-box lookup: none yet, waiting on the relay, found, not found, or the relay unreachable. */
    public enum Lookup { NONE, PENDING, FOUND, NOT_FOUND, UNREACHABLE }

    public static final Type<FollowsSyncPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "follows_sync"));

    /** Caps on what a packet may carry, so a bad sender can't make the client allocate without bound. */
    static final int MAX_ROWS = 64;
    static final int MAX_NAMES = 64;

    public static final StreamCodec<FriendlyByteBuf, FollowsSyncPacket> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeVarInt(Math.min(p.rows.size(), MAX_ROWS));
            for (Row r : p.rows.subList(0, Math.min(p.rows.size(), MAX_ROWS))) {
                buf.writeUtf(r.token, 64);
                buf.writeUtf(r.name, 64);
                buf.writeBoolean(r.exhausted);
                buf.writeVarInt(r.found);
            }
            buf.writeVarInt(Math.min(p.names.size(), MAX_NAMES));
            for (String n : p.names.subList(0, Math.min(p.names.size(), MAX_NAMES))) buf.writeUtf(n, 64);
            buf.writeUtf(p.lookupName, 64);
            buf.writeEnum(p.lookup);
        },
        buf -> {
            int n = Math.min(buf.readVarInt(), MAX_ROWS);
            List<Row> rows = new ArrayList<>();
            for (int i = 0; i < n; i++) rows.add(new Row(buf.readUtf(64), buf.readUtf(64), buf.readBoolean(), buf.readVarInt()));
            int m = Math.min(buf.readVarInt(), MAX_NAMES);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < m; i++) names.add(buf.readUtf(64));
            return new FollowsSyncPacket(rows, names, buf.readUtf(64), buf.readEnum(Lookup.class));
        });

    public FollowsSyncPacket {
        rows = List.copyOf(rows);
        names = List.copyOf(names);
        lookupName = lookupName == null ? "" : lookupName;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FollowsSyncPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> FollowsClientState.set(packet));
    }
}
