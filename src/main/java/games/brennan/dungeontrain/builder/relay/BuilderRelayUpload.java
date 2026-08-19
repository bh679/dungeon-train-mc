package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderSave;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.event.NetworkConsentMirror;
import games.brennan.dungeontrain.event.SharedCarriageMode;
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
    private static final int MAX_BLOCKS_CHARS = 690_000;

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
     */
    public static void afterSave(ServerPlayer player, ServerLevel level, BuilderSave.Written written) {
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
            // expired. Take it back first; a save without a token is refused by the relay.
            claimThenSave(player, level, key, known, blocks, text, written);
            return;
        }
        submitNew(player, level, key, blocks, text, written, data);
    }

    /** First upload of this template: create the profile entry and remember what came back. */
    private static void submitNew(ServerPlayer player, ServerLevel level, String key, String blocks, String text,
                                  BuilderSave.Written written, DungeonTrainWorldData data) {
        SharedCarriageClient.submitBuild(
                player.getUUID().toString(), player.getGameProfile().getName(), blocks,
                written.size().getX(), written.size().getY(), written.size().getZ(),
                text, data.builderStage(), poolFor(),
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
                        // Somebody else took the lease. Drop the stale token and try to claim it back.
                        BuilderRelayBuilds.Entry tokenless = entry.withToken("");
                        DungeonTrainWorldData live = DungeonTrainWorldData.get(level);
                        live.builderRelayBuilds().put(key, tokenless);
                        live.markBuilderRelayBuildsDirty();
                        claimThenSave(player, level, key, tokenless, blocks, text, written);
                        return;
                    }
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
     * Put one of this player's builds on the train, or take it back off — the My Builds screen's action.
     *
     * <p>Publishing hands the build to the pool and frees this world's lease with it, so the entry's
     * token is cleared; the next save of that template claims it back. Resolves to the message the
     * screen shows.</p>
     */
    public static CompletableFuture<Component> submitToTrain(ServerPlayer player, ServerLevel level,
                                                             int relayId, boolean publish) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        String key = data.builderRelayBuilds().keyForRelayId(relayId);
        BuilderRelayBuilds.Entry entry = key == null ? null : data.builderRelayBuilds().get(key);
        if (entry == null || entry.secret().isEmpty()) {
            // Not ours to publish: this world never uploaded it, or uploaded it before secrets existed.
            return CompletableFuture.completedFuture(
                    msg("gui.dungeontrain.builder.profile.not_yours", ChatFormatting.YELLOW));
        }
        if (publish && !DungeonTrainConfig.isSharedCarriagesEnabled()) {
            // Nothing leases from the pool while the feature is off, so publishing would put the build
            // somewhere nothing can reach. Say so rather than appearing to succeed.
            return CompletableFuture.completedFuture(
                    msg("gui.dungeontrain.builder.profile.pool_off", ChatFormatting.YELLOW));
        }
        return SharedCarriageClient.publish(relayId, entry.secret(), publish).thenApply(result -> {
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
    private static String poolFor() {
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
