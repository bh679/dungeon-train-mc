package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Puts back builds the relay has lost.
 *
 * <p>A build is a local file first and a relay row second — {@link BuilderRelayUpload} says so, and
 * that asymmetry is what makes this possible. The relay's pool is capped and evicts to stay under the
 * cap, with no tombstone and no archive; between 2026-08-18 and 08-30 a 2,000-row cap took roughly
 * 25,000 carriages with it. Nothing noticed, because the only thing that ever discovers a build has
 * gone is the <em>next save</em> of that exact build ({@code saveThrough}'s 404), and a build nobody
 * is currently editing is never saved again.</p>
 *
 * <p>So: ask the relay which of this world's uploads it still has, and re-submit the ones it doesn't.
 * Not scoped to that incident — the same hole opens on any eviction, cap change, or loss, and a
 * reconcile that only knew about August would have to be written again next time.</p>
 *
 * <p><b>A restored build comes back private and unpublished.</b> It goes up the ordinary
 * {@code /carriages/submit} road, with the ordinary moderation, and the relay issues a new id and a
 * new secret — the row that held "published", "accepted" and its favourites is gone, and the client's
 * word for what a moderator once decided is not authority. The player re-submits from My Builds.</p>
 *
 * <p><b>Two tiers, and the second one is asked about.</b> A build whose file is still on disk is
 * unambiguous. A build whose file is gone too can be read out of a backup archive — but deleting a
 * build locally does not clear its relay record here, so a missing file may be a deliberate deletion,
 * and re-uploading it would undo the player's own housekeeping. That tier is listed separately and
 * off by default.</p>
 */
public final class BuilderRelayReconcile {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Gap between uploads. The relay's binding limit is its per-IP window (300 requests a minute), and
     * a reconcile is the one thing in the mod that would otherwise fire a few hundred requests back to
     * back. Four a second leaves the rest of that budget for the session doing the reconciling.
     */
    private static final long PACE_MS = 250L;

    /**
     * How many builds one run will restore.
     *
     * <p>Under the relay's per-author profile cap (200), because that cap evicts too: a run that
     * uploaded past it would silently delete this player's oldest profile builds to make room for the
     * ones it was restoring, which is the failure this whole class exists to answer. The remainder is
     * reported and picked up by the next run.</p>
     */
    private static final int MAX_PER_RUN = 150;

    /** One reconcile at a time, per game. Two would race on the world data and on the pacing budget. */
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static volatile ExecutorService worker;

    private BuilderRelayReconcile() {}

    /**
     * One build the relay no longer has.
     *
     * @param key      the {@link BuilderRelayBuilds#keyOf} key it is filed under in this world
     * @param entry    what this world still records about it — its now-dead relay id
     * @param kind     which store owns it
     * @param subKind  the part/track kind whose id-space it belongs to, or empty
     * @param id       the template name
     * @param onDisk   whether its file is still in the live store (tier one) or only in a backup
     */
    public record Missing(String key, BuilderRelayBuilds.Entry entry, BuilderPhotoPaths.Kind kind,
                          String subKind, String id, boolean onDisk) {}

    /**
     * What a scan found, split by tier.
     *
     * <p>{@code reachable} is false when the relay could not be asked at all. It is not the same as
     * finding nothing missing, and the difference is load-bearing: a relay that is down must never be
     * read as a relay that has lost everything.</p>
     */
    public record Scan(List<Missing> onDisk, List<Missing> backupOnly, boolean reachable) {
        public static Scan unreachable() {
            return new Scan(List.of(), List.of(), false);
        }

        public boolean isEmpty() {
            return onDisk.isEmpty() && backupOnly.isEmpty();
        }

        public int total() {
            return onDisk.size() + backupOnly.size();
        }
    }

    /** What a run did. {@code remaining} is what {@link #MAX_PER_RUN} left for the next one. */
    public record Outcome(int restored, int failed, int remaining) {}

    // ---- scan ----

    /**
     * Which of this world's uploaded builds the relay no longer has.
     *
     * <p>One request for the whole profile rather than one per build: {@code /carriages/mine} answers
     * with every row this player owns, and what is missing is what this world recorded and that
     * listing doesn't mention. Reading the world data happens on the calling (server) thread; the
     * request and the disk checks resolve later.</p>
     */
    public static CompletableFuture<Scan> scan(ServerPlayer player, ServerLevel level) {
        if (player == null || level == null || !BuilderRelayUpload.canUpload(player)) {
            return CompletableFuture.completedFuture(Scan.unreachable());
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        List<Map.Entry<String, BuilderRelayBuilds.Entry>> recorded =
                new ArrayList<>(data.builderRelayBuilds().all());
        if (recorded.isEmpty()) {
            return CompletableFuture.completedFuture(new Scan(List.of(), List.of(), true));
        }
        String uuid = player.getUUID().toString();
        return SharedCarriageClient.listMine(uuid, uuid, RelayTarget.dev()).thenApply(builds -> {
            // null means the call failed. Every id would then look missing, and a reconcile offered on
            // that basis would re-upload a player's whole profile because their wifi dropped.
            if (builds == null) return Scan.unreachable();
            Set<Integer> alive = new HashSet<>();
            for (SharedCarriageClient.ProfileBuild build : builds) alive.add(build.id());
            return classify(recorded, alive, Locator.STORES);
        });
    }

    /**
     * Where a build's copies are. A seam, so the sorting below can be tested without a game
     * directory: the real answer walks the template stores and the backup archives, and a test
     * answers from a map.
     */
    interface Locator {
        boolean onDisk(BuilderPhotoPaths.Kind kind, String subKind, String id);

        boolean inBackup(BuilderPhotoPaths.Kind kind, String subKind, String id);

        Locator STORES = new Locator() {
            @Override
            public boolean onDisk(BuilderPhotoPaths.Kind kind, String subKind, String id) {
                return BuilderTemplateSource.liveOnDisk(kind, subKind, id);
            }

            @Override
            public boolean inBackup(BuilderPhotoPaths.Kind kind, String subKind, String id) {
                return BuilderTemplateSource.fileFor(kind, subKind, id)
                        .flatMap(BuilderTemplateSource::fromBackups).isPresent();
            }
        };
    }

    /**
     * Sort what this world recorded against what the relay still has.
     *
     * <p>Pure, given a {@link Locator}. A build the relay still lists is fine; one it doesn't is
     * offered from disk if the file is there, from a backup if it isn't, and not at all if there is no
     * copy anywhere — there is nothing to offer a player about a build that exists nowhere.</p>
     */
    static Scan classify(Collection<Map.Entry<String, BuilderRelayBuilds.Entry>> recorded,
                         Set<Integer> alive, Locator locator) {
        List<Missing> onDisk = new ArrayList<>();
        List<Missing> backupOnly = new ArrayList<>();
        for (Map.Entry<String, BuilderRelayBuilds.Entry> e : recorded) {
            if (alive.contains(e.getValue().relayId())) continue;
            Missing missing = missingOf(e.getKey(), e.getValue(), locator);
            if (missing == null) continue;
            if (missing.onDisk()) {
                onDisk.add(missing);
            } else if (locator.inBackup(missing.kind(), missing.subKind(), missing.id())) {
                backupOnly.add(missing);
            }
        }
        return new Scan(List.copyOf(onDisk), List.copyOf(backupOnly), true);
    }

    /**
     * Take one recorded key apart into the build it names, or null when this build of the mod cannot
     * place it — an unknown kind is one whose store this version doesn't have, and guessing would read
     * the wrong directory.
     */
    private static Missing missingOf(String key, BuilderRelayBuilds.Entry entry, Locator locator) {
        // keyOf joins the triple with NUL, which is the one character a template name cannot contain
        // — so the split is exact, and an id with spaces in it survives whole.
        String[] parts = key.split("\0", 3);
        if (parts.length != 3) return null;
        BuilderPhotoPaths.Kind kind = BuilderRelayKinds.kindOf(parts[0]);
        if (kind == null || parts[2].isEmpty()) return null;
        return new Missing(key, entry, kind, parts[1], parts[2],
                locator.onDisk(kind, parts[1], parts[2]));
    }

    // ---- restore ----

    /**
     * Re-upload what a scan found.
     *
     * <p>Sequential and paced on a worker thread, not fanned out: see {@link #PACE_MS}. Each build is
     * read, reshaped and encoded off the server thread; only the world-data write that records the new
     * relay id goes back onto it, exactly as {@link BuilderRelayUpload} does it.</p>
     *
     * <p>Stops early on a run of failures rather than working through a few hundred builds against a
     * relay that is plainly not accepting them. Whatever it did is kept — a partial reconcile leaves
     * the rest for the next run, because each build is recorded as it lands.</p>
     *
     * @param includeBackups whether to also restore builds whose only copy is in a backup archive —
     *                       the player's answer to the second tier, never assumed
     */
    public static CompletableFuture<Outcome> restore(ServerPlayer player, ServerLevel level, Scan scan,
                                                     boolean includeBackups) {
        if (player == null || level == null || scan == null || !BuilderRelayUpload.canUpload(player)) {
            return CompletableFuture.completedFuture(new Outcome(0, 0, 0));
        }
        List<Missing> queue = new ArrayList<>(scan.onDisk());
        if (includeBackups) queue.addAll(scan.backupOnly());
        if (queue.isEmpty()) return CompletableFuture.completedFuture(new Outcome(0, 0, 0));

        if (!RUNNING.compareAndSet(false, true)) {
            // Already reconciling — say nothing new rather than starting a second pass over the same
            // builds, which would upload each one twice.
            return CompletableFuture.completedFuture(new Outcome(0, 0, queue.size()));
        }
        int remaining = Math.max(0, queue.size() - MAX_PER_RUN);
        List<Missing> run = queue.size() > MAX_PER_RUN ? queue.subList(0, MAX_PER_RUN) : queue;
        HolderLookup.Provider registries = level.registryAccess();
        String uuid = player.getUUID().toString();
        String name = player.getGameProfile().getName();

        return CompletableFuture.supplyAsync(
                () -> runQueue(level, uuid, name, List.copyOf(run), registries, remaining), worker())
                .whenComplete((outcome, error) -> RUNNING.set(false));
    }

    /** The paced loop itself, on the worker thread. */
    private static Outcome runQueue(ServerLevel level, String uuid, String name, List<Missing> queue,
                                    HolderLookup.Provider registries, int remaining) {
        int restored = 0;
        int failed = 0;
        int consecutiveFailures = 0;
        for (Missing missing : queue) {
            Optional<Upload> prepared = prepare(missing, registries);
            if (prepared.isEmpty()) {
                failed++;
                continue;
            }
            if (upload(level, uuid, name, missing, prepared.get())) {
                restored++;
                consecutiveFailures = 0;
            } else {
                failed++;
                if (++consecutiveFailures >= 3) {
                    LOGGER.warn("[DungeonTrain] Build reconcile: stopping after {} failures in a row; "
                            + "{} build(s) left for the next run.", consecutiveFailures,
                            queue.size() - restored - failed);
                    remaining += queue.size() - restored - failed;
                    break;
                }
            }
            pace();
        }
        LOGGER.info("[DungeonTrain] Build reconcile: restored {}, failed {}, {} left.",
                restored, failed, remaining);
        return new Outcome(restored, failed, remaining);
    }

    /** A build turned into the two things a submit carries: its blocks blob and its authored text. */
    private record Upload(String blocks, String text, int l, int h, int w) {}

    /**
     * Read a build off disk (or out of a backup) and reshape it into what a submit sends.
     *
     * <p>The blob is built from the stored template rather than from the world, because the build is
     * not standing anywhere — {@link CarriageSnapshotTemplate#fromTemplateTag} is the inverse of the
     * reshaping a download already does, and produces the same bytes
     * {@link CarriageBlockSnapshot#captureLevel} would have. The text is scraped the same way too, so
     * a restored build's signs and books reach moderation exactly as they did the first time.</p>
     */
    private static Optional<Upload> prepare(Missing missing, HolderLookup.Provider registries) {
        try {
            Optional<BuilderTemplateSource.Found> found = BuilderTemplateSource.read(
                    missing.kind(), missing.subKind(), missing.id(), !missing.onDisk());
            if (found.isEmpty()) return Optional.empty();

            CompoundTag snapshot = CarriageSnapshotTemplate.fromTemplateTag(found.get().tag());
            int l = snapshot.getInt("l");
            int h = snapshot.getInt("h");
            int w = snapshot.getInt("w");
            if (l <= 0 || h <= 0 || w <= 0) {
                LOGGER.warn("[DungeonTrain] Build reconcile: '{}' has no readable volume — skipped.", missing.id());
                return Optional.empty();
            }
            String blocks = CarriageBlockSnapshot.encode(snapshot);
            if (blocks.length() > BuilderRelayUpload.MAX_BLOCKS_CHARS) {
                LOGGER.info("[DungeonTrain] Build reconcile: '{}' is {} chars, over the {} limit — skipped.",
                        missing.id(), blocks.length(), BuilderRelayUpload.MAX_BLOCKS_CHARS);
                return Optional.empty();
            }
            return Optional.of(new Upload(blocks, CarriageSnapshotTemplate.textOf(snapshot, registries), l, h, w));
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Build reconcile: couldn't prepare '{}': {}", missing.id(), t.toString());
            return Optional.empty();
        }
    }

    /**
     * Submit one build and record what came back.
     *
     * <p>Blocks on the worker thread until the relay answers, which is what makes the pacing above
     * mean anything — a fire-and-forget loop would put the whole queue on the wire at once.</p>
     */
    private static boolean upload(ServerLevel level, String uuid, String name, Missing missing, Upload upload) {
        try {
            Optional<SharedCarriageClient.BuildUpload> result = SharedCarriageClient.submitBuild(
                    uuid, name, upload.blocks(), upload.l(), upload.h(), upload.w(), upload.text(),
                    stageFor(missing), BuilderRelayUpload.poolFor(),
                    BuilderRelayKinds.idOf(missing.kind()), missing.subKind(), missing.id(), "profile")
                    .join();
            if (result.isEmpty()) return false;

            SharedCarriageClient.BuildUpload up = result.get();
            MinecraftServer server = level.getServer();
            if (server != null) {
                server.execute(() -> {
                    DungeonTrainWorldData live = DungeonTrainWorldData.get(level);
                    // Replaces the dead record: a restored build has a new id and a new secret, and the
                    // old ones authorise nothing.
                    live.builderRelayBuilds().put(missing.key(),
                            new BuilderRelayBuilds.Entry(up.id(), up.secret(), up.token(), false));
                    live.markBuilderRelayBuildsDirty();
                });
            }
            return true;
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Build reconcile: uploading '{}' failed: {}", missing.id(), t.toString());
            return false;
        }
    }

    /**
     * The stage a restored build claims.
     *
     * <p>A carriage carries its Stage link in the weights file, which survived whatever the relay did,
     * so the link is read back rather than lost. Nothing else the builder authors is Stage-linked —
     * an upload of one carries the builder world's current stage, which a reconcile has no business
     * inventing.</p>
     */
    private static String stageFor(Missing missing) {
        if (missing.kind() != BuilderPhotoPaths.Kind.CARRIAGE) return "";
        String stage = CarriageWeights.current().stageIdFor(missing.id());
        return stage == null ? "" : stage;
    }

    private static void pace() {
        try {
            Thread.sleep(PACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One daemon thread, made on first use — a reconcile is rare and must not hold the game open. */
    private static ExecutorService worker() {
        ExecutorService existing = worker;
        if (existing != null) return existing;
        synchronized (BuilderRelayReconcile.class) {
            if (worker == null) {
                worker = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "DungeonTrain-BuildReconcile");
                    t.setDaemon(true);
                    return t;
                });
            }
            return worker;
        }
    }
}
