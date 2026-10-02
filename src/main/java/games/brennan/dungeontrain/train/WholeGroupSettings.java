package games.brennan.dungeontrain.train;

import games.brennan.dungeontrain.DungeonTrain;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * The whole-group <b>test cadence</b>: {@link #force(int)} makes exactly every Nth carriage group a
 * whole group regardless of the seed, for the length of the session.
 *
 * <p>How often a group is a whole group in play is not set here. It is the weight of the
 * {@link WholeGroupSelection#VARIANT_ID Whole Group} template in the Group row, the same way the
 * {@code whole} template's weight is the whole-room rate — see {@link WholeGroupSelection}.</p>
 */
@EventBusSubscriber(modid = DungeonTrain.MOD_ID)
public final class WholeGroupSettings {

    public static final int OFF = 0;
    public static final int MAX_EVERY = 1000;

    private static volatile int forced = OFF;

    private WholeGroupSettings() {}

    /** The session-only periodic override, or {@link #OFF} when none. */
    public static int forced() {
        return forced;
    }

    public static void force(int n) {
        forced = clamp(n);
    }

    public static int clamp(int n) {
        return Math.max(OFF, Math.min(MAX_EVERY, n));
    }

    /** A content reload ends any forced cadence, as a server restart does. */
    public static synchronized void reload() {
        forced = OFF;
    }

    public static synchronized void clear() {
        forced = OFF;
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reload();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clear();
    }
}
