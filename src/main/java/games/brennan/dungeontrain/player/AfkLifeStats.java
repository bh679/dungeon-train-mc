package games.brennan.dungeontrain.player;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.event.PlayerActivityTracker.Reason;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-life record of how the AFK rules treated a player, for the run summary the relay receives at
 * death ({@code RunSummaryReporter}). Answers what the relay could not before: which rule stopped
 * the train clock, how long for, how often the pause menu opened on its own, and how many
 * advancements the AFK gate refused.
 *
 * <ul>
 *   <li><b>Stopped ticks</b> per reason — server ticks the world ran with the clock stopped.
 *       {@link Reason#PAUSED} has no bucket: a single-player server does not tick behind a pause
 *       screen, so it would read near zero, and reading time is already in the book telemetry.</li>
 *   <li><b>Container ticks</b> — of those, the ticks a container menu was open.</li>
 *   <li><b>Stops</b> per reason — how many times the clock stopped for it.</li>
 *   <li><b>Auto-pauses</b> — the pause menu opening on its own, in the world or over a screen.</li>
 *   <li><b>Refused</b> — distinct advancements the AFK gate turned down.</li>
 * </ul>
 *
 * <p>Its own attachment for the reason {@link PlayerBiomeProgress} is: {@link PlayerRunState}'s codec
 * is at the 16-field cap. Persisted so a life that spans a quit and a reload keeps its counts; not
 * {@code copyOnDeath}, and cleared on respawn by {@code AchievementEvents}.</p>
 */
public final class AfkLifeStats {

    /** Cap on the refused set, so a stuck gate cannot grow a save without bound. */
    static final int MAX_REFUSED = 512;

    public static final Codec<AfkLifeStats> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.LONG.optionalFieldOf("mouseIdleTicks", 0L).forGetter(s -> s.mouseIdleTicks),
        Codec.LONG.optionalFieldOf("inputIdleTicks", 0L).forGetter(s -> s.inputIdleTicks),
        Codec.LONG.optionalFieldOf("noProgressTicks", 0L).forGetter(s -> s.noProgressTicks),
        Codec.LONG.optionalFieldOf("containerTicks", 0L).forGetter(s -> s.containerTicks),
        Codec.INT.optionalFieldOf("mouseIdleStops", 0).forGetter(s -> s.mouseIdleStops),
        Codec.INT.optionalFieldOf("inputIdleStops", 0).forGetter(s -> s.inputIdleStops),
        Codec.INT.optionalFieldOf("noProgressStops", 0).forGetter(s -> s.noProgressStops),
        Codec.INT.optionalFieldOf("pausedStops", 0).forGetter(s -> s.pausedStops),
        Codec.INT.optionalFieldOf("autoPausesWorld", 0).forGetter(s -> s.autoPausesWorld),
        Codec.INT.optionalFieldOf("autoPausesScreen", 0).forGetter(s -> s.autoPausesScreen),
        ResourceLocation.CODEC.listOf().optionalFieldOf("refused", List.of()).forGetter(AfkLifeStats::refusedList)
    ).apply(instance, AfkLifeStats::new));

    private static final int TICKS_PER_SECOND = 20;

    private long mouseIdleTicks;
    private long inputIdleTicks;
    private long noProgressTicks;
    private long containerTicks;
    private int mouseIdleStops;
    private int inputIdleStops;
    private int noProgressStops;
    private int pausedStops;
    private int autoPausesWorld;
    private int autoPausesScreen;
    private final Set<ResourceLocation> refused;

    public AfkLifeStats() {
        this.refused = new HashSet<>();
    }

    AfkLifeStats(long mouseIdleTicks, long inputIdleTicks, long noProgressTicks, long containerTicks,
                 int mouseIdleStops, int inputIdleStops, int noProgressStops, int pausedStops,
                 int autoPausesWorld, int autoPausesScreen, List<ResourceLocation> refused) {
        this.mouseIdleTicks = mouseIdleTicks;
        this.inputIdleTicks = inputIdleTicks;
        this.noProgressTicks = noProgressTicks;
        this.containerTicks = containerTicks;
        this.mouseIdleStops = mouseIdleStops;
        this.inputIdleStops = inputIdleStops;
        this.noProgressStops = noProgressStops;
        this.pausedStops = pausedStops;
        this.autoPausesWorld = autoPausesWorld;
        this.autoPausesScreen = autoPausesScreen;
        this.refused = new HashSet<>(refused);
    }

    // -------------------------------------------------------------- recording

    /**
     * {@code ticks} of world time passed with the clock stopped for {@code reason}. Tracking and
     * paused add nothing (see the class note); {@code inContainer} also counts them as container time.
     */
    public void addStopped(Reason reason, long ticks, boolean inContainer) {
        if (ticks <= 0L) return;
        switch (reason) {
            case MOUSE_IDLE -> mouseIdleTicks += ticks;
            case INPUT_IDLE -> inputIdleTicks += ticks;
            case NO_PROGRESS -> noProgressTicks += ticks;
            default -> { return; }
        }
        if (inContainer) containerTicks += ticks;
    }

    /** The clock just stopped for {@code reason}. */
    public void addStop(Reason reason) {
        switch (reason) {
            case MOUSE_IDLE -> mouseIdleStops++;
            case INPUT_IDLE -> inputIdleStops++;
            case NO_PROGRESS -> noProgressStops++;
            case PAUSED -> pausedStops++;
            default -> { }
        }
    }

    /** The pause menu opened on its own — over a screen, or in the world. */
    public void addAutoPause(boolean overScreen) {
        if (overScreen) {
            autoPausesScreen++;
        } else {
            autoPausesWorld++;
        }
    }

    /** The AFK gate refused {@code advancement}. Counted once per advancement per life. */
    public void addRefused(ResourceLocation advancement) {
        if (refused.size() < MAX_REFUSED) refused.add(advancement);
    }

    /** Start a new life. */
    public void clear() {
        mouseIdleTicks = inputIdleTicks = noProgressTicks = containerTicks = 0L;
        mouseIdleStops = inputIdleStops = noProgressStops = pausedStops = 0;
        autoPausesWorld = autoPausesScreen = 0;
        refused.clear();
    }

    // ---------------------------------------------------------------- reading

    public List<ResourceLocation> refusedList() {
        return new ArrayList<>(refused);
    }

    public int refusedCount() {
        return refused.size();
    }

    public int autoPausesWorld() {
        return autoPausesWorld;
    }

    public int autoPausesScreen() {
        return autoPausesScreen;
    }

    /** Seconds stopped for {@code reason}; zero for tracking and paused. */
    public long stoppedSec(Reason reason) {
        long ticks = switch (reason) {
            case MOUSE_IDLE -> mouseIdleTicks;
            case INPUT_IDLE -> inputIdleTicks;
            case NO_PROGRESS -> noProgressTicks;
            default -> 0L;
        };
        return ticks / TICKS_PER_SECOND;
    }

    public long containerSec() {
        return containerTicks / TICKS_PER_SECOND;
    }

    /** Times the clock stopped for {@code reason}; zero for tracking. */
    public int stops(Reason reason) {
        return switch (reason) {
            case MOUSE_IDLE -> mouseIdleStops;
            case INPUT_IDLE -> inputIdleStops;
            case NO_PROGRESS -> noProgressStops;
            case PAUSED -> pausedStops;
            default -> 0;
        };
    }

    /** The {@code afk} object of the run-summary payload. */
    public JsonObject toJson() {
        JsonObject stopped = new JsonObject();
        stopped.addProperty("mouseIdle", stoppedSec(Reason.MOUSE_IDLE));
        stopped.addProperty("inputIdle", stoppedSec(Reason.INPUT_IDLE));
        stopped.addProperty("noProgress", stoppedSec(Reason.NO_PROGRESS));

        JsonObject stops = new JsonObject();
        stops.addProperty("mouseIdle", mouseIdleStops);
        stops.addProperty("inputIdle", inputIdleStops);
        stops.addProperty("noProgress", noProgressStops);
        stops.addProperty("paused", pausedStops);

        JsonObject autoPauses = new JsonObject();
        autoPauses.addProperty("world", autoPausesWorld);
        autoPauses.addProperty("screen", autoPausesScreen);

        JsonObject afk = new JsonObject();
        afk.add("stoppedSec", stopped);
        afk.addProperty("containerSec", containerSec());
        afk.add("stops", stops);
        afk.add("autoPauses", autoPauses);
        afk.addProperty("refused", refused.size());
        return afk;
    }
}
