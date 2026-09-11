package games.brennan.dungeontrain.net;

import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.builder.relay.BuilderRelayDownload;
import games.brennan.dungeontrain.builder.relay.BuilderRelayInstall;
import games.brennan.dungeontrain.builder.relay.BuilderRelaySubVariant;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server: bring one of my relay builds down into this install's template library.
 *
 * <p>Carries the relay id and, on a second press, what to do about a name this install already uses
 * ({@code resolution}) and the name the player picked for it ({@code name}).</p>
 *
 * <p>{@code overwriteUnsaved} is the player's answer to the other second-press question: the
 * template this build lands on has unsaved editor edits, and installing replaces it. False on every
 * first press, so the question is always asked before anything is written.</p>
 *
 * <p>{@code ownerUuid} names whose build it is, and like
 * {@link BuilderProfileRequestPacket#ownerUuid} it is honoured on a dev build only — a release server
 * downloads the caller's own build instead, so nothing a client sends widens what it may fetch.
 * A foreign build installs as a local copy: the link back to its relay row is deliberately not
 * recorded, so a later save here can never overwrite the original.</p>
 *
 * <p>{@code parentId} names the variant parent to file the build under as a sub-variant once it is
 * installed — blank for a plain top-level load. Honoured for the kinds that have sub-variants
 * (contents, portal rooms) and ignored for the rest; see {@link BuilderRelaySubVariant}.</p>
 *
 * <p>The answer comes back as a {@link BuilderProfileDownloadResultPacket} once the relay has
 * answered, rather than from this handler — the fetch is a network call and the server thread does
 * not wait on one.</p>
 */
public record BuilderProfileDownloadPacket(int relayId, BuilderRelayInstall.Resolution resolution,
                                           String name, String ownerUuid, String ownerName,
                                           boolean live, boolean overwriteUnsaved, String parentId,
                                           boolean prefabsResolved, List<String> prefabOverwrite)
        implements CustomPacketPayload {

    /** As many prefabs as one build may carry — the guard on the wire (TemplateLootPrefabs.MAX_PER_BUILD). */
    static final int MAX_PREFAB_IDS = 64;

    public BuilderProfileDownloadPacket {
        ownerName = ownerName == null ? "" : ownerName;
        parentId = parentId == null ? "" : parentId;
        prefabOverwrite = prefabOverwrite == null ? List.of() : List.copyOf(prefabOverwrite);
    }

    /** As the canonical constructor, before the loot-prefab question has been asked. */
    public BuilderProfileDownloadPacket(int relayId, BuilderRelayInstall.Resolution resolution,
                                        String name, String ownerUuid, String ownerName,
                                        boolean live, boolean overwriteUnsaved, String parentId) {
        this(relayId, resolution, name, ownerUuid, ownerName, live, overwriteUnsaved, parentId, false, List.of());
    }

    /**
     * This packet again, carrying the player's answer to {@link BuilderRelayDownload.Outcome#PREFAB_CONFLICT}:
     * the ids to write over this install's own. Everything else — the name, the edits answer, the
     * parent — is exactly as the press that raised the question sent it, so the replay lands on the
     * same template.
     */
    public BuilderProfileDownloadPacket answeringPrefabs(java.util.Collection<String> overwrite) {
        return new BuilderProfileDownloadPacket(relayId, resolution, name, ownerUuid, ownerName, live,
                overwriteUnsaved, parentId, true, List.copyOf(overwrite));
    }

    /** As the canonical constructor, for a plain top-level load. */
    public BuilderProfileDownloadPacket(int relayId, BuilderRelayInstall.Resolution resolution,
                                        String name, String ownerUuid, String ownerName,
                                        boolean live, boolean overwriteUnsaved) {
        this(relayId, resolution, name, ownerUuid, ownerName, live, overwriteUnsaved, "");
    }

    /** The first press on one of my own builds: install it, unless the name is already in use here. */
    public BuilderProfileDownloadPacket(int relayId) {
        this(relayId, BuilderRelayInstall.Resolution.AS_IS, "", "", "", false, false, "");
    }

    /**
     * The first press on a build in the profile being viewed — which may be somebody else's, and may
     * be on the live relay. Both come from the screen that listed it, so a build is always fetched
     * from the pool it was shown in.
     *
     * <p>{@code ownerName} is the display name that screen was captioning the build with. It is a
     * label, not an identity — {@code ownerUuid} is what the relay is asked about, and what a
     * recorded credit is keyed on — so a client saying something odd here can misspell a byline and
     * nothing more. Empty when the screen never knew a name.</p>
     */
    public BuilderProfileDownloadPacket(int relayId, String ownerUuid, String ownerName, boolean live) {
        this(relayId, BuilderRelayInstall.Resolution.AS_IS, "", ownerUuid, ownerName, live, false, "");
    }

    public static final Type<BuilderProfileDownloadPacket> TYPE =
        new Type<>(ResourceLocation.fromNamespaceAndPath(DungeonTrain.MOD_ID, "builder_profile_download"));

    public static final StreamCodec<FriendlyByteBuf, BuilderProfileDownloadPacket> STREAM_CODEC =
        StreamCodec.of(
            (buf, packet) -> {
                buf.writeVarInt(packet.relayId);
                buf.writeEnum(packet.resolution);
                buf.writeUtf(packet.name, 64);
                buf.writeUtf(packet.ownerUuid, 48);
                buf.writeUtf(packet.ownerName, 64);
                buf.writeBoolean(packet.live);
                buf.writeBoolean(packet.overwriteUnsaved);
                buf.writeUtf(packet.parentId, 64);
                buf.writeBoolean(packet.prefabsResolved);
                buf.writeCollection(
                        packet.prefabOverwrite.size() > MAX_PREFAB_IDS
                                ? packet.prefabOverwrite.subList(0, MAX_PREFAB_IDS)
                                : packet.prefabOverwrite,
                        (b, id) -> b.writeUtf(id, 32));
            },
            buf -> new BuilderProfileDownloadPacket(buf.readVarInt(),
                    buf.readEnum(BuilderRelayInstall.Resolution.class), buf.readUtf(64), buf.readUtf(48),
                    buf.readUtf(64), buf.readBoolean(), buf.readBoolean(), buf.readUtf(64),
                    buf.readBoolean(),
                    buf.readCollection(size -> new ArrayList<String>(Math.min(size, MAX_PREFAB_IDS)),
                            b -> b.readUtf(32)))
        );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BuilderProfileDownloadPacket packet, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (player.getServer() == null) return;
            ServerLevel level = player.getServer().overworld();
            String owner = BuilderProfileRequestPacket.viewedOwner(player, packet.ownerUuid);
            boolean live = BuilderProfileRequestPacket.liveRequested(packet.live);
            BuilderRelayDownload.PrefabAnswer prefabs = packet.prefabsResolved
                    ? BuilderRelayDownload.PrefabAnswer.resolved(packet.prefabOverwrite)
                    : BuilderRelayDownload.PrefabAnswer.UNASKED;
            BuilderRelayDownload.download(player, level, packet.relayId, packet.resolution, packet.name,
                            owner, packet.ownerName, live, packet.overwriteUnsaved, packet.parentId, prefabs)
                    .thenAccept(result -> player.getServer().execute(() -> {
                        if (player.hasDisconnected()) return;
                        DungeonTrainNet.sendTo(player, BuilderProfileDownloadResultPacket.of(result));
                    }));
        });
    }
}
