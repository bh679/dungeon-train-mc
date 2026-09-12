package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.client.builder.BuilderProfileState;
import games.brennan.dungeontrain.editor.TemplateLootPrefabs;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Server → client: what became of a download, and what it left behind.
 *
 * <p>An outcome rather than a message, because the screen does two different things with it: it says
 * something to the player, and — when a build actually landed — it can offer to open the thing that
 * was just written. A pre-rendered chat line could do the first and never the second.</p>
 *
 * <p>{@code kind}/{@code id}/{@code subKind} name what was installed and are empty on every outcome
 * where nothing was.</p>
 *
 * <p>{@code takenNames} rides along only on the outcomes that ask the player for a name — every
 * name of that kind this install will not write over, so the screen can suggest a free one and
 * refuse a used one on the spot instead of spending a round trip to be told. Empty otherwise, and
 * never the authority: the server asks again on the press that follows.</p>
 */
public record BuilderProfileDownloadResultPacket(BuilderRelayDownload.Outcome outcome, String kindId,
                                                 String id, String subKind,
                                                 List<String> takenNames,
                                                 List<TemplateLootPrefabs.Conflict> conflicts)
        implements CustomPacketPayload {

    /** As many names as a naming prompt could ever usefully show — a guard on the wire, not a rule. */
    private static final int MAX_TAKEN_NAMES = 512;
    /** As many prefabs as one build may carry (TemplateLootPrefabs.MAX_PER_BUILD). */
    static final int MAX_CONFLICTS = 64;
    /**
     * Cap on one side of one conflict's text — the largest {@code writeUtf} allows. A real prefab is
     * a few hundred bytes; past this the text is cut and the screen says it cannot show it.
     */
    static final int MAX_CONFLICT_TEXT = 32767;

    public BuilderProfileDownloadResultPacket {
        takenNames = takenNames == null ? List.of() : List.copyOf(takenNames);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    /** An answer that asks for no name, and so carries none. */
    public BuilderProfileDownloadResultPacket(BuilderRelayDownload.Outcome outcome, String kindId,
                                              String id, String subKind) {
        this(outcome, kindId, id, subKind, List.of(), List.of());
    }

    /** An answer that asks about names but not prefabs. */
    public BuilderProfileDownloadResultPacket(BuilderRelayDownload.Outcome outcome, String kindId,
                                              String id, String subKind, List<String> takenNames) {
        this(outcome, kindId, id, subKind, takenNames, List.of());
    }

    public static final Type<BuilderProfileDownloadResultPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_download_result"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileDownloadResultPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeEnum(packet.outcome);
                buf.writeUtf(packet.kindId, 16);
                buf.writeUtf(packet.id, 64);
                buf.writeUtf(packet.subKind, 32);
                buf.writeCollection(
                        packet.takenNames.size() > MAX_TAKEN_NAMES
                                ? packet.takenNames.subList(0, MAX_TAKEN_NAMES)
                                : packet.takenNames,
                        (b, name) -> b.writeUtf(name, 32));
                buf.writeCollection(
                        packet.conflicts.size() > MAX_CONFLICTS
                                ? packet.conflicts.subList(0, MAX_CONFLICTS)
                                : packet.conflicts,
                        (b, c) -> {
                            b.writeUtf(c.id(), 32);
                            b.writeUtf(clip(c.localText()), MAX_CONFLICT_TEXT);
                            b.writeUtf(clip(c.incomingText()), MAX_CONFLICT_TEXT);
                        });
            },
            buf -> new BuilderProfileDownloadResultPacket(
                    buf.readEnum(BuilderRelayDownload.Outcome.class),
                    buf.readUtf(16), buf.readUtf(64), buf.readUtf(32),
                    buf.readCollection(size -> new ArrayList<String>(Math.min(size, MAX_TAKEN_NAMES)),
                            b -> b.readUtf(32)),
                    buf.readCollection(size -> new ArrayList<TemplateLootPrefabs.Conflict>(Math.min(size, MAX_CONFLICTS)),
                            b -> new TemplateLootPrefabs.Conflict(b.readUtf(32),
                                    b.readUtf(MAX_CONFLICT_TEXT), b.readUtf(MAX_CONFLICT_TEXT))))
        );

    /**
     * A conflict text the wire can carry. Cut rather than dropped: the screen still lists the
     * prefab and offers the choice, it just cannot show what is inside (a cut file will not parse).
     */
    static String clip(String text) {
        if (text == null) return "";
        return text.length() > MAX_CONFLICT_TEXT ? text.substring(0, MAX_CONFLICT_TEXT) : text;
    }

    /** The wire form of one {@link BuilderRelayDownload.Result}. */
    public static BuilderProfileDownloadResultPacket of(BuilderRelayDownload.Result result) {
        BuilderPhotoPaths.Kind kind = result.kind();
        return new BuilderProfileDownloadResultPacket(result.outcome(),
                kind == null ? "" : kind.id(), result.id(), result.subKind(), result.takenNames(),
                result.conflicts());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileDownloadResultPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> BuilderProfileState.downloadResult(packet));
    }
}
