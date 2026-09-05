package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.EditorDirtyCheck;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Brings one of a player's relay builds back down into this install, so an editor can open it.
 *
 * <p>The inverse of {@link BuilderRelayUpload}, and its opposite in one important way: an upload is a
 * second home for a build that already exists locally, so every failure there is cosmetic. A
 * download is the <em>only</em> copy this install will have, so what it writes has to be right —
 * hence the fold, the conversion and the install are each allowed to fail out loud rather than
 * leaving a half-written template behind.</p>
 *
 * <p>Three phases, split by which thread may do them. The fetch is a network call; the fold and the
 * conversion are pure; the install writes files and touches registries and so is pushed back onto
 * the server thread, exactly as {@code BuilderRelayUpload} pushes its world-data writes there.</p>
 */
public final class BuilderRelayDownload {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuilderRelayDownload() {}

    /**
     * What the player is told happened.
     *
     * <p>Not one blanket failure, because they send the player to different places: a build that is
     * already here needs nothing, one the relay never heard of is gone for good, one that is not
     * theirs is a bug or a stale screen, and a relay that could not be reached is worth trying again
     * in a minute.</p>
     *
     * <p>{@link #UNSAVED_EDITS} is a question rather than a refusal, and the only one raised before
     * anything is read off the wire is written: the template this build would land on has in-world
     * edits nobody has saved, and installing would put them beyond reach. The player answers it and
     * presses again — see {@link #download(ServerPlayer, ServerLevel, int, BuilderRelayInstall.Resolution, String, String, boolean, boolean)}.</p>
     */
    public enum Outcome { INSTALLED, ALREADY_HERE, NAME_TAKEN, UNSAVED_EDITS, NOT_YOURS, GONE, UNAVAILABLE, UNSUPPORTED, FAILED }

    /**
     * What an install produced: the outcome, and — when something landed — enough to name it, so the
     * screen can offer to open the thing that was just written.
     */
    public record Result(Outcome outcome, BuilderPhotoPaths.Kind kind, String id, String subKind,
                         List<String> takenNames) {
        Result(Outcome outcome, BuilderPhotoPaths.Kind kind, String id, String subKind) {
            this(outcome, kind, id, subKind, List.of());
        }

        static Result of(Outcome outcome) {
            return new Result(outcome, null, "", "", List.of());
        }

        /**
         * The same answer, carrying the names this install will not write over.
         *
         * <p>Only worth sending on an outcome that asks the player to name something — see
         * {@link BuilderRelayInstall#takenNames}.</p>
         */
        Result withTakenNames(List<String> names) {
            return new Result(outcome, kind, id, subKind, names);
        }
    }

    /**
     * Pull build {@code relayId} down and install it.
     *
     * <p>Gated on the same two things an upload is ({@link BuilderRelayUpload#canUpload}): the server
     * has to have profiles on, and the player has to have granted network consent. Fail-closed, and
     * the same posture in both directions — a player who has not consented to their builds going up
     * is not asked to accept them coming down either.</p>
     */
    public static CompletableFuture<Result> download(ServerPlayer player, ServerLevel level, int relayId) {
        return download(player, level, relayId, BuilderRelayInstall.Resolution.AS_IS, "", "", false, false);
    }

    /**
     * As {@link #download(ServerPlayer, ServerLevel, int)}, carrying the player's answer to a name
     * this install already uses.
     *
     * <p>Two presses, always: the first comes back {@link Outcome#ALREADY_HERE}, the player chooses,
     * and the second names the choice. The build is fetched again for that second press rather than
     * held between the two — a cached blob would have to be keyed to a player and expired somehow,
     * and this is one HTTP call on a deliberate button press.</p>
     */
    public static CompletableFuture<Result> download(ServerPlayer player, ServerLevel level, int relayId,
                                                     BuilderRelayInstall.Resolution resolution,
                                                     String newName, String ownerUuid, boolean live,
                                                     boolean overwriteUnsaved) {
        if (player == null || level == null || !BuilderRelayUpload.canUpload(player)) {
            return CompletableFuture.completedFuture(Result.of(Outcome.UNAVAILABLE));
        }
        String own = player.getUUID().toString();
        String owner = ownerUuid == null || ownerUuid.isBlank() ? own : ownerUuid.trim();
        boolean mine = owner.equals(own);
        return SharedCarriageClient.fetchBuild(relayId, owner, RelayTarget.of(live))
                .thenCompose(result -> switch (result.status()) {
                    case FORBIDDEN -> CompletableFuture.completedFuture(Result.of(Outcome.NOT_YOURS));
                    case UNKNOWN -> CompletableFuture.completedFuture(Result.of(Outcome.GONE));
                    case ERROR -> CompletableFuture.completedFuture(Result.of(Outcome.UNAVAILABLE));
                    case OK -> onServer(level, () -> install(level, result.build(), resolution, newName, mine,
                            overwriteUnsaved));
                });
    }

