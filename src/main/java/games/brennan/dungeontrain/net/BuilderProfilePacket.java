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
 * Server → client: this player's relay build profile.
 *
 * <p>The answer to {@link BuilderProfileRequestPacket}, and the reason the profile is a packet at all:
 * the relay client lives on the server side, so the screen cannot ask the relay itself. What crosses
 * here is what the relay already reduced to metadata — no blocks blob — and the client draws each tile
 * from the LOCAL template of the same name.</p>
 *
 * <p>{@code status} distinguishes an empty profile from a relay that couldn't be reached, which the
 * screen must not conflate: one says "you haven't uploaded anything", the other says "we don't know".</p>
 */
public record BuilderProfilePacket(Status status, List<Entry> builds) implements CustomPacketPayload {

    /** Why the list is what it is. */
    public enum Status {
        /** The relay answered; {@link #builds} is the whole profile (possibly empty). */
        OK,
        /** The relay could not be reached, or the answer was unusable. */
        UNAVAILABLE,
        /** Profiles are switched off on this server. Nothing the player can change from here. */
        DISABLED,
        /**
         * Profiles are on, but this player's client has not granted network consent, so none of their
         * builds are on the relay. Unlike {@link #DISABLED} this is the player's own setting to flip.
         *
         * <p>Appended last on purpose: the payload codes this enum by ordinal
         * ({@code buf.readEnum}), so a new constant may only ever be added at the end.</p>
         */
        NO_CONSENT,
        /**
         * Consent has not been mirrored yet — the login sync is still in flight. Distinct from
         * {@link #NO_CONSENT} so a race never tells a consenting player that they declined.
         */
        CONSENT_PENDING
    }

    /**
     * One build in the profile.
     *
     * @param relayId    the relay's id — what a publish action names
     * @param kind       the relay's kind string ({@code carriage}, {@code portal_room}, …)
     * @param subKind    the part/track kind, or empty
     * @param buildName  the template name, which is also how the client finds the local file to draw
     * @param published  whether it is on the train
     * @param flag       the moderation verdict, so a withheld build can say why it isn't appearing
     * @param stage      the stage it was authored in, or empty
     * @param changes    how many recorded changes it has — a rough "how much work is in this"
     */
    public record Entry(int relayId, String kind, String subKind, String buildName, boolean published,
                        String flag, String stage, int changes) {}

    public static final Type<BuilderProfilePacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile"));

    /** Bound generously above any real id/name, and low enough that a hostile packet can't allocate wildly. */
    private static final int MAX_STRING = 64;
    private static final int MAX_ENTRIES = 512;

    public static final StreamCodec<FriendlyByteBuf, BuilderProfilePacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeEnum(packet.status);
                int n = Math.min(packet.builds.size(), MAX_ENTRIES);
                buf.writeVarInt(n);
                for (int i = 0; i < n; i++) {
                    Entry e = packet.builds.get(i);
                    buf.writeVarInt(e.relayId());
                    buf.writeUtf(e.kind(), MAX_STRING);
                    buf.writeUtf(e.subKind(), MAX_STRING);
                    buf.writeUtf(e.buildName(), MAX_STRING);
                    buf.writeBoolean(e.published());
                    buf.writeUtf(e.flag(), MAX_STRING);
                    buf.writeUtf(e.stage(), MAX_STRING);
                    buf.writeVarInt(e.changes());
                }
            },
            buf -> {
                Status status = buf.readEnum(Status.class);
                int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
                List<Entry> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(new Entry(buf.readVarInt(), buf.readUtf(MAX_STRING), buf.readUtf(MAX_STRING),
                            buf.readUtf(MAX_STRING), buf.readBoolean(), buf.readUtf(MAX_STRING),
                            buf.readUtf(MAX_STRING), buf.readVarInt()));
                }
                return new BuilderProfilePacket(status, List.copyOf(out));
            }
        );

    public static BuilderProfilePacket of(Status status) {
        return new BuilderProfilePacket(status, List.of());
    }

    /** Reduce the relay's rows to what the screen draws. */
    public static BuilderProfilePacket of(List<SharedCarriageClient.ProfileBuild> rows) {
        List<Entry> out = new ArrayList<>(Math.min(rows.size(), MAX_ENTRIES));
        for (SharedCarriageClient.ProfileBuild r : rows) {
            if (out.size() >= MAX_ENTRIES) break;
            out.add(new Entry(r.id(), r.kind(), r.subKind(), r.buildName(),
                    "published".equals(r.visibility()), r.flag(), r.stage(), r.changeCount()));
        }
        return new BuilderProfilePacket(Status.OK, List.copyOf(out));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfilePacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderProfileState.accept(packet));
    }
}
