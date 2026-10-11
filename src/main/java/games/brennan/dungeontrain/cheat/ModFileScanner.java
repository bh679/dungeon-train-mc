package games.brennan.dungeontrain.cheat;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.locating.IModFile;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fingerprints every installed mod <b>file</b> once per launch, off-thread, for the hash-verified
 * whitelist. {@link ModList} lists mods; this maps them back to the jar the player actually put on
 * disk and hashes that — the thing a mod id (a line in {@code mods.toml}) cannot vouch for.
 *
 * <p><b>Grouping.</b> A jar embedding other jars (NeoForge's jar-in-jar) shows up as several mods,
 * each with its own {@link IModFile} whose path points <em>inside</em> the outer jar. Each is
 * walked up through {@code getDiscoveryAttributes().parent()} to the file that was installed, and
 * all their ids are attributed to that one file — the player can only add or remove the outer
 * jar, so that is the only thing worth a verdict.</p>
 *
 * <p><b>Exemptions.</b> A file that is not a plain {@code .jar} on disk (a dev classpath directory)
 * is skipped — nothing to hash and no player has it. So is any file carrying one of
 * {@link #EXEMPT_IDS}: the game and loader themselves, and Dungeon Train's own jar (with the siblings
 * it embeds). Those are judged by id, as before; a cheat jar cannot borrow the exemption because a
 * duplicate mod id stops the game from loading at all.</p>
 *
 * <p><b>Threading.</b> {@link #scan()} starts one daemon thread the first time it is called (the
 * title screen, or server start on a dedicated server) and memoises the future for the JVM — the
 * installed files do not change while the game runs. {@link #current(long)} waits at most that
 * long and otherwise answers empty, which every caller treats as "not known yet", never as "no
 * files". Any failure is logged and yields an empty list: the hash check can only ever fall back
 * to the id check, never take the game down.</p>
 */
public final class ModFileScanner {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Ids whose file keeps the id-only rule (see the class doc). Mirrored in the relay's EXEMPT_IDS. */
    static final Set<String> EXEMPT_IDS = Set.of("minecraft", "neoforge", "forge", "dungeontrain");

    private static volatile CompletableFuture<List<ModFileFingerprint>> scan;

    private ModFileScanner() {}

    /** Start (once) and return the scan. Never throws; a failed scan completes with an empty list. */
    public static CompletableFuture<List<ModFileFingerprint>> scan() {
        CompletableFuture<List<ModFileFingerprint>> s = scan;
        if (s != null) return s;
        synchronized (ModFileScanner.class) {
            if (scan == null) {
                CompletableFuture<List<ModFileFingerprint>> f = new CompletableFuture<>();
                scan = f;
                Thread t = new Thread(() -> f.complete(scanNow()), "DungeonTrain-ModFileScan");
                t.setDaemon(true);
                t.start();
            }
            return scan;
        }
    }

    /**
     * The fingerprints if the scan has finished within {@code timeoutMs} (0 = don't wait), else an
     * empty list. Starts the scan if nothing has yet.
     */
    public static List<ModFileFingerprint> current(long timeoutMs) {
        try {
            return timeoutMs <= 0 ? scan().getNow(List.of()) : scan().get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** Has the scan finished (with any result)? */
    public static boolean isDone() {
        CompletableFuture<List<ModFileFingerprint>> s = scan;
        return s != null && s.isDone();
    }

    /** The fingerprint whose file declares {@code modId}, or null. Non-blocking. */
    public static ModFileFingerprint fileOf(String modId) {
        String id = ModIds.normalise(modId);
        for (ModFileFingerprint f : current(0)) {
            if (f.modIds().contains(id)) return f;
        }
        return null;
    }

    /** The whole scan, on the scanner thread. Never throws. */
    private static List<ModFileFingerprint> scanNow() {
        long t0 = System.nanoTime();
        try {
            Map<Path, List<String>> files = installedFiles();
            List<ModFileFingerprint> out = new ArrayList<>(files.size());
            for (Map.Entry<Path, List<String>> e : files.entrySet()) {
                try {
                    out.add(fingerprint(e.getKey(), e.getValue()));
                } catch (Exception ex) {
                    LOGGER.warn("[DungeonTrain] mod file scan: could not hash {}: {}", e.getKey(), ex.toString());
                }
            }
            LOGGER.info("[DungeonTrain] mod file scan: {} file(s) fingerprinted in {} ms",
                out.size(), (System.nanoTime() - t0) / 1_000_000);
            return List.copyOf(out);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] mod file scan failed — the hash check is skipped this launch: {}", t.toString());
            return List.of();
        }
    }

    /** Installed jar → the ids it declares (its own and its embedded jars'), exemptions applied. */
    private static Map<Path, List<String>> installedFiles() {
        Map<Path, List<String>> files = new LinkedHashMap<>();
        Set<Path> exempt = new java.util.HashSet<>();
        for (var info : ModList.get().getMods()) {
            IModFile root = outermost(info.getOwningFile().getFile());
            Path path = root.getFilePath();
            String id = ModIds.normalise(info.getModId());
            if (EXEMPT_IDS.contains(id)) exempt.add(path);
            files.computeIfAbsent(path, p -> new ArrayList<>()).add(id);
        }
        Map<Path, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<Path, List<String>> e : files.entrySet()) {
            if (exempt.contains(e.getKey()) || !isPlainJar(e.getKey())) continue;
            out.put(e.getKey(), List.copyOf(e.getValue()));
        }
        return out;
    }

    /** Walk a jar-in-jar entry up to the file the player installed. */
    static IModFile outermost(IModFile file) {
        IModFile f = file;
        for (int i = 0; i < 16; i++) {
            IModFile parent = f.getDiscoveryAttributes() == null ? null : f.getDiscoveryAttributes().parent();
            if (parent == null) return f;
            f = parent;
        }
        return f;
    }

    /** A regular file on the default file system whose name ends in {@code .jar}. */
    static boolean isPlainJar(Path p) {
        try {
            return p != null && p.getFileSystem() == java.nio.file.FileSystems.getDefault()
                && Files.isRegularFile(p)
                && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Hash one file: SHA-1 and CurseForge's whitespace-stripped length in one pass, the murmur2 in a
     * second (the file is in the page cache by then). Package-visible for tests.
     */
    static ModFileFingerprint fingerprint(Path jar, List<String> modIds) throws IOException {
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e); // SHA-1 is mandatory in every JRE
        }
        long size = 0;
        long normalized = 0;
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(jar)) {
            int read;
            while ((read = in.read(buf)) >= 0) {
                sha1.update(buf, 0, read);
                size += read;
                for (int i = 0; i < read; i++) {
                    if (!CurseForgeFingerprint.isWhitespace(buf[i] & 0xff)) normalized++;
                }
            }
        }
        long murmur2;
        try (InputStream in = Files.newInputStream(jar)) {
            murmur2 = CurseForgeFingerprint.hash(in, normalized);
        }
        List<String> packages;
        try {
            packages = PackageFingerprint.ofJar(jar);
        } catch (Exception e) {
            // No package knowledge is "unknown", never "suspect" — see UnapprovedModIntegrity.tierOf.
            LOGGER.debug("[DungeonTrain] mod file scan: could not read packages of {}: {}", jar, e.toString());
            packages = List.of();
        }
        return new ModFileFingerprint(jar.getFileName().toString(), size,
            HexFormat.of().formatHex(sha1.digest()), murmur2, modIds, packages);
    }

    /** Test seam: forget the memoised scan. */
    static synchronized void resetForTest() {
        scan = null;
    }
}