    /**
     * Decode, fold, convert and write — everything that touches the disk, on the server thread.
     *
     * <p>The fold is the same rule a leased carriage gets ({@code TrainAssembler.foldLeaseDeltas}),
     * through the same {@link SharedCarriageClient#pendingDeltas}: without it a build that has been
     * edited on somebody's train since its last compaction comes back as it was submitted rather than
     * as it is now.</p>
     */
    private static Result install(ServerLevel level, SharedCarriageClient.BuildFetch build,
                                  BuilderRelayInstall.Resolution resolution, String newName, boolean mine,
                                  boolean overwriteUnsaved) {
        BuilderPhotoPaths.Kind kind = BuilderRelayKinds.kindOf(build.kind());
        if (kind == null || build.buildName().isEmpty()) {
            // A kind this build of the mod does not know, or a build the relay never named. Neither
            // can be filed anywhere, and guessing at a store would put it where nothing looks.
            LOGGER.warn("[DungeonTrain] Builder relay download: id={} is a '{}' named '{}' — nothing to install it as",
                    build.id(), build.kind(), build.buildName());
            return Result.of(Outcome.UNSUPPORTED);
        }

        CompoundTag snapshot;
        try {
            snapshot = fold(CarriageBlockSnapshot.decode(build.blocks()), build);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Builder relay download: id={} would not decode: {}", build.id(), t.toString());
            return Result.of(Outcome.FAILED);
        }

        StructureTemplate template;
        try {
            HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            template = CarriageSnapshotTemplate.toTemplate(snapshot, blocks);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Builder relay download: id={} would not convert: {}", build.id(), t.toString());
            return Result.of(Outcome.FAILED);
        }

        // The name this build will land on, asked before anything is written. Installing over a
        // template whose editor plot holds edits nobody has saved puts those blocks beyond reach —
        // the file is replaced and the next stamp restamps from it — so the player is asked first.
        // A fetch is a read, so answering "no" here leaves both the file and the plot as they were.
        String landsOn = resolution == BuilderRelayInstall.Resolution.LOAD_AS_NEW
                ? newName.trim()
                : build.buildName();
        if (!overwriteUnsaved && hasUnsavedEdits(level, kind, build.subKind(), landsOn)) {
            return new Result(Outcome.UNSAVED_EDITS, kind, landsOn, build.subKind());
        }

        BuilderRelayInstall.Outcome installed = BuilderRelayInstall.install(
                kind, build.buildName(), build.subKind(), build.stage(), template, resolution, newName,
                build.sidecars(), mine);
        // Which name the build ended up under: its own, unless the player asked for it to arrive as
        // something else. This is what the screen opens, so it has to be the name that was written —
        // the same name the unsaved-edits question above was asked about.
        String installedAs = landsOn;
        if (installed != BuilderRelayInstall.Outcome.INSTALLED) {
            Result refusal = new Result(switch (installed) {
                case ALREADY_HERE -> Outcome.ALREADY_HERE;
                case NAME_TAKEN -> Outcome.NAME_TAKEN;
                case UNSUPPORTED -> Outcome.UNSUPPORTED;
                default -> Outcome.FAILED;
            }, kind, build.buildName(), build.subKind());
            // The two outcomes that send the player to a name box are the two worth telling which
            // names are gone — the box can then open on a free one and refuse a used one itself.
            return refusal.outcome() == Outcome.ALREADY_HERE || refusal.outcome() == Outcome.NAME_TAKEN
                    ? refusal.withTakenNames(BuilderRelayInstall.takenNames(kind, build.subKind(), mine))
                    : refusal;
        }

        // Only a build that kept its relay name is still that relay row. A copy loaded under a new
        // name is a new build as far as the relay is concerned — recording the link would point this
        // world's saves of it at a row whose name no longer matches, quietly renaming the original.
        //
        // Somebody ELSE's build is the same case for a stronger reason: the fetch hands back the
        // owner's durable secret, and remembering it would let this world save over their row. A
        // foreign build lands here as a local copy and nothing more.
        if (resolution != BuilderRelayInstall.Resolution.LOAD_AS_NEW && mine) {
            remember(level, build, kind);
        }
        return new Result(Outcome.INSTALLED, kind, installedAs, build.subKind());
    }

