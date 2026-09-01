package games.brennan.dungeontrain.client.builder;

import com.mojang.logging.LogUtils;
import games.brennan.discordpresence.config.DiscordPresenceClientConfig;
import games.brennan.dungeontrain.builder.relay.BuilderProfileCap;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.builder.relay.BuilderTemplateSource;
import games.brennan.dungeontrain.net.relay.RelayTarget;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.slf4j.Logger;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the build reconcile from the menu: ask the relay what it still has, and send back what it
 * has lost.
 *
 * <p>Client-side on purpose. The relay is reachable from the title screen — the translation tools
 * already read it there — and a build is a file on this install, so neither half of this needs a
 * world to be loaded. That is what lets the offer be made at startup, before the player picks a
 * world, rather than only once they are standing in one.</p>
 *
 * <p>The upload is the same {@code /carriages/submit} call a builder save makes, with the same
 * fields, so a restored build is indistinguishable from the one that was lost — bar its new id. The
 * relay dedupes a builder submit per author, kind and build name, so running this twice, or running
 * it and then saving the build in-world, converges on one row rather than piling up copies.</p>
 */
public final class BuilderReconcileRunner {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Gap between uploads — the relay's per-IP window is 300 a minute, and this is one client. */
    private static final long PACE_MS = 250L;

    /**
     * A ceiling on one run, on top of the profile-cap allowance below.
     *
     * <p>Not about the cap: it bounds how long a single title-screen restore can run and how much of
     * the relay's rate budget it takes. Whatever is left is picked up next launch.</p>
     */
    private static final int MAX_PER_RUN = 150;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private static volatile ExecutorService worker;

    private BuilderReconcileRunner() {}

    /** What a run did. {@code remaining} is what {@link #MAX_PER_RUN} left for the next one. */
    public record Outcome(int restored, int failed, int remaining) {}

    /** Whether this install may talk to the relay about builds at all. */
    public static boolean canRun() {
        try {
            return DiscordPresenceClientConfig.isGranted() && uuid() != null;
        } catch (Throwable t) {
            return false;   // consent config unreadable — fail closed, exactly as the server gate does
        }
    }

    /**
     * Which of this install's builds the relay no longer has.
     *
     * <p>Resolves to an empty result when the relay cannot be reached. That is deliberately the same
     * answer as "nothing is missing": the two differ only in what could be done about them, and
     * neither is worth a card at the title screen.</p>
     */
    public static CompletableFuture<BuilderReconcileScan.Result> scan() {
        if (!canRun()) return CompletableFuture.completedFuture(empty());
        UUID uuid = uuid();
        return SharedCarriageClient.listMine(uuid.toString(), uuid.toString(), RelayTarget.dev())
                .thenApplyAsync(builds -> {
                    // null is a failed call. Treating it as an empty profile would report every build
                    // on the install as lost, and offer to re-upload the lot.
                    if (builds == null) return empty();
                    Set<String> relayKeys = new LinkedHashSet<>();
                    for (SharedCarriageClient.ProfileBuild build : builds) {
                        relayKeys.add(keyOf(build));
                    }
                    List<BuilderReconcileScan.Build> onDisk = BuilderReconcileScan.localBuilds();
                    List<BuilderReconcileScan.Build> inBackups =
                            BuilderReconcileScan.backupBuilds(BuilderReconcileScan.keysOf(onDisk));
                    return BuilderReconcileScan.compare(onDisk, inBackups, relayKeys,
                            BuilderProfileCap.used(builds));
                }, worker())
                .exceptionally(error -> {
                    LOGGER.warn("[DungeonTrain] Build reconcile: scan failed: {}", error.toString());
                    return empty();
                });
    }

    /**
     * Send back what a scan found, paced.
     *
     * <p>Sequential on a worker thread: a few hundred builds fired at once is the one thing in the
     * mod that would trip the relay's rate limiter, and being throttled mid-restore is worse than
     * taking a minute. Stops after three failures in a row rather than working through the whole
     * queue against a relay that is plainly not accepting anything.</p>
     */
    public static CompletableFuture<Outcome> restore(BuilderReconcileScan.Result scan,
                                                     boolean includeBackups) {
        if (!canRun() || scan == null) return CompletableFuture.completedFuture(new Outcome(0, 0, 0));
        List<BuilderReconcileScan.Build> queue = new java.util.ArrayList<>(scan.onDisk());
        if (includeBackups) queue.addAll(scan.inBackups());
        if (queue.isEmpty() || !RUNNING.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(new Outcome(0, 0, queue.size()));
        }
        // Never fill the profile past its cap. Going over does not fail — the relay accepts the
        // upload and deletes this player's OLDEST build to make room, which would mean a restore
        // quietly costing them work in the act of returning it.
        int allowance = Math.min(MAX_PER_RUN, BuilderProfileCap.remaining(scan.profileUsed()));
        if (allowance <= 0) {
            LOGGER.warn("[DungeonTrain] Build reconcile: profile is full ({}/{}); {} build(s) left "
                    + "un-restored. Remove some from My Builds to make room.", scan.profileUsed(),
                    BuilderProfileCap.MAX_PROFILE_BUILDS, queue.size());
            RUNNING.set(false);
            return CompletableFuture.completedFuture(new Outcome(0, 0, queue.size()));
        }
        int remaining = Math.max(0, queue.size() - allowance);
        List<BuilderReconcileScan.Build> run =
                List.copyOf(queue.size() > allowance ? queue.subList(0, allowance) : queue);
        UUID uuid = uuid();
        String name = Minecraft.getInstance().getUser().getName();
        HolderLookup.Provider registries = registries();

        return CompletableFuture
                .supplyAsync(() -> runQueue(run, uuid.toString(), name, registries, remaining), worker())
                .whenComplete((outcome, error) -> RUNNING.set(false));
    }

