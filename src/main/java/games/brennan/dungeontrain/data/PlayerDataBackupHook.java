package games.brennan.dungeontrain.data;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.VersionInfo;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Decides <em>when</em> a restore point is taken, and takes it off the server thread.
 *
 * <p>Backups happen at the four moments a player would be upset to lose the work either side of:
 * loading a world, <b>saving a build in the Train Editor</b>, <b>dying</b>, and leaving the world.
 * The load/exit pair alone left a whole editing session unprotected — a carriage built and then
 * lost before the next world load was in no archive at all, which is the exact failure this area
 * exists to prevent.</p>
 *
 * <p><b>Why it is debounced and asynchronous.</b> An editing session saves constantly, and a backup
 * is a full walk-and-zip of the data root. Doing that inline would hitch the game on every save,
 * and doing it on every save would be almost entirely redundant work. So a request marks the
 * install dirty and a single daemon thread writes at most one archive per
 * {@link #MIN_INTERVAL_MS}; the content digest in {@link PlayerDataBackup} then skips even that
 * when nothing actually changed. Session end is the exception — it runs <b>synchronously</b>,
 * because the JVM may exit before a queued task would run.</p>
 *
 * <p>The walk can overlap a save in progress, so an archive may catch a file mid-write. That is
 * accepted deliberately: archives are written atomically and never replace each other, so the
 * previous restore point is always intact, and one suspect entry in the newest archive is a far
 * smaller problem than not having the archive.</p>
 *
 * <p>Separate from {@link PlayerDataBackup} so that class stays free of Forge types and testable
 * against {@code @TempDir}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class PlayerDataBackupHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Floor on the gap between two automatic backups. Two minutes is short enough that little work
     * is ever at risk and long enough that a save-tweak-save editing loop doesn't thrash the disk.
     */
    static final long MIN_INTERVAL_MS = 120_000L;

    /** Single daemon thread: backups are strictly serialised and never hold up shutdown. */
    private static final ScheduledExecutorService WORKER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DungeonTrain-Backup");
            t.setDaemon(true);
            return t;
        });

    private static long lastBackupAt = 0L;
    private static ScheduledFuture<?> scheduled = null;
    private static String pendingReason = null;

    /** Whether this world load has already been backed up on the way in. */
    private static volatile boolean backedUpThisLoad = false;

    private PlayerDataBackupHook() {}

    /**
     * The player's chosen backup mode.
     *
     * <p>Read reflectively-safely: the setting lives in the CLIENT config, and this class also runs
     * on dedicated servers where that config was never loaded. A dedicated server therefore keeps
     * the default rather than silently stopping backups — losing a server's data because a
     * client-side toggle wasn't there would be the worst possible reading of the setting.</p>
     */
    static BackupMode mode() {
        BackupMode override = operatorOverride();
        if (override != null) return override;
        try {
            return games.brennan.dungeontrain.config.ClientDisplayConfig.getBackupMode();
        } catch (Throwable notOnThisSide) {
            return BackupMode.DEFAULT;
        }
    }

    /** System property / environment names a server operator can set. */
    static final String OVERRIDE_PROPERTY = "dungeontrain.backups";
    static final String OVERRIDE_ENV = "DUNGEONTRAIN_BACKUPS";

    /** Resolved once: neither the property nor the environment changes while the JVM runs. */
    private static volatile BackupMode override = null;
    private static volatile boolean overrideResolved = false;

    /**
     * The launch-flag override, or {@code null} when unset.
     *
     * <p>This exists because the setting above is a CLIENT config, so a dedicated server had no way
     * to turn backups off at all — the options screen isn't there, and the two config files a
     * server operator would reach for are held to their defaults by {@code DtConfigIntegrity},
     * so putting it in one of those would taint every player on the server into Free Play.
     * {@code -Ddungeontrain.backups=off} (or {@code DUNGEONTRAIN_BACKUPS=off}) touches no config
     * file and cannot interact with the integrity checks.</p>
     *
     * <p>It wins over the options screen on clients too. Nobody sets a JVM flag by accident, and an
     * override that silently lost to a UI toggle would be worse than not having one — so it is
     * logged once at startup to stay discoverable.</p>
     */
    static BackupMode operatorOverride() {
        if (overrideResolved) return override;
        synchronized (PlayerDataBackupHook.class) {
            if (overrideResolved) return override;
            BackupMode resolved;
            String source = OVERRIDE_PROPERTY;
            try {
                String property = System.getProperty(OVERRIDE_PROPERTY);
                // Name the source that actually supplied the value: telling an operator who set the
                // environment variable that a system property did it sends them to the wrong place
                // when they want to change it back.
                if (property == null || property.isBlank()) source = OVERRIDE_ENV;
                resolved = BackupMode.overrideFrom(property, System.getenv(OVERRIDE_ENV));
            } catch (SecurityException restricted) {
                resolved = null;
            }
            if (resolved != null) {
                LOGGER.info("[DungeonTrain] Backup mode forced to {} by {} — the Options setting is "
                    + "ignored while it is set.", resolved, source);
            }
            override = resolved;
            overrideResolved = true;
            return override;
        }
    }

    // ---- Triggers ----

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStarted(ServerStartedEvent event) {
        if (backedUpThisLoad) return;
        backedUpThisLoad = true;
        request("world-load");
    }

    /**
     * A death ends a run, and the profile it wrote to is worth a restore point. Players only —
     * every mob death would fire this hundreds of times a session for nothing.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !player.level().isClientSide) {
            request("death");
        }
    }

    /**
     * A build was just written to disk. Called from {@code BuilderSave} on a successful save — the
     * one moment where there is brand-new work that exists nowhere else.
     */
    public static void onTemplateSaved() {
        request("template-save");
    }

    /**
     * Leaving the world. Runs <b>now</b>, on the calling thread, and cancels anything queued: a
     * scheduled task would lose the race with JVM shutdown, and this is the backup that captures
     * everything the session just did.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerStopped(ServerStoppedEvent event) {
        synchronized (PlayerDataBackupHook.class) {
            if (scheduled != null) {
                scheduled.cancel(false);
                scheduled = null;
            }
            pendingReason = null;
        }
        writeNow("session-end");
        backedUpThisLoad = false;
    }

    // ---- Scheduling ----

    /**
     * Ask for a restore point. Returns immediately; the write happens on the worker thread, no
     * sooner than {@link #MIN_INTERVAL_MS} after the last one. Repeat requests inside that window
     * collapse into the one already queued.
     */
    public static synchronized void request(String reason) {
        if (scheduled != null && !scheduled.isDone()) {
            // Already queued — keep the earlier deadline, but report the most recent cause.
            pendingReason = reason;
            return;
        }
        pendingReason = reason;
        long sinceLast = System.currentTimeMillis() - lastBackupAt;
        long delay = Math.max(0L, MIN_INTERVAL_MS - sinceLast);
        scheduled = WORKER.schedule(PlayerDataBackupHook::runQueued, delay, TimeUnit.MILLISECONDS);
    }

    private static void runQueued() {
        String reason;
        synchronized (PlayerDataBackupHook.class) {
            reason = pendingReason == null ? "periodic" : pendingReason;
            pendingReason = null;
            scheduled = null;
        }
        writeNow(reason);
    }

    /** Write a restore point immediately. Never throws — a failed backup must not stop the game. */
    static void writeNow(String reason) {
        try {
            BackupMode mode = mode();
            if (!mode.writesAnything()) return;
            PlayerDataBackup.Result result = PlayerDataBackup.create(
                PlayerDataPaths.backupsRoot(), sources(), reason, VersionInfo.VERSION);
            if (mode.writesOutsideTheInstance() && result.wrote()) {
                PlayerDataPaths.externalBackupsRoot().ifPresent(
                    external -> PlayerDataBackup.mirror(result.archive().orElseThrow(), external));
            }
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Backup: couldn't write a restore point ({}): {}",
                reason, e.toString());
        } finally {
            synchronized (PlayerDataBackupHook.class) {
                lastBackupAt = System.currentTimeMillis();
            }
        }
    }

    /**
     * Everything worth archiving: the <b>whole</b> data root, plus {@code dtpacks/} — a player's
     * saved packages are their builds too, and they live outside the data root.
     *
     * <p>Taking the root wholesale rather than listing each relocated folder is deliberate. An
     * enumeration missed the loose files that sit directly under the root — {@code
     * dtpacks-state.json}, which records the active package, and the queued uploads in {@code
     * outbox/} — so they were in no archive at all. It also means a folder added later is backed up
     * without anyone remembering to add it here.</p>
     *
     * <p>{@code backups/} excludes itself, or every archive would contain the previous ones.</p>
     */
    public static List<PlayerDataBackup.Source> sources() {
        return List.of(
            new PlayerDataBackup.Source(PlayerDataPaths.ROOT_DIR, PlayerDataPaths.root(),
                Set.of(PlayerDataPaths.BACKUPS)),
            new PlayerDataBackup.Source("dtpacks", PlayerDataPaths.dtpacksRoot()));
    }
}
