package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.building.Buildings;
import games.brennan.dungeontrain.config.DungeonTrainConfig;
import games.brennan.dungeontrain.net.relay.SharedCarriageClient;
import games.brennan.dungeontrain.tools.BundledFingerprints;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Which building files on this install are ones the game has taken on.
 *
 * <p>A building somebody made is custom content, and custom content puts a world in Free Play
 * ({@link EditorContentIntegrity}). A building an operator <b>accepted</b> is not that: it is content
 * the game has said yes to, the same as what ships in the jar, and a player who downloads one should
 * not be playing a lesser game for it. The relay publishes the block hashes of the accepted buildings;
 * a file whose hash is one of them is exempt from the scan, and so is the weight sidecar and picture
 * beside it.</p>
 *
 * <p><b>The hash is the relay's.</b> {@link BundledFingerprints#hashOf} gives a stored template the
 * hash its upload had, and a building uploads as its file — so the file a save wrote, the file a
 * download wrote and the row the operator accepted all agree ({@code BuildingRelayTest}). Change one
 * block and the hash is a different one, the acceptance covers nothing, and the world is Free Play
 * again until the new version is accepted.</p>
 *
 * <p><b>Held in memory only, and asked for only when there is something to check.</b> An install with
 * no building files never calls the relay. One that has some asks once, then again no more often than
 * {@link #REFRESH_MS}; until an answer arrives nothing is exempt, which is the conservative reading —
 * as it is offline, and for a player who has not allowed network access. Nothing is cached to disk:
 * the list is what makes content count as the game's, and a file a player can edit is not a place to
 * keep that.</p>
 */
public final class ApprovedBuildings {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How long an answer is trusted before the relay is asked again — acceptances arrive over days. */
    static final long REFRESH_MS = 10 * 60_000L;

    /** How long to wait before asking again after no answer, growing with each attempt. */
    private static final long RETRY_MS = 20_000L;
    private static final int MAX_RETRIES = 3;

    /** The extension a building's template carries. */
    private static final String NBT_EXT = ".nbt";

    /** The accepted hashes, or null while the relay has never answered. */
    private static volatile Set<String> accepted = null;
    private static volatile long lastAskedMs = 0L;
    private static final AtomicBoolean ASKING = new AtomicBoolean();

    /**
     * Whether this client's player has allowed network access — set by the client at startup. Null on
     * a dedicated server, which has no player to ask and goes by its own config instead.
     */
    private static volatile BooleanSupplier clientConsent = null;

    /** One file's hash, remembered against its size and timestamp so a scan re-hashes only what changed. */
    private record Stamp(long size, long modified, String hash) {}

    private static final Map<Path, Stamp> HASHES = new ConcurrentHashMap<>();

    private ApprovedBuildings() {}

    /** Hand over the client's consent check — see {@link #clientConsent}. */
    public static void setClientConsent(BooleanSupplier consent) {
        clientConsent = consent;
    }

    /**
     * Whether {@code file}, somewhere under the package folder {@code packageDir}, belongs to an
     * accepted building: the template itself, or anything else filed under the same name beside it.
     */
    public static boolean exempts(Path packageDir, Path file) {
        String name = buildingNameOf(packageDir, file);
        if (name == null) return false;
        return isAccepted(packageDir.resolve(Buildings.SUBDIR).resolve(name + NBT_EXT));
    }

    /**
     * The building {@code file} is filed under — {@code <package>/buildings/<name>.<anything>} — or
     * null when it is not a building's file at all. Pure.
     */
    static String buildingNameOf(Path packageDir, Path file) {
        if (packageDir == null || file == null || !file.startsWith(packageDir)) return null;
        Path relative = packageDir.relativize(file);
        if (relative.getNameCount() != 2 || !Buildings.SUBDIR.equals(relative.getName(0).toString())) return null;
        String filename = relative.getName(1).toString();
        int dot = filename.indexOf('.');
        if (dot <= 0) return null;
        String name = filename.substring(0, dot);
        return Buildings.NAME.matcher(name).matches() ? name : null;
    }

    /** Whether the building template at {@code nbt} is one the relay lists as accepted. */
    static boolean isAccepted(Path nbt) {
        if (nbt == null || !Files.isRegularFile(nbt)) return false;
        Set<String> known = accepted;
        refreshIfDue();
        if (known == null || known.isEmpty()) return false;
        String hash = hashOf(nbt);
        return hash != null && known.contains(hash);
    }

    /** {@code nbt}'s relay hash, or null when it will not read — an unreadable file is not an accepted one. */
    private static String hashOf(Path nbt) {
        try {
            long size = Files.size(nbt);
            long modified = Files.getLastModifiedTime(nbt).toMillis();
            Stamp stamp = HASHES.get(nbt);
            if (stamp != null && stamp.size() == size && stamp.modified() == modified) return stamp.hash();
            String hash = BundledFingerprints.hashOf(nbt);
            HASHES.put(nbt, new Stamp(size, modified, hash));
            return hash;
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Couldn't fingerprint building {}: {}", nbt, e.toString());
            return null;
        }
    }

    /**
     * Ask the relay for the accepted list when it has never been asked, or not for a while.
     *
     * <p>Asynchronous, and it never blocks the scan that triggered it: this scan reads what is known
     * now, and an answer that changes the list drops {@link EditorContentIntegrity}'s cached scan so
     * the next read sees it.</p>
     */
    static void refreshIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastAskedMs < REFRESH_MS && lastAskedMs != 0L) return;
        if (!mayAsk() || !ASKING.compareAndSet(false, true)) return;
        lastAskedMs = now;
        ask(0);
    }

    /**
     * One request, and a few spaced retries behind it when the relay does not answer.
     *
     * <p>The retries matter because nothing else would ask again: a scan is what triggers a request,
     * and a scan that found custom content is cached until something invalidates it. Without them one
     * dropped request at startup would leave an accepted building counting as custom for the whole
     * session.</p>
     */
    private static void ask(int attempt) {
        SharedCarriageClient.acceptedHashes(BuilderRelayKinds.BUILDING).handle((answer, failure) -> {
            if (answer != null) {
                ASKING.set(false);
                apply(answer);
            } else if (attempt < MAX_RETRIES) {
                LOGGER.debug("[DungeonTrain] Accepted buildings: no answer from the relay, retrying ({})", attempt + 1);
                CompletableFuture.delayedExecutor(RETRY_MS * (attempt + 1), TimeUnit.MILLISECONDS)
                    .execute(() -> ask(attempt + 1));
            } else {
                ASKING.set(false);
            }
            return null;
        });
    }

    /** Take the relay's answer; null is "no answer", which leaves what is known alone. */
    static void apply(Set<String> answer) {
        if (answer == null) return;
        Set<String> next = Set.copyOf(answer);
        Set<String> previous = accepted;
        accepted = next;
        if (!next.equals(previous)) {
            LOGGER.info("[DungeonTrain] Accepted buildings: {} on the relay's list", next.size());
            EditorContentIntegrity.invalidate();
        }
    }

    /**
     * Whether the relay may be asked at all: on a client, the player's own answer to "use the
     * internet?"; on a dedicated server, the switch that opts the server into shared builds.
     */
    private static boolean mayAsk() {
        try {
            BooleanSupplier consent = clientConsent;
            if (consent != null) return consent.getAsBoolean();
            return FMLEnvironment.dist.isDedicatedServer() && DungeonTrainConfig.isBuilderProfileEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Test seam: set the accepted list directly, as an answer from the relay would. */
    static void setAcceptedForTest(Set<String> hashes) {
        accepted = hashes == null ? null : Set.copyOf(hashes);
        lastAskedMs = hashes == null ? 0L : System.currentTimeMillis();
        HASHES.clear();
    }
}
