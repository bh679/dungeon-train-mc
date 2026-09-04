package games.brennan.dungeontrain.client.shaders;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches a shader pack's zip into {@code <gameDir>/shaderpacks/}.
 *
 * <p>Nothing is mirrored: the URL is the author's own Modrinth CDN link out of the generated
 * manifest, fetched only when a player clicks Download, the way a launcher does it. Three things
 * guard that fetch, and all three matter because the result is a file the game will later load:</p>
 *
 * <ul>
 *   <li><b>Host allowlist</b> — https and {@link ShaderPack#ALLOWED_HOST} only, re-checked here as
 *       well as at manifest load, including across any redirect.</li>
 *   <li><b>Size cap</b> — the manifest says how big the file is; anything materially larger is a
 *       different file and the stream is abandoned rather than filling the player's disk.</li>
 *   <li><b>SHA-512</b> — computed while streaming and compared to the manifest. A mismatch deletes
 *       the download rather than installing it.</li>
 * </ul>
 *
 * <p>The zip lands as {@code <name>.part} and is moved into place only once it has verified, so an
 * interrupted download can never leave Iris a half-written pack to choke on.</p>
 *
 * <p>Follows the {@code GitHubLatestReleaseFetcher} idiom — one shared single-thread daemon
 * executor, so concurrent clicks queue rather than saturating the link, and nothing here blocks JVM
 * shutdown. Progress is published into a concurrent map the screen polls each frame; no game state
 * is touched off the render thread.</p>
 */
public final class ShaderPackDownloader {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    /** Headroom over the manifest size for a re-zipped-but-identical build; beyond it, abandon. */
    private static final long SIZE_SLACK = 4L * 1024 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DungeonTrain-ShaderDownload");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(EXECUTOR)
            .build();

    /** Bytes received so far, by pack id, while a download is in flight. */
    private static final Map<String, Long> PROGRESS = new ConcurrentHashMap<>();
    /** Why the last attempt failed, by pack id. Cleared when a fresh attempt starts. */
    private static final Map<String, String> ERRORS = new ConcurrentHashMap<>();

    private ShaderPackDownloader() {}

    public static boolean isDownloading(ShaderPack pack) {
        return PROGRESS.containsKey(pack.id());
    }

    /** 0..1 for the progress bar, or 0 when nothing is in flight. */
    public static float progress(ShaderPack pack) {
        Long done = PROGRESS.get(pack.id());
        if (done == null || pack.size() <= 0) {
            return 0F;
        }
        return Math.min(1F, (float) (done / (double) pack.size()));
    }

    /** The last failure for this pack, or {@code null}. */
    public static String errorFor(ShaderPack pack) {
        return ERRORS.get(pack.id());
    }

    /**
     * Start a download. {@code onDone} runs on the download thread with {@code true} on success —
     * callers hop back to the render thread themselves.
     */
    public static void start(ShaderPack pack, java.util.function.Consumer<Boolean> onDone) {
        if (isDownloading(pack)) {
            return;
        }
        ERRORS.remove(pack.id());
        PROGRESS.put(pack.id(), 0L);
        EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                fetch(pack);
                ok = true;
                LOGGER.info("[DungeonTrain] Downloaded shader pack {}", pack.filename());
            } catch (Exception e) {
                ERRORS.put(pack.id(), e.getMessage() == null ? e.toString() : e.getMessage());
                LOGGER.warn("[DungeonTrain] Shader pack download failed ({}): {}",
                        pack.filename(), e.toString());
            } finally {
                PROGRESS.remove(pack.id());
                onDone.accept(ok);
            }
        });
    }

    private static void fetch(ShaderPack pack) throws Exception {
        URI uri = URI.create(pack.url());
        requireAllowed(uri);

        Path dir = ShaderPackLibrary.directory();
        Files.createDirectories(dir);
        Path target = dir.resolve(pack.filename());
        Path part = dir.resolve(pack.filename() + ".part");
        Files.deleteIfExists(part);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("User-Agent", "DungeonTrain-Mod/" + modVersion())
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response =
                CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        // A redirect could have walked off the allowlist even though the first URL was on it.
        requireAllowed(response.uri());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        long total = 0L;
        long cap = pack.size() + SIZE_SLACK;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > cap) {
                    throw new IllegalStateException("download is larger than the manifest says");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
                PROGRESS.put(pack.id(), total);
            }
        } catch (Exception e) {
            Files.deleteIfExists(part);
            throw e;
        }

        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(pack.sha512())) {
            Files.deleteIfExists(part);
            throw new IllegalStateException("the download did not match its checksum");
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void requireAllowed(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !ShaderPack.ALLOWED_HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalStateException("refused a download from " + uri.getHost());
        }
    }

    private static String modVersion() {
        try {
            return ModList.get().getModContainerById(DungeonTrain.MOD_ID)
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
