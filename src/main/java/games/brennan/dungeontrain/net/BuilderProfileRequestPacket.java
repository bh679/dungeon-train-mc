package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayUpload;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: what have I built?
 *
 * <p>Normally an empty ask — the answer is about whoever asked, and the server knows who that is. The
 * reply is a {@link BuilderProfilePacket}, sent once the relay answers rather than in this handler: the
 * fetch is a network call and the server thread does not wait on one.</p>
 *
 * <p>{@code live} is the other dev-build affordance: it points the whole screen at the PRODUCTION
 * relay rather than the one this build writes to, so a developer can look at real players' builds.
 * Same fail-closed rule as below — a release server serves its own relay however the packet was
 * crafted.</p>
 *
 * <p>{@code ownerUuid} is the one exception, and it is a DEV-BUILD affordance: it names somebody else
 * whose builds to list, which is how a developer looks at a player's work to reproduce a problem. It
 * is honoured only on a dev build ({@link DungeonTrain#isDevBuild()}), and a release server answers a
 * packet carrying one with the caller's own profile rather than an error — the safe answer is the
 * ordinary one, so nothing a client sends can widen what it may see.</p>
 *
 * <p>{@code all} is the third of those affordances and the widest: every builder's builds in one
 * answer, addressed to {@link #ALL} rather than to a person. It reads the operator's own listing
 * through the admin URL this machine holds, so a jar without one gets {@code UNAVAILABLE} — the same
 * "couldn't ask" an unreachable relay gives, which is what it is.</p>
 */
public record BuilderProfileRequestPacket(String ownerUuid, boolean live, boolean all)
        implements CustomPacketPayload {

    /**
     * The sentinel {@code ownerUuid} an "everybody's builds" answer is addressed to.
     *
     * <p>Not a uuid any player can hold, so a screen waiting on the pooled listing can tell that
     * answer apart from one about a person — which is the same test {@code isForViewed} already makes
     * for every other profile reply.</p>
     */
    public static final String ALL = "*";

    /** The ordinary ask: my own builds, on this build's own relay. */
    public BuilderProfileRequestPacket() {
        this("", false);
    }

    /** One profile, named or own — everything that is not the pooled listing. */
    public BuilderProfileRequestPacket(String ownerUuid, boolean live) {
        this(ownerUuid, live, false);
    }

    /** Every builder's builds, pooled. A dev-build affordance, like naming somebody else's profile. */
    public static BuilderProfileRequestPacket all(boolean live) {
        return new BuilderProfileRequestPacket(ALL, live, true);
    }

    public static final Type<BuilderProfileRequestPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_request"));

    /** A uuid string is 36 chars; the bound is what a hostile packet may allocate, not a format check. */
    private static final int MAX_UUID = 48;

    /**
     * How many pooled rows one answer carries. The packet's own cap is 512 and the relay's listing has
     * no limit of its own, so the trim happens before the wire rather than on it — otherwise the
     * newest builds would be the ones falling off the end.
     */
    private static final int MAX_ALL = 512;

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileRequestPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeUtf(packet.ownerUuid, MAX_UUID);
                buf.writeBoolean(packet.live);
                buf.writeBoolean(packet.all);
            },
            buf -> new BuilderProfileRequestPacket(buf.readUtf(MAX_UUID), buf.readBoolean(),
                    buf.readBoolean())
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileRequestPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            String owner = viewedOwner(player, packet.ownerUuid);
            String relay = RelayTarget.of(liveRequested(packet.live));
            boolean mine = owner.equals(ownProfile(player));
            // A foreign profile is named by whoever the relay says built those rows, which the screen
            // already knows: it picked the creator. Sending the uuid back is what lets it tell an answer
            // about that player apart from a slower one about somebody else.
            String name = mine ? ownName(player) : "";
            // The same gate the upload uses, but reported one limb at a time: with profiles off, or
            // without this player's network consent, nothing of theirs is on the relay and nothing
            // should be asked about them. Which limb closed decides what the screen can tell them to
            // do about it, so the two are never collapsed into one answer. Addressed to the profile
            // that was asked for, even when that is somebody else's — a refusal the screen cannot
            // recognise as an answer to its own question leaves it saying "loading" for good.
            BuilderProfilePacket.Status blocked = blockedReason(player);
            if (blocked != null) {
                DungeonTrainNet.sendTo(player, BuilderProfilePacket.of(blocked, owner, name, mine));
                return;
            }
            if (allRequested(packet.all)) {
                answerAll(player, liveRequested(packet.live));
                return;
            }
            SharedCarriageClient.listMine(owner, ownProfile(player), relay).thenAccept(rows -> {
                if (player.getServer() == null) return;
                player.getServer().execute(() -> {
                    if (player.hasDisconnected()) return;
                    DungeonTrainNet.sendTo(player, rows == null
                            ? BuilderProfilePacket.of(BuilderProfilePacket.Status.UNAVAILABLE, owner, name, mine)
                            : BuilderProfilePacket.of(rows, owner, name, mine));
                });
            });
        });
    }

    /**
     * Whose profile this request is actually about.
     *
     * <p>Fail-closed by construction: anything other than a dev build asking about a named other
     * player collapses to the caller's own uuid. This is the ONE place that decision is made, so a
     * later caller cannot forget it.</p>
     */
    static String viewedOwner(ServerPlayer player, String requested) {
        return viewedOwner(ownProfile(player), requested, DungeonTrain.isDevBuild());
    }

    /** The rule itself, free of the player and the build it is asked about — see {@link #viewedOwner}. */
    static String viewedOwner(String own, String requested, boolean devBuild) {
        if (requested == null || requested.isBlank()) return own;
        return devBuild ? requested.trim() : own;
    }

    /**
     * Whether this call should pool every builder's builds.
     *
     * <p>The same fail-closed shape as {@link #viewedOwner} and {@link #liveRequested}: a release
     * server answers a packet asking for the pool with the caller's own profile, because the pooled
     * listing is the operator's view of everybody's work and nothing a client sends can widen what it
     * may see.</p>
     */
    static boolean allRequested(boolean requested) {
        return requested && DungeonTrain.isDevBuild();
    }

    /**
     * Everybody's builds, in one answer addressed to {@link #ALL}.
     *
     * <p>Never {@code mine}: the rows span owners, and the one thing the screen must not do with them
     * is offer the author's own actions on somebody else's build. Each row carries its own owner, so
     * whoever draws them can still say whose each is.</p>
     */
    private static void answerAll(ServerPlayer player, boolean live) {
        SharedCarriageClient.listAll(live, MAX_ALL).thenAccept(rows -> {
            if (player.getServer() == null) return;
            player.getServer().execute(() -> {
                if (player.hasDisconnected()) return;
                DungeonTrainNet.sendTo(player, rows == null
                        ? BuilderProfilePacket.of(BuilderProfilePacket.Status.UNAVAILABLE, ALL, "", false)
                        : BuilderProfilePacket.of(rows, ALL, "", false));
            });
        });
    }

    /**
     * Whether this call should address the LIVE relay.
     *
     * <p>The other half of the dev gate, kept beside {@link #viewedOwner} for the same reason: one
     * place decides, and a release build can only ever be told to use its own relay.</p>
     */
    static boolean liveRequested(boolean requested) {
        return requested && DungeonTrain.isDevBuild();
    }

    private static String ownProfile(ServerPlayer player) {
        return player.getUUID().toString();
    }

    private static String ownName(ServerPlayer player) {
        return player.getGameProfile() == null ? "" : player.getGameProfile().getName();
    }

    /**
     * Why this player may not upload, or {@code null} when they may.
     *
     * <p>{@link BuilderRelayUpload#canUpload} stays the authority on the yes/no — this method only
     * decomposes a {@code no} into something the screen can say. Asking it first rather than
     * re-deriving its condition matters: should that gate ever grow a third limb, the worst this can
     * do is name the wrong reason for a refusal, never wave through an upload it would have blocked.</p>
     *
     * <p>Order within the refusal: the server's own switch is named first, because when profiles are
     * off the player's consent is moot and pointing them at their own setting would be a dead end.</p>
     */
    static BuilderProfilePacket.Status blockedReason(ServerPlayer player) {
        if (BuilderRelayUpload.canUpload(player)) return null;
        if (!DungeonTrainConfig.isBuilderProfileEnabled()) {
            return BuilderProfilePacket.Status.DISABLED;
        }
        // Absent from the mirror means the login sync is still in flight, not that they said no.
        return NetworkConsentMirror.isKnown(player.getUUID())
                ? BuilderProfilePacket.Status.NO_CONSENT
                : BuilderProfilePacket.Status.CONSENT_PENDING;
    }
}
