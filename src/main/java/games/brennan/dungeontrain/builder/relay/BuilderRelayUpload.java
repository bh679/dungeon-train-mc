package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderSave;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.event.SharedCarriageMode;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Sends a Train Builder save to the player's relay profile.
 *
 * <p>Runs after {@link BuilderSave} has written the template to disk, and never instead of it: the
 * local file is the build, and the relay copy is a second home for it. So every failure here is
 * reported and dropped — a relay that is down, a build too big for the wire, a lease somebody else is
 * holding — and the player still has exactly what they saved.</p>
 *
 * <p>Two phases, split by which thread may do them. The capture reads the world, so it happens
 * synchronously on the server thread while the blocks are still the ones that were just saved; the
 * upload is a network call and resolves later, back on the server thread, to record what the relay
 * assigned and tell the player.</p>
 *
 * <p>A build goes up <b>private</b>: {@code visibility=profile}, which the relay keeps out of the pool
 * entirely. Putting it on the train is a separate, deliberate act — see
 * {@link #submitToTrain}.</p>
 */
public final class BuilderRelayUpload {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The relay's own cap on a blocks blob is ~700k base64 chars. Refusing a hair below it means the
     * player is told their build is too big by the game, in the builder, rather than by a 400 from a
     * server they can't see.
     */
    static final int MAX_BLOCKS_CHARS = 690_000;

    private BuilderRelayUpload() {}

    /**
     * Whether this player's builder saves may be uploaded at all: the server has to have opted in, and
     * the player's own client has to have granted network consent. Fail-closed, and the same posture
     * {@code SharedCarriageGate.canContribute} takes for in-play contributions.
     */
    public static boolean canUpload(ServerPlayer player) {
        if (player == null) return false;
        return DungeonTrainConfig.isBuilderProfileEnabled() && NetworkConsentMirror.isGranted(player);
    }

    /**
     * Upload what a save just wrote.
     *
     * <p>The first upload of a template {@code submit}s it and keeps the lease the relay hands back —
     * that lease is what an unpublished build IS, on the relay's side, and holding it is also what
     * keeps the row safe from the pool's ring eviction. Later saves of the same template {@code save}
     * through that lease, so a build has one profile entry however many times it is saved.</p>
     *
     * @param stageId the stage the build belongs to, from whichever tool did the saving — a builder
     *                world's current stage, or an editor template's own stage link. A parameter
     *                rather than a read of {@code DungeonTrainWorldData.builderStage()} because that
     *                field describes a builder world and means nothing in an ordinary one.
     */
    public static void afterSave(ServerPlayer player, ServerLevel level, BuilderSave.Written written,
                                 String stageId) {
        if (written == null || !canUpload(player)) return;
        String blocks;
        String text;
        try {
            CarriageBlockSnapshot.Captured captured = CarriageBlockSnapshot.captureLevel(
                    level, written.origin(), written.size(), level.registryAccess());
            blocks = CarriageBlockSnapshot.encode(captured.tag());
            text = captured.text();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Builder relay upload: could not capture '{}': {}", written.id(), t.toString());
            tell(player, "gui.dungeontrain.builder.profile.upload_failed", ChatFormatting.RED, written.id());
            return;
        }
        if (blocks.length() > MAX_BLOCKS_CHARS) {
            LOGGER.info("[DungeonTrain] Builder relay upload: '{}' is {} chars, over the {} limit — kept local only.",
                    written.id(), blocks.length(), MAX_BLOCKS_CHARS);
            tell(player, "gui.dungeontrain.builder.profile.too_big", ChatFormatting.YELLOW, written.id());
            return;
        }

        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        String key = BuilderRelayBuilds.keyOf(BuilderRelayKinds.idOf(written.kind()), written.subKind(), written.id());
        BuilderRelayBuilds.Entry known = data.builderRelayBuilds().get(key);
        if (known != null && !known.token().isEmpty()) {
            saveThrough(player, level, key, known, blocks, text, written);
            return;
        }
        if (known != null) {
            // Known build, but this world isn't holding the lease — it was published, or the lease
            // expired. The owner secret is authority enough to write anyway; only a build uploaded
            // before secrets existed has to go the long way round and take a lease back first.
            if (!known.secret().isEmpty()) {
                ownerSave(player, level, key, known, blocks, text, written);
            } else {
                claimThenSave(player, level, key, known, blocks, text, written);
            }
            return;
        }
        submitNew(player, level, key, blocks, text, written, stageId);
    }

    /**
     * First upload of this template: create the profile entry and remember what came back.
     *
     * <p>Preceded by a check that there is room. A profile at the relay's cap does not reject the
     * next upload — it accepts it and deletes the author's oldest build to make room, silently. This
     * is the one path that adds a row, so it is the one place that can catch that before it happens
     * and let the player choose what goes instead. A save that stops here is still saved locally;
     * only the relay copy is withheld.</p>
     */
    private static void submitNew(ServerPlayer player, ServerLevel level, String key, String blocks, String text,
                                  BuilderSave.Written written, String stageId) {
        SharedCarriageClient.listMine(player.getUUID().toString(), player.getUUID().toString(),
                        RelayTarget.dev())
                .thenAccept(builds -> onServer(level, () -> {
                    // A failed listing is not evidence of a full profile. Upload rather than block —
                    // the relay is the authority, and refusing a save because a check could not be
                    // made would be the worse failure.
                    if (builds != null && BuilderProfileCap.isFull(BuilderProfileCap.used(builds))) {
                        tell(player, "gui.dungeontrain.builder.profile.full", ChatFormatting.YELLOW,
                                BuilderProfileCap.MAX_PROFILE_BUILDS);
                        return;
                    }
                    submitNewNow(player, level, key, blocks, text, written, stageId);
                }));
    }

    /** The upload itself, once there is known to be room for it. */
    private static void submitNewNow(ServerPlayer player, ServerLevel level, String key, String blocks,
                                     String text, BuilderSave.Written written, String stageId) {
        SharedCarriageClient.submitBuild(
                player.getUUID().toString(), player.getGameProfile().getName(), blocks,
                written.size().getX(), written.size().getY(), written.size().getZ(),
                text, stageId == null ? "" : stageId, poolFor(),
                BuilderRelayKinds.idOf(written.kind()), written.subKind(), written.id(), "profile")
                .thenAccept(result -> onServer(level, () -> {
                    if (result.isEmpty()) {
                        tell(player, "gui.dungeontrain.builder.profile.upload_failed", ChatFormatting.RED, written.id());
                        return;
                    }
                    SharedCarriageClient.BuildUpload up = result.get();
                    DungeonTrainWorldData live = DungeonTrainWorldData.get(level);
                    live.builderRelayBuilds().put(key,
                            new BuilderRelayBuilds.Entry(up.id(), up.secret(), up.token(), false));
                    live.markBuilderRelayBuildsDirty();
                    tell(player, "gui.dungeontrain.builder.profile.saved", ChatFormatting.GRAY, written.id());
                }));
    }

    /** A later save of a template this world still holds the lease on. */
    private static void saveThrough(ServerPlayer player, ServerLevel level, String key,
                                    BuilderRelayBuilds.Entry entry, String blocks, String text,
                                    BuilderSave.Written written) {
        SharedCarriageClient.save(entry.relayId(), entry.token(), blocks, text, 0)
                .thenAccept(status -> onServer(level, () -> {
                    if (status == SharedCarriageClient.CallStatus.OK) {
                        tell(player, "gui.dungeontrain.builder.profile.saved", ChatFormatting.GRAY, written.id());
                        return;
                    }
                    if (status == SharedCarriageClient.CallStatus.UNKNOWN) {
                        // The relay no longer has this build (evicted, or an admin removed it). Forget
                        // it here too, so the next save uploads it fresh instead of retrying forever.
                        DungeonTrainWorldData live = DungeonTrainWorldData.get(level);
                        live.builderRelayBuilds().remove(key);
                        live.markBuilderRelayBuildsDirty();
                        tell(player, "gui.dungeontrain.builder.profile.gone", ChatFormatting.YELLOW, written.id());
                        return;
                    }
                    if (status == SharedCarriageClient.CallStatus.FORBIDDEN) {
                        // Somebody else took the lease. Drop the stale token and write as the owner
                        // instead — the same route afterSave takes for a build it holds no lease on.
                        BuilderRelayBuilds.Entry tokenless = entry.withToken("");
                        DungeonTrainWorldData live = DungeonTrainWorldData.get(level);
                        live.builderRelayBuilds().put(key, tokenless);
                        live.markBuilderRelayBuildsDirty();
                        if (!tokenless.secret().isEmpty()) {
                            ownerSave(player, level, key, tokenless, blocks, text, written);
                        } else {
                            claimThenSave(player, level, key, tokenless, blocks, text, written);
                        }
                        return;
                    }
                    LOGGER.warn("[DungeonTrain] Builder relay upload: saving '{}' through its lease failed — {}",
                            written.id(), status);
                    tell(player, "gui.dungeontrain.builder.profile.upload_failed", ChatFormatting.RED, written.id());
                }));
    }

    /**
     * Save a build this world holds no lease on, as its owner.
     *
     * <p>The author's save is authoritative and nobody may block it. Taking a lease first — which is
     * what this path used to do — meant any stranger out riding a published build answered
     * {@code in_use}, and the player was told their build "is out on someone's train" for a save that
     * had already succeeded locally and would never sync however many times they repeated it. A lease
     * is a drifting-carriage rule, for two worlds editing one carriage mid-ride; an authored template
     * has one owner, and the {@code secret} the relay issued at submit is that ownership.</p>
     *
     * <p>Nobody is displaced: the relay writes the blob and leaves the rider's lease alone.</p>
     */
    private static void ownerSave(ServerPlayer player, ServerLevel level, String key,
                                  BuilderRelayBuilds.Entry entry, String blocks, String text,
                                  BuilderSave.Written written) {
        SharedCarriageClient.ownerSave(entry.relayId(), entry.secret(), blocks, text, 0)
                .thenAccept(status -> onServer(level, () -> {
                    if (status == SharedCarriageClient.CallStatus.OK) {
                        tell(player, "gui.dungeontrain.builder.profile.saved", ChatFormatting.GRAY, written.id());
                        return;
                    }
                    if (status == SharedCarriageClient.CallStatus.UNKNOWN) {
                        // A 404 is either the build being gone or a relay too old to have the route,
                        // and this call can't tell them apart. The lease path can — its own 404 is
                        // unambiguous — so hand over to it rather than guessing.
                        claimThenSave(player, level, key, entry, blocks, text, written);
                        return;
                    }
                    LOGGER.warn("[DungeonTrain] Builder relay upload: saving '{}' as its owner failed — {}",
                            written.id(), status);
                    tell(player, "gui.dungeontrain.builder.profile.upload_failed", ChatFormatting.RED, written.id());
                }));
    }

    /**
     * Take the lease back, then save through it.
     *
     * <p>The one outcome that is not an error: another world is holding the build right now — someone
     * is playing with a carriage this player published. Their local save already happened, so this says
     * so and stops, rather than yanking a carriage out from under a live session.</p>
     */
    private static void claimThenSave(ServerPlayer player, ServerLevel level, String key,
                                      BuilderRelayBuilds.Entry entry, String blocks, String text,
                                      BuilderSave.Written written) {
        SharedCarriageClient.claim(entry.relayId(), entry.secret(),
                        player.getUUID().toString(), player.getGameProfile().getName())
                .thenAccept(claim -> onServer(level, () -> {
                    if (claim.status() == SharedCarriageClient.CallStatus.OK && !claim.token().isEmpty()) {
                        DungeonTrainWorldData live = DungeonTrainWorldData.get(level);
                        BuilderRelayBuilds.Entry leased = entry.withToken(claim.token());
                        live.builderRelayBuilds().put(key, leased);
                        live.markBuilderRelayBuildsDirty();
                        saveThrough(player, level, key, leased, blocks, text, written);
                        return;
                    }
                    if (claim.inUse()) {
                        tell(player, "gui.dungeontrain.builder.profile.in_use", ChatFormatting.YELLOW, written.id());
                        return;
                    }
                    if (claim.status() == SharedCarriageClient.CallStatus.UNKNOWN) {
                        DungeonTrainWorldData live = DungeonTrainWorldData.get(level);
                        live.builderRelayBuilds().remove(key);
                        live.markBuilderRelayBuildsDirty();
                        tell(player, "gui.dungeontrain.builder.profile.gone", ChatFormatting.YELLOW, written.id());
                        return;
                    }
                    tell(player, "gui.dungeontrain.builder.profile.upload_failed", ChatFormatting.RED, written.id());
                }));
    }

    /**
     * Offer one of this player's builds to the operator, or take it back — the My Builds screen's action.
     *
     * <p>Publishing hands the build to the relay's submission queue and frees this world's lease with
     * it, so the entry's token is cleared; the next save of that template claims it back. Resolves to
     * the message the screen shows.</p>
     *
     * <p>Every kind may be submitted. The pool refusal in {@link #publishWith} is the one thing that is
     * still a carriage question: a carriage is what a train slot holds, so submitting one to a world
     * whose pool is off would be asking for something that world has switched away — while a portal
     * room or a shell part is asking to be looked at, which the pool has no say over.</p>
     *
     * <p>A build this world has no secret for is not refused: {@link #adopt} recovers one from the
     * relay first, so what can be submitted is what the player owns rather than what this particular
     * world happened to upload.</p>
     */
    public static CompletableFuture<Component> submitToTrain(ServerPlayer player, ServerLevel level,
                                                             int relayId, boolean publish) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        String key = data.builderRelayBuilds().keyForRelayId(relayId);
        BuilderRelayBuilds.Entry entry = key == null ? null : data.builderRelayBuilds().get(key);
        if (entry == null || entry.secret().isEmpty()) {
            // This world has no secret for the build. Recover one rather than refuse — see adopt().
            return adopt(player, level, relayId, publish);
        }
        return publishWith(level, key, entry, BuilderRelayBuilds.kindOfKey(key), publish);
    }

    /**
     * Submit a build this world holds no secret for, by asking the relay for one.
     *
     * <p>A build's secret is issued once, to whoever uploaded it, and is kept in the uploading world's
     * saved data — so "your build" and "a build this world uploaded" are not the same set. They come
     * apart routinely: the title-screen reconcile ({@code BuilderReconcileRunner}) restores builds to
     * the relay before any world is loaded and has nowhere to write the secrets it gets back, and a
     * build uploaded from one world is listed by My Builds in every world. Refusing those with "this
     * world didn't upload that build" made a build the player owns, and is looking at, unsubmittable
     * for reasons they cannot see or fix.</p>
     *
     * <p>{@code /carriages/fetch} is authorised on the owner's uuid and returns the secret for exactly
     * that reason — it is the same call and the same reasoning behind
     * {@link BuilderRelayDownload}'s remember step, which links a downloaded build to the world that
     * pulled it. The relay refuses a build that is not this player's, so ownership is still checked
     * where it is actually known.</p>
     *
     * <p>It costs a fetch of the whole blocks blob to read one field, which is why this is the fallback
     * and not the path: a world that uploaded the build answers from its own saved data.</p>
     */
    private static CompletableFuture<Component> adopt(ServerPlayer player, ServerLevel level,
                                                      int relayId, boolean publish) {
        String owner = player == null ? "" : player.getUUID().toString();
        return SharedCarriageClient.fetchBuild(relayId, owner).thenCompose(result -> {
            SharedCarriageClient.BuildFetch build = result.build();
            Adoption verdict = adoptionOf(result.status(), build);
            if (verdict == Adoption.GONE) {
                return CompletableFuture.completedFuture(
                        msg("gui.dungeontrain.builder.profile.gone_short", ChatFormatting.YELLOW));
            }
            if (verdict == Adoption.NOT_YOURS) {
                return CompletableFuture.completedFuture(
                        msg("gui.dungeontrain.builder.profile.not_yours", ChatFormatting.YELLOW));
            }
            String key = BuilderRelayBuilds.keyOf(build.kind(), build.subKind(), build.buildName());
            // No lease token: this adoption took none, so the next save of the template claims one —
            // the path afterSave already takes for a build it knows but is not holding.
            BuilderRelayBuilds.Entry adopted =
                    new BuilderRelayBuilds.Entry(build.id(), build.secret(), "", build.published());
            return publishWith(level, key, adopted, build.kind(), publish);
        });
    }

    /** What a fetch made in {@link #adopt} means for the submission that asked for it. */
    enum Adoption {
        /** The build is this player's and came back with its secret: file it and carry on. */
        ADOPT,
        /**
         * No claim on that row. FORBIDDEN (somebody else's build), a garbled answer and a build
         * stored before secrets existed all land here — from the player's side they are one thing:
         * this is not a build they can submit, and no retry changes that.
         */
        NOT_YOURS,
        /** The relay has no such id — evicted, or deleted by an operator. */
        GONE
    }

    /**
     * The decision above, as a pure function of what the relay said — the part worth pinning in a test.
     *
     * <p>Split out for the same reason {@code BuilderRelayReconcile.classify} is: the call around it
     * needs a live relay and a loaded world, while the reading of its answer is three cases that must
     * not drift.</p>
     */
    static Adoption adoptionOf(SharedCarriageClient.CallStatus status, SharedCarriageClient.BuildFetch build) {
        if (status == SharedCarriageClient.CallStatus.UNKNOWN) return Adoption.GONE;
        if (status != SharedCarriageClient.CallStatus.OK || build == null) return Adoption.NOT_YOURS;
        return build.secret().isEmpty() ? Adoption.NOT_YOURS : Adoption.ADOPT;
    }

    /** The publish call itself, once a secret is in hand — the tail both paths above share. */
    private static CompletableFuture<Component> publishWith(ServerLevel level, String key,
                                                            BuilderRelayBuilds.Entry entry,
                                                            String kindId, boolean publish) {
        if (publish && BuilderRelayKinds.canJoinTheTrain(kindId)
                && !DungeonTrainConfig.isSharedCarriagesEnabled()) {
            // Nothing leases from the pool while the feature is off, so publishing a carriage would put
            // it somewhere nothing can reach. Say so rather than appearing to succeed. Every other kind
            // is submitted to be read by a person, not spawned, so the switch does not speak for it.
            return CompletableFuture.completedFuture(
                    msg("gui.dungeontrain.builder.profile.pool_off", ChatFormatting.YELLOW));
        }
        return SharedCarriageClient.publish(entry.relayId(), entry.secret(), publish).thenApply(result -> {
            if (result.ok()) {
                onServer(level, () -> {
                    DungeonTrainWorldData live = DungeonTrainWorldData.get(level);
                    // Publishing frees the lease; withdrawing hands a fresh one back.
                    live.builderRelayBuilds().put(key,
                            entry.withPublished(publish).withToken(publish ? "" : result.token()));
                    live.markBuilderRelayBuildsDirty();
                });
                return msg(publish ? "gui.dungeontrain.builder.profile.published"
                        : "gui.dungeontrain.builder.profile.withdrawn", ChatFormatting.GREEN);
            }
            if (result.inUse()) return msg("gui.dungeontrain.builder.profile.in_use_withdraw", ChatFormatting.YELLOW);
            if (result.status() == SharedCarriageClient.CallStatus.UNKNOWN) {
                return msg("gui.dungeontrain.builder.profile.gone_short", ChatFormatting.YELLOW);
            }
            return msg("gui.dungeontrain.builder.profile.action_failed", ChatFormatting.RED);
        });
    }

    /**
     * Which relay pool a builder upload joins: always the <b>normal</b> one.
     *
     * <p>{@link SharedCarriageMode} would say {@code freeplay} for every builder world, because a
     * builder world is creative and any creative player taints a world's pool. That rule is about
     * cheated <em>runs</em> not contaminating legitimate ones — but the Train Builder is the authoring
     * tool, where creative is the whole point, and reading it as cheating would quarantine every build
     * ever made in it away from the trains it was made for.</p>
     */
    static String poolFor() {
        return SharedCarriageMode.NORMAL;
    }

    /** Run on the server thread — every world-data write below happens there, not on an HTTP callback. */
    private static void onServer(ServerLevel level, Runnable action) {
        MinecraftServer server = level.getServer();
        if (server == null) return;
        server.execute(action);
    }

    private static void tell(ServerPlayer player, String key, ChatFormatting colour, Object arg) {
        if (player == null) return;
        player.sendSystemMessage(Component.translatable(key, arg).withStyle(colour));
    }

    private static Component msg(String key, ChatFormatting colour) {
        return Component.translatable(key).withStyle(colour);
    }

    /** A snapshot tag's size, for tests and diagnostics — the wire form is what the relay caps. */
    static int encodedLength(CompoundTag tag) throws java.io.IOException {
        return CarriageBlockSnapshot.encode(tag).length();
    }
}