    /**
     * Whether the template {@code id} names has in-world edits that have not been saved to disk.
     *
     * <p>The same scan the editor's own "save before switch" list runs
     * ({@link EditorDirtyCheck#unsavedModelIds}), narrowed to the one template a download is about to
     * write over. Answers false for anything with no plot of its own to lose — a part, a carriage
     * group — and for a template nobody has stamped this session, which is what a name this install
     * has never held looks like.</p>
     */
    private static boolean hasUnsavedEdits(ServerLevel level, BuilderPhotoPaths.Kind kind,
                                           String subKind, String id) {
        String categoryId = BuilderRelayKinds.categoryIdFor(kind, subKind);
        String modelId = EditorDirtyCheck.dirtyKeyFor(kind, subKind, id);
        if (categoryId == null || modelId == null) return false;
        return EditorDirtyCheck.unsavedModelIds(level, DungeonTrainWorldData.get(level).dims(), categoryId)
                .contains(modelId);
    }

    /**
     * Record what the relay calls this build, so a later save in THIS world updates that row instead
     * of uploading a second copy of the same build.
     *
     * <p>The secret is what makes that possible and is the reason the fetch returns one: it is the
     * durable owner capability, issued once to whoever first uploaded the build, and no world can
     * re-derive it. The lease token is left empty on purpose — this download took no lease, so the
     * next save claims one, which is exactly the path
     * {@link BuilderRelayUpload#afterSave} already takes for a build it knows but is not holding.</p>
     */
    private static void remember(ServerLevel level, SharedCarriageClient.BuildFetch build,
                                 BuilderPhotoPaths.Kind kind) {
        if (build.secret().isEmpty()) {
            // An older relay, or a build stored before secrets existed. The template is installed and
            // usable; only the link back to its relay row is missing, and a later save re-establishes
            // that by re-uploading (the relay dedupes an identical builder submit per author).
            LOGGER.info("[DungeonTrain] Builder relay download: id={} came back without a secret — "
                    + "installed, but this world cannot save to that row", build.id());
            return;
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        String key = BuilderRelayBuilds.keyOf(BuilderRelayKinds.idOf(kind), build.subKind(), build.buildName());
        data.builderRelayBuilds().put(key,
                new BuilderRelayBuilds.Entry(build.id(), build.secret(), "", build.published()));
        data.markBuilderRelayBuildsDirty();
    }

    /** Fold the build's delta log onto its base blob — the lease path's rule, on a fetched build. */
    static CompoundTag fold(CompoundTag base, SharedCarriageClient.BuildFetch build) {
        List<SharedCarriageClient.DeltaRec> pending =
                SharedCarriageClient.pendingDeltas(build.deltas(), build.baseSeq());
        CompoundTag folded = base;
        for (SharedCarriageClient.DeltaRec d : pending) {
            try {
                folded = CarriageBlockSnapshot.applyDeltaCells(folded, CarriageBlockSnapshot.decode(d.cells()));
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Builder relay download: id={} delta seq={} decode failed: {}",
                        build.id(), d.seq(), e.toString());
            }
        }
        return folded;
    }

    /** Run {@code work} on the server thread and resolve to what it returned. */
    private static CompletableFuture<Result> onServer(ServerLevel level,
                                                      java.util.function.Supplier<Result> work) {
        MinecraftServer server = level.getServer();
        if (server == null) return CompletableFuture.completedFuture(Result.of(Outcome.FAILED));
        CompletableFuture<Result> done = new CompletableFuture<>();
        server.execute(() -> {
            try {
                done.complete(work.get());
            } catch (Throwable t) {
                LOGGER.error("[DungeonTrain] Builder relay download failed", t);
                done.complete(Result.of(Outcome.FAILED));
            }
        });
        return done;
    }
}
