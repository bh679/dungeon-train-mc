package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.building.Buildings;
import games.brennan.dungeontrain.editor.EditorCategory;
import games.brennan.dungeontrain.editor.EditorPlotArrival;
import games.brennan.dungeontrain.editor.EditorStampQueue;
import games.brennan.dungeontrain.editor.EditorStampedCategoryState;
import games.brennan.dungeontrain.editor.WorkbenchEditor;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagedBuild;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagingStore;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Bring one of the relay's builds down onto the editor's <b>Workbench</b> — staged, not installed.
 *
 * <p>The sibling of {@link BuilderRelayDownload}: the same fetch ({@link BuilderRelayDownload#fetch}),
 * the same fold, and then a different landing. A download writes the build into its kind's store and
 * registers it; a stage writes it to the Workbench shelf ({@link WorkbenchStagingStore}) where no
 * registry looks, so the author can stand in it, edit it and only then decide what kind it is
 * ({@code WorkbenchCommit}).</p>
 *
 * <p>Never switches the resident category. When the Workbench is resident the build is stamped beside
 * the player there and then; otherwise it waits on the shelf and the player is told to open the
 * Workbench — which lays it out with the rest.</p>
 */
public final class BuilderRelayStage {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** As tall as a Workbench plot may be — the Buildings layer's own ceiling, which the Workbench shares. */
    public static final int MAX_HEIGHT = Buildings.MAX_SIZE.getY();

    private BuilderRelayStage() {}

    /** Fetch {@code relayId} and put it on the Workbench. The answer names the staged id in {@code Result.id}. */
    public static CompletableFuture<BuilderRelayDownload.Result> stage(ServerPlayer player, ServerLevel level,
                                                                       int relayId, String ownerUuid,
                                                                       String ownerName, boolean live) {
        return BuilderRelayDownload.fetch(player, level, relayId, ownerUuid, live).thenCompose(fetched -> fetched.failed()
                ? CompletableFuture.completedFuture(BuilderRelayDownload.Result.of(fetched.failure()))
                : BuilderRelayDownload.onServer(level, () -> stageFetched(player, level, fetched, ownerName)));
    }

    /** Decode, fold and shelve — on the server thread. */
    static BuilderRelayDownload.Result stageFetched(ServerPlayer player, ServerLevel level,
                                                    BuilderRelayDownload.Fetched fetched, String ownerName) {
        SharedCarriageClient.BuildFetch build = fetched.build();
        BuilderPhotoPaths.Kind kind = BuilderRelayKinds.kindOf(build.kind());
        if (kind == null || build.buildName().isEmpty()) {
            LOGGER.warn("[DungeonTrain] Workbench: relay id={} is a '{}' named '{}' — nothing to stage it as",
                    build.id(), build.kind(), build.buildName());
            return BuilderRelayDownload.Result.of(BuilderRelayDownload.Outcome.UNSUPPORTED);
        }
        CompoundTag snapshot;
        try {
            snapshot = BuilderRelayDownload.fold(CarriageBlockSnapshot.decode(build.blocks()), build);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Workbench: relay id={} would not decode: {}", build.id(), t.toString());
            return BuilderRelayDownload.Result.of(BuilderRelayDownload.Outcome.FAILED);
        }
        Vec3i size = sizeOf(snapshot, build);
        if (size.getY() > MAX_HEIGHT) {
            return new BuilderRelayDownload.Result(BuilderRelayDownload.Outcome.TOO_TALL, kind, build.buildName(), build.subKind());
        }
        String stagedId = WorkbenchStagingStore.freeId(build.buildName(), build.id());
        WorkbenchStagedBuild staged = new WorkbenchStagedBuild(stagedId, build.id(), build.kind(), build.subKind(),
                build.buildName(), build.stage(), fetched.owner(), ownerName == null ? "" : ownerName, fetched.mine(),
                size, build.sidecars(), build.lootPrefabs(), System.currentTimeMillis());
        try {
            WorkbenchStagingStore.write(staged, snapshot);
        } catch (Exception e) {
            LOGGER.error("[DungeonTrain] Workbench: could not shelve relay id={}", build.id(), e);
            return BuilderRelayDownload.Result.of(BuilderRelayDownload.Outcome.FAILED);
        }
        ServerLevel overworld = level.getServer().overworld();
        if (!EditorStampedCategoryState.isActive(EditorCategory.WORKBENCH)) {
            LOGGER.info("[DungeonTrain] Workbench: staged '{}' (relay id={}); the Workbench is not resident",
                    stagedId, build.id());
            return new BuilderRelayDownload.Result(BuilderRelayDownload.Outcome.STAGED_NOT_SHOWING, kind, stagedId, build.subKind());
        }
        // A fill still queued would stamp into whatever slot this takes; let it finish first.
        if (EditorStampQueue.isBusy()) EditorStampQueue.flush();
        BlockPos origin = WorkbenchEditor.allocate(overworld, staged, player.blockPosition().getX());
        WorkbenchEditor.stampAt(overworld, staged, origin);
        EditorPlotArrival.land(player, overworld, origin, size, true, EditorPlotArrival.Inside.FRONT_DOOR, null);
        LOGGER.info("[DungeonTrain] Workbench: staged '{}' (relay id={}) at {}", stagedId, build.id(), origin);
        return new BuilderRelayDownload.Result(BuilderRelayDownload.Outcome.STAGED, kind, stagedId, build.subKind());
    }

    /** The snapshot's own {@code l/h/w}, falling back to the relay row's when the tag lacks them. */
    static Vec3i sizeOf(CompoundTag snapshot, SharedCarriageClient.BuildFetch build) {
        int l = snapshot.contains("l") ? snapshot.getInt("l") : build.l();
        int h = snapshot.contains("h") ? snapshot.getInt("h") : build.h();
        int w = snapshot.contains("w") ? snapshot.getInt("w") : build.w();
        if (l != build.l() || h != build.h() || w != build.w()) {
            LOGGER.info("[DungeonTrain] Workbench: relay id={} says {}x{}x{} but its blocks say {}x{}x{} — using the blocks",
                    build.id(), build.l(), build.h(), build.w(), l, h, w);
        }
        return new Vec3i(Math.max(1, l), Math.max(1, h), Math.max(1, w));
    }
}
