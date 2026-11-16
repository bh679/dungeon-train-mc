package games.brennan.dungeontrain.compat;

import com.mojang.logging.LogUtils;
import games.brennan.dtmodverify.api.FileFingerprint;
import games.brennan.dtmodverify.api.ModVerify;
import games.brennan.dtmodverify.api.Verdict;
import games.brennan.dungeontrain.cheat.ApprovedModList;
import games.brennan.dungeontrain.cheat.EngineIntegrity;
import games.brennan.dungeontrain.cheat.ModFileFingerprint;
import games.brennan.dungeontrain.cheat.UnapprovedModIntegrity;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Bridge into the optional <b>DT Mod Verify</b> sibling ({@code dtmodverify}) — the Free Play
 * file-verification ENGINE: it hashes every installed jar (SHA-1, CurseForge fingerprint, package
 * fingerprint, declared mod ids), holds the pure tier rule, and POSTs the fingerprints to the relay's
 * {@code /approved-mods/verify}. DT keeps the judgement, the Unsupported Mods popup and the telemetry.
 *
 * <p><b>Why the engine is not in DT.</b> Reading every jar on disk is the kind of thing that sends a
 * mod into CurseForge's manual review, and DT releases many times a week — so the scan lives in its
 * own small mod, reviewed once on its own listing, jarJar'd inside DT like {@link StreamDetectBridge}'s.</p>
 *
 * <p>Without the engine (or with one whose API no longer links) DT behaves exactly as before the hash
 * check existed: no scan, no POST, no file rows, no file facts — every answer here is the empty one
 * ({@link #isDone()} true, {@link #current} empty, {@link #fileOf} null, {@link #tierOf}
 * {@link UnapprovedModIntegrity.Tier#UNKNOWN}). Hard imports are confined to the nested {@link Api}
 * holder, which is only loaded once the mod is known to be present.</p>
 */
public final class DtModVerifyBridge {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String MOD_ID = "dtmodverify";

    /** DT's own view of the relay's answer: HTTP status (0 = no answer) and body ("" when none). */
    public record VerifyAnswer(int status, String body) {
        public VerifyAnswer {
            body = body == null ? "" : body;
        }

        /** 2xx. */
        public boolean ok() {
            return status / 100 == 2;
        }
    }

    private static final VerifyAnswer NO_ANSWER = new VerifyAnswer(0, "");

    /** Read once; the mod list does not change after loading. Cleared if the API fails to link. */
    private static volatile boolean present = detectPresent();

    private DtModVerifyBridge() {}

    /**
     * Present when the engine's classes load AND the mod list says it is loaded. Outside a mod
     * environment (unit tests) there is no mod list; then the classpath alone decides, so tests
     * exercise the real engine.
     */
    private static boolean detectPresent() {
        try {
            Class.forName("games.brennan.dtmodverify.api.ModVerify", false, DtModVerifyBridge.class.getClassLoader());
        } catch (Throwable t) {
            return false;
        }
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (Throwable t) {
            return true;
        }
    }

    /** Is the engine installed and linking? */
    public static boolean isPresent() {
        return present;
    }

    /**
     * The file on disk a loaded mod file came from, as the engine maps it (0.2.1: a custom-locator
     * nested file resolves to the shell that bundles it). Without the engine, or with one too old
     * for the seam, the jar-in-jar parent chain alone ({@link EngineIntegrity#outermost}).
     */
    public static java.nio.file.Path outermostPath(net.neoforged.neoforgespi.locating.IModFile file) {
        if (present) {
            try {
                return Api.outermostPath(file);
            } catch (LinkageError e) {
                LOGGER.debug("[DungeonTrain] dtmodverify has no outermostPath seam ({}); using the parent chain", e.toString());
            }
        }
        return EngineIntegrity.outermost(file).getFilePath();
    }

    /** The engine's scan future, once started; completes when every jar has been fingerprinted. */
    private static volatile CompletableFuture<?> scanFuture;

    /** Start the once-per-JVM scan of the installed jars (no-op without the engine). */
    public static void scan(Set<String> exemptIds) {
        if (!present) return;
        try {
            scanFuture = Api.scan(exemptIds);
        } catch (LinkageError e) {
            disable(e);
        }
    }

    /**
     * Run {@code then} once the scan has finished (successfully or not), on the engine's thread — the
     * caller hops to its own. Runs at once without the engine or when no scan was started.
     */
    public static void onScanDone(Runnable then) {
        CompletableFuture<?> f = scanFuture;
        if (!present || f == null) {
            then.run();
            return;
        }
        f.whenComplete((r, err) -> then.run());
    }

    /** The fingerprints once the scan is done within {@code timeoutMs}; empty when not yet, or absent. */
    public static List<ModFileFingerprint> current(long timeoutMs) {
        if (!present) return List.of();
        try {
            return Api.current(timeoutMs);
        } catch (LinkageError e) {
            disable(e);
            return List.of();
        }
    }

    /** Has the scan finished? Always true without the engine, so nobody waits on it. */
    public static boolean isDone() {
        if (!present) return true;
        try {
            return Api.isDone();
        } catch (LinkageError e) {
            disable(e);
            return true;
        }
    }

    /** The scanned file declaring {@code modId}, or null (unknown, not yet, or absent). Non-blocking. */
    public static ModFileFingerprint fileOf(String modId) {
        if (!present) return null;
        try {
            return Api.fileOf(modId);
        } catch (LinkageError e) {
            disable(e);
            return null;
        }
    }

    /** The engine's pure tier rule, in DT's own tiers. {@code UNKNOWN} without the engine. */
    public static UnapprovedModIntegrity.Tier tierOf(ModFileFingerprint f, Set<String> approvedSha1s,
                                                     Map<String, Set<String>> knownPackages, Set<String> exemptIds) {
        if (!present) return UnapprovedModIntegrity.Tier.UNKNOWN;
        try {
            return Api.tierOf(f, approvedSha1s, knownPackages, exemptIds);
        } catch (LinkageError e) {
            disable(e);
            return UnapprovedModIntegrity.Tier.UNKNOWN;
        }
    }

    /** POST the files to the relay. Never exceptional; (0, "") without the engine or without an answer. */
    public static CompletableFuture<VerifyAnswer> verify(String base, String dtVersion, List<ModFileFingerprint> files) {
        if (!present) return CompletableFuture.completedFuture(NO_ANSWER);
        try {
            return Api.verify(base, dtVersion, files);
        } catch (LinkageError e) {
            disable(e);
            return CompletableFuture.completedFuture(NO_ANSWER);
        }
    }

    /** One INFO line naming the files the relay judged suspect/revoked. */
    public static void logVerdicts(String body, int sent) {
        if (!present) return;
        try {
            Api.logVerdicts(body, sent);
        } catch (LinkageError e) {
            disable(e);
        }
    }

    /** The engine's version, or {@code "?"}. */
    public static String version() {
        if (!present) return "?";
        try {
            return Api.version();
        } catch (LinkageError e) {
            disable(e);
            return "?";
        }
    }

    private static void disable(LinkageError e) {
        present = false;
        LOGGER.warn("[DungeonTrain] DT Mod Verify is installed but its API did not link — "
            + "falling back to the id-only mod check", e);
    }

    /** The only place the engine's classes are named; converts between its records and DT's. */
    private static final class Api {
        static CompletableFuture<?> scan(Set<String> exemptIds) {
            return ModVerify.scan(exemptIds);
        }

        static List<ModFileFingerprint> current(long timeoutMs) {
            List<FileFingerprint> in = ModVerify.current(timeoutMs);
            List<ModFileFingerprint> out = new ArrayList<>(in.size());
            for (FileFingerprint f : in) out.add(from(f));
            return List.copyOf(out);
        }

        static boolean isDone() {
            return ModVerify.isDone();
        }

        static ModFileFingerprint fileOf(String modId) {
            return from(ModVerify.fileOf(modId));
        }

        static UnapprovedModIntegrity.Tier tierOf(ModFileFingerprint f, Set<String> approvedSha1s,
                                                  Map<String, Set<String>> knownPackages, Set<String> exemptIds) {
            // The 5-arg overload (engine 0.2.0): a file generated by an approved carrier jar is
            // verified when every id it declares is on the curated carrier allowlist.
            Verdict v = ModVerify.tierOf(to(f), approvedSha1s, knownPackages, exemptIds,
                ApprovedModList.carrierApproved());
            return switch (v) {
                case VERIFIED -> UnapprovedModIntegrity.Tier.VERIFIED;
                case PLAUSIBLE -> UnapprovedModIntegrity.Tier.PLAUSIBLE;
                case UNKNOWN -> UnapprovedModIntegrity.Tier.UNKNOWN;
                case SUSPECT -> UnapprovedModIntegrity.Tier.SUSPECT;
            };
        }

        static CompletableFuture<VerifyAnswer> verify(String base, String dtVersion, List<ModFileFingerprint> files) {
            List<FileFingerprint> out = new ArrayList<>(files.size());
            for (ModFileFingerprint f : files) out.add(to(f));
            return ModVerify.verify(base, dtVersion, out)
                .handle((a, err) -> a == null ? NO_ANSWER : new VerifyAnswer(a.status(), a.body()));
        }

        static void logVerdicts(String body, int sent) {
            games.brennan.dtmodverify.relay.VerifyClient.logVerdicts(body, sent);
        }

        static String version() {
            return ModVerify.version();
        }

        static java.nio.file.Path outermostPath(net.neoforged.neoforgespi.locating.IModFile file) {
            return ModVerify.outermostPath(file);
        }

        static ModFileFingerprint from(FileFingerprint f) {
            if (f == null) return null;
            FileFingerprint.Carrier c = f.carrier();
            return new ModFileFingerprint(f.fileName(), f.size(), f.sha1(), f.murmur2(), f.modIds(), f.packages(),
                c == null ? null : c.sha1(), c == null ? null : c.fileName());
        }

        static FileFingerprint to(ModFileFingerprint f) {
            if (f == null) return null;
            FileFingerprint out = new FileFingerprint(f.fileName(), f.size(), f.sha1(), f.murmur2(), f.modIds(), f.packages());
            // Only the sha1 and name travel through DT; size/murmur2 of the carrier are the engine's
            // and the relay's concern (the engine's own verify call sends its full record).
            return f.carrierSha1() == null ? out
                : out.withCarrier(new FileFingerprint.Carrier(f.carrierName(), 0L, f.carrierSha1(), 0L));
        }
    }
}
