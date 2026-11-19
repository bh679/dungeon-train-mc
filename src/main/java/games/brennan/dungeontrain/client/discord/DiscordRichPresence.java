package games.brennan.dungeontrain.client.discord;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Objects;

/**
 * Optional Discord status: while the player's Discord status switch is on, their Discord profile shows
 * "Playing Dungeon Train" with one line of where they are ({@link PresenceSnapshot}). On by default
 * (Options → Account turns it off; never in Kid mode).
 *
 * <p>All Discord IO runs on one daemon thread, started the first time the switch is on, so a slow or
 * missing Discord can never stall a frame. The client thread only calls {@link #setEnabled} and
 * {@link #update}, which just store the wanted state. The worker connects when enabled, sends the snapshot
 * when it changes, clears it and disconnects when disabled, and retries a missing Discord every
 * {@link #RETRY_MILLIS}. Discord itself clears the card when the game exits and the socket closes.</p>
 *
 * <p>"Playing <i>name</i>" is the name of the Discord Developer Portal application whose id is
 * {@link #APPLICATION_ID}. Until that is filled in the switch does nothing.</p>
 */
public final class DiscordRichPresence {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The Discord application "Dungeon Train" (discord.com/developers/applications). Public, not a secret.
     * Shared with the relay bot (its DISCORD_CLIENT_ID); blank would mean nothing ever connects.
     */
    public static final String APPLICATION_ID = "1515159664818524220";

    static final long RETRY_MILLIS = 30_000L;
    private static final long IDLE_WAIT_MILLIS = 1_000L;

    private static final Object LOCK = new Object();
    private static boolean enabled;
    private static PresenceSnapshot wanted;
    private static Thread worker;
    private static boolean warnedNoApplicationId;

    private DiscordRichPresence() {}

    /** Turn the Discord status on or off. Cheap and idempotent; safe to call every tick. */
    public static void setEnabled(boolean on) {
        synchronized (LOCK) {
            if (enabled == on) return;
            enabled = on;
            if (on && APPLICATION_ID.isBlank()) {
                if (!warnedNoApplicationId) {
                    warnedNoApplicationId = true;
                    LOGGER.info("[DungeonTrain] Discord status is on, but no Discord application id is set yet; nothing will show.");
                }
                return;
            }
            if (on) startWorkerIfNeeded();
            LOCK.notifyAll();
        }
    }

    /** The card to show. Only sent when it differs from what Discord already has. */
    public static void update(PresenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (LOCK) {
            if (snapshot.equals(wanted)) return;
            wanted = snapshot;
            LOCK.notifyAll();
        }
    }

    private static void startWorkerIfNeeded() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(DiscordRichPresence::runWorker, "DungeonTrain Discord status");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------- worker thread

    private static void runWorker() {
        DiscordIpcConnection connection = null;
        PresenceSnapshot sent = null;
        long nextAttempt = 0L;
        boolean loggedUnavailable = false;
        while (true) {
            boolean on;
            PresenceSnapshot target;
            synchronized (LOCK) {
                try {
                    LOCK.wait(IDLE_WAIT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    closeQuietly(connection);
                    return;
                }
                on = enabled && !APPLICATION_ID.isBlank();
                target = wanted;
            }

            if (!on) {
                if (connection != null) {
                    clearQuietly(connection);
                    closeQuietly(connection);
                    connection = null;
                    sent = null;
                    LOGGER.info("[DungeonTrain] Discord status turned off; cleared.");
                }
                nextAttempt = 0L;
                continue;
            }

            long now = System.currentTimeMillis();
            if (connection == null) {
                if (now < nextAttempt) continue;
                try {
                    connection = DiscordIpcConnection.open(APPLICATION_ID);
                    sent = null;
                    loggedUnavailable = false;
                    LOGGER.info("[DungeonTrain] Discord status connected.");
                } catch (IOException e) {
                    nextAttempt = now + RETRY_MILLIS;
                    if (!loggedUnavailable) {
                        loggedUnavailable = true;
                        LOGGER.debug("[DungeonTrain] Discord status: Discord not reachable ({}); retrying every {} s.",
                                e.getMessage(), RETRY_MILLIS / 1000);
                    }
                    continue;
                }
            }

            if (target != null && !target.equals(sent)) {
                try {
                    connection.setActivity(target);
                    sent = target;
                } catch (IOException | RuntimeException e) {
                    LOGGER.debug("[DungeonTrain] Discord status: lost Discord ({}); retrying.", e.getMessage());
                    closeQuietly(connection);
                    connection = null;
                    sent = null;
                    nextAttempt = now + RETRY_MILLIS;
                }
            }
        }
    }

    private static void clearQuietly(DiscordIpcConnection connection) {
        try {
            connection.setActivity(null);
        } catch (IOException | RuntimeException ignored) {
            // Closing the socket clears the card anyway.
        }
    }

    private static void closeQuietly(DiscordIpcConnection connection) {
        if (connection != null) connection.close();
    }
}
