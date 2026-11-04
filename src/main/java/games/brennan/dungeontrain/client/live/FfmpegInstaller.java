package games.brennan.dungeontrain.client.live;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.mixin.vista.VistaModClientAccessor;
import net.mehvahdjukaar.vista.configs.ClientConfigs;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpeg;
import net.mehvahdjukaar.vista.client.web.ffmpeg.FFmpegManager;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Gets this client an ffmpeg when Vista could not: its default download source is unreachable from
 * some networks, and a player who chose "Don't install" has no way back in game. DT fetches its own
 * copy ({@link FfmpegMirror}, pinned by size and SHA-256), unpacks ffmpeg and ffprobe into the folder
 * Vista manages, and hands Vista the result. From then on Vista finds the binaries itself at launch,
 * so its install prompt stops coming back.
 *
 * <p>Runs on the IO pool; the render thread only reads {@link #running} and {@link #progress}.
 */
public final class FfmpegInstaller {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Vista's managed-binaries folder, relative to the game directory exactly as Vista resolves it. */
    static final Path BIN = Path.of("vista_ffmpeg_bin");

    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile int progress = -1;
    /** The automatic retry after Vista's own download failed runs once per launch. */
    private static final AtomicBoolean AUTO_TRIED = new AtomicBoolean();

    /** Outcome listener for the chat lines ({@link FfmpegHint}); null in tests. */
    @Nullable private static volatile Listener listener;

    /** Why an install ended without a working ffmpeg. */
    enum Failure {
        /** The files never arrived. */
        DOWNLOAD,
        /** The files are there but Vista couldn't run them. */
        WONT_RUN
    }

    interface Listener {
        void installed();
        void failed(Failure failure);
    }

    private FfmpegInstaller() {}

    static void setListener(@Nullable Listener l) {
        listener = l;
    }

    /** True while DT is fetching or unpacking ffmpeg. */
    public static boolean running() {
        return RUNNING.get();
    }

    /** Download percentage while {@link #running}, or -1 when unknown. */
    public static int progress() {
        return progress;
    }

    /**
     * The "Set up FFmpeg" button: switch Vista's ffmpeg back on, then fetch DT's copy where there is
     * one for this platform, else Vista's own default download.
     */
    public static void install() {
        if (FfmpegSupport.state() == FfmpegSupport.State.READY) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        progress = -1;
        enableVistaFfmpeg();
        if (FFmpegManager.hasManagedBinaries()) {
            LOGGER.info("[DungeonTrain] ffmpeg is already in {}; handing it to Vista", BIN.toAbsolutePath());
            handToVista(FFmpegManager.getOrDownload(null));
            return;
        }
        FfmpegMirror mirror = FfmpegMirror.current();
        if (mirror != null) {
            Util.ioPool().execute(() -> fromMirror(mirror));
            return;
        }
        LOGGER.info("[DungeonTrain] No DT ffmpeg mirror for this platform; asking Vista to download its own");
        handToVista(FFmpegManager.getOrDownload(null));
    }

    /** Vista's own download failed: try DT's copy once per launch, without asking again (the player already chose to install). */
    static void afterVistaFailed() {
        FfmpegMirror mirror = FfmpegMirror.current();
        if (mirror == null || !AUTO_TRIED.compareAndSet(false, true)) return;
        if (!RUNNING.compareAndSet(false, true)) return;
        progress = -1;
        LOGGER.info("[DungeonTrain] Vista's ffmpeg download failed; trying DT's copy at {}", mirror.url());
        Util.ioPool().execute(() -> fromMirror(mirror));
    }

    private static void fromMirror(FfmpegMirror mirror) {
        try {
            Files.createDirectories(BIN);
            // Outlives a failed try: the next one, or the next launch, carries on from it.
            Path zip = BIN.resolve("dungeontrain-ffmpeg.zip.part");
            FfmpegDownload.fetch(mirror, zip, p -> progress = p);
            try (InputStream in = Files.newInputStream(zip)) {
                extractBinaries(in, BIN, binaryNames());
            }
            Files.deleteIfExists(zip);
            LOGGER.info("[DungeonTrain] Installed ffmpeg from {}", mirror.url());
            // Vista now finds the managed binaries, runs its own -version check and keeps them.
            handToVista(FFmpegManager.getOrDownload(null));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[DungeonTrain] Could not install ffmpeg from {}: {}", mirror.url(), e.toString());
            finish(Failure.DOWNLOAD);
        }
    }

    /** Make {@code future} the ffmpeg every Vista TV and the Live Feed use, and report how it ends. */
    private static void handToVista(CompletableFuture<FFmpeg> future) {
        Minecraft.getInstance().execute(() -> VistaModClientAccessor.dungeontrain$setFfmpegFuture(future));
        future.whenComplete((ffmpeg, e) -> {
            if (e != null) LOGGER.warn("[DungeonTrain] Vista rejected the ffmpeg install: {}", e.toString());
            finish(e == null ? null : failureOf(e));
        });
    }

    /** Vista's run check throws its own type, sometimes wrapped; anything else stopped it getting the files. */
    static Failure failureOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof FFmpegManager.UnusableFFmpegException) return Failure.WONT_RUN;
        }
        return Failure.DOWNLOAD;
    }

    /** {@code failure} is null on success. */
    private static void finish(@Nullable Failure failure) {
        RUNNING.set(false);
        progress = -1;
        Listener l = listener;
        if (l == null) return;
        Minecraft.getInstance().execute(() -> {
            if (failure == null) l.installed(); else l.failed(failure);
        });
    }

    /** Undo Vista's "Don't install": it saves {@code ffmpeg_enabled=false}, which also skips its startup check. */
    private static void enableVistaFfmpeg() {
        if (ClientConfigs.canUseFFmpeg()) return;
        ClientConfigs.SPEC.manuallySetValue(ClientConfigs.ENABLE_FFMPEG, true);
        LOGGER.info("[DungeonTrain] Switched Vista's ffmpeg back on");
    }

    /**
     * Unpack the entries of {@code zip} whose file name (folders ignored) is one of {@code names} into
     * {@code dir}, failing unless every name was there. Only those fixed names are ever written, so no
     * entry path can reach outside {@code dir}. Pure, for the tests.
     */
    static void extractBinaries(InputStream zip, Path dir, Set<String> names) throws IOException {
        Set<String> missing = new HashSet<>(names);
        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = fileName(entry.getName());
                if (!missing.remove(name)) continue;
                Path tmp = dir.resolve(name + ".part");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                if (!name.endsWith(".exe")) dir.resolve(name).toFile().setExecutable(true);
            }
        }
        if (!missing.isEmpty()) throw new IOException("archive is missing " + missing);
    }

    /** The executables Vista looks for on this OS. */
    private static Set<String> binaryNames() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return windows ? Set.of("ffmpeg.exe", "ffprobe.exe") : Set.of("ffmpeg", "ffprobe");
    }

    private static String fileName(String entryName) {
        String n = entryName.replace('\\', '/');
        return n.substring(n.lastIndexOf('/') + 1);
    }
}