    private static Outcome runQueue(List<BuilderReconcileScan.Build> queue, String uuid, String name,
                                    HolderLookup.Provider registries, int remaining) {
        int restored = 0;
        int failed = 0;
        int inARow = 0;
        for (BuilderReconcileScan.Build build : queue) {
            if (upload(build, uuid, name, registries)) {
                restored++;
                inARow = 0;
            } else {
                failed++;
                if (++inARow >= 3) {
                    remaining += queue.size() - restored - failed;
                    LOGGER.warn("[DungeonTrain] Build reconcile: stopping after 3 failures in a row; "
                            + "{} build(s) left.", remaining);
                    break;
                }
            }
            pace();
        }
        LOGGER.info("[DungeonTrain] Build reconcile: restored {}, failed {}, {} left.",
                restored, failed, remaining);
        return new Outcome(restored, failed, remaining);
    }

    /** Read one build, reshape it into the wire blob, and submit it. Blocking, on the worker thread. */
    private static boolean upload(BuilderReconcileScan.Build build, String uuid, String name,
                                  HolderLookup.Provider registries) {
        try {
            // searchBackups=true: a build in the second tier has no file on disk by definition, and
            // one in the first tier never reaches the archive branch because the live read succeeds.
            Optional<BuilderTemplateSource.Found> found = BuilderTemplateSource.read(
                    build.kind(), build.subKind(), build.id(), true);
            if (found.isEmpty()) return false;

            CompoundTag snapshot = CarriageSnapshotTemplate.fromTemplateTag(found.get().tag());
            int l = snapshot.getInt("l");
            int h = snapshot.getInt("h");
            int w = snapshot.getInt("w");
            if (l <= 0 || h <= 0 || w <= 0) {
                LOGGER.warn("[DungeonTrain] Build reconcile: '{}' has no readable volume — skipped.",
                        build.id());
                return false;
            }
            String blocks = CarriageBlockSnapshot.encode(snapshot);
            if (blocks.length() > MAX_BLOCKS_CHARS) {
                LOGGER.info("[DungeonTrain] Build reconcile: '{}' is {} chars, over the {} limit — skipped.",
                        build.id(), blocks.length(), MAX_BLOCKS_CHARS);
                return false;
            }
            String text = registries == null ? "" : CarriageSnapshotTemplate.textOf(snapshot, registries);
            return SharedCarriageClient.submitBuild(uuid, name, blocks, l, h, w, text, "", "normal",
                            BuilderRelayKinds.idOf(build.kind()), build.subKind(), build.id(), "profile")
                    .join().isPresent();
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Build reconcile: uploading '{}' failed: {}", build.id(), t.toString());
            return false;
        }
    }

    /** The relay's own ceiling on a blocks blob, minus a margin — as {@code BuilderRelayUpload} has it. */
    private static final int MAX_BLOCKS_CHARS = 690_000;

    /**
     * The registry lookup the text scrape needs.
     *
     * <p>Present at the title screen only once a resource reload has run, and absent while connecting.
     * Null is handled rather than waited for: a build with no readable registries goes up with no
     * scraped text, which the relay treats as a build that carries none — it is auto-approved, exactly
     * as an empty-text build from a builder save is.</p>
     */
    private static HolderLookup.Provider registries() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc.level != null ? mc.level.registryAccess() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String keyOf(SharedCarriageClient.ProfileBuild build) {
        return (build.kind() == null ? "" : build.kind()) + '\0'
                + (build.subKind() == null ? "" : build.subKind()) + '\0'
                + (build.buildName() == null ? "" : build.buildName());
    }

    private static UUID uuid() {
        Minecraft mc = Minecraft.getInstance();
        return mc.getUser() == null ? null : mc.getUser().getProfileId();
    }

    private static BuilderReconcileScan.Result empty() {
        return new BuilderReconcileScan.Result(List.of(), List.of(), 0);
    }

    private static void pace() {
        try {
            Thread.sleep(PACE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One daemon thread, made on first use — this runs rarely and must not hold the game open. */
    private static ExecutorService worker() {
        ExecutorService existing = worker;
        if (existing != null) return existing;
        synchronized (BuilderReconcileRunner.class) {
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
