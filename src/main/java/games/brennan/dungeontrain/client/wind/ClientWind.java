package games.brennan.dungeontrain.client.wind;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.net.WindProfilePacket;
import games.brennan.dungeontrain.wind.WindField;
import games.brennan.dungeontrain.wind.WindProfile;
import games.brennan.dungeontrain.wind.WindSample;
import org.slf4j.Logger;

/**
 * Client copy of the server's wind state ({@code WindServer.State}), set by {@link WindProfilePacket} and
 * cleared on disconnect. The client evaluates {@link WindField} itself from the shared game time.
 */
public final class ClientWind {

    private static final Logger LOGGER = LogUtils.getLogger();

    private record Snapshot(WindProfile profile, int seed, long startX, boolean on, boolean known) {}

    private static volatile Snapshot snap = new Snapshot(WindProfile.CALM, 0, Long.MIN_VALUE, false, false);

    private ClientWind() {}

    public static void apply(WindProfilePacket p) {
        WindProfile profile;
        try {
            profile = WindProfile.parse(p.profileJson());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[DungeonTrain] wind: server sent an unreadable profile ({}); wind visuals off", e.getMessage());
            profile = WindProfile.CALM;
        }
        snap = new Snapshot(profile, p.seed(), p.startX(), p.on(), true);
    }

    public static void reset() {
        snap = new Snapshot(WindProfile.CALM, 0, Long.MIN_VALUE, false, false);
    }

    public static boolean active() {
        Snapshot s = snap;
        return s.on() && s.profile().enabled() && s.startX() != Long.MIN_VALUE;
    }

    /**
     * True once this server has told us about its wind (a DT server always does at login, even with the wind off).
     * From then on DT holds Windy calm whenever its own wind isn't blowing, instead of letting Windy's own wind show.
     */
    public static boolean known() {
        return snap.known();
    }

    /** World X where the wind starts, {@link Long#MIN_VALUE} for none. */
    public static long startX() {
        return snap.startX();
    }

    public static WindProfile profile() {
        return snap.profile();
    }

    /** True when {@code worldX} is past the wind start (cheap gate before any sampling). */
    public static boolean pastStart(double worldX) {
        Snapshot s = snap;
        return s.startX() != Long.MIN_VALUE && worldX >= s.startX();
    }

    public static WindSample sampleAt(double worldX, long tick) {
        Snapshot s = snap;
        if (!active()) return WindSample.CALM;
        return WindField.sample(s.profile(), worldX - s.startX(), tick, s.seed(),
                        games.brennan.dungeontrain.wind.WindZones.styleAt(worldX))
                .scaled(games.brennan.dungeontrain.wind.WindZones.bandFactor(worldX));
    }
}
