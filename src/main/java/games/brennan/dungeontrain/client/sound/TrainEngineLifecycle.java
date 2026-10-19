package games.brennan.dungeontrain.client.sound;

/**
 * What {@link TrainSoundManager} does with the engine loop each client tick.
 *
 * <p><b>Liveness comes from the sound engine, not the instance.</b> Vanilla's {@code SoundEngine}
 * can drop a looping sound's channel without ever marking the instance stopped: a category slider at
 * zero stops and unmaps the channel but leaves the instance ticking, a slider dragged to zero and back
 * while paused leaves a stopped channel that is unmapped on unpause, and a sound played while Master is
 * zero is skipped outright. In each case {@code isStopped()} stays {@code false}, so a manager that
 * only respawned stopped instances held a dead one until the world was rejoined — the engine stayed
 * silent after the player unmuted.</p>
 *
 * <p><b>Muted is held, not ticked.</b> While the engine cannot carry the sound (Master or Ambient at
 * zero) the instance is stopped and the spawn waits, exactly as for the player's own Train Engine
 * Volume at zero; raising the slider spawns a fresh one on the next tick.</p>
 *
 * <p>Pure logic, no Minecraft imports, so it unit-tests without a NeoForge bootstrap.</p>
 */
public final class TrainEngineLifecycle {

    /** Ticks between attempts to replace an instance the engine is not playing. */
    public static final int RETRY_INTERVAL_TICKS = 20;

    public enum Action {
        /** The active instance is playing — leave it. */
        KEEP,
        /** Stop the active instance and hold the spawn. */
        STOP,
        /** Spawn a new instance, replacing the active one if there is one. */
        SPAWN,
        /** Nothing to do this tick. */
        WAIT
    }

    private TrainEngineLifecycle() {}

    /**
     * @param settingAudible the player's Train Engine Volume is above zero
     * @param engineCanHold  the sound engine would keep the loop registered (Master and Ambient above
     *                       zero, or a stream is live and keeps muted sounds alive)
     * @param hasActive      the manager holds an instance
     * @param activeLive     the sound engine is still playing that instance
     * @param retryDue       enough ticks have passed since the last spawn to try again
     */
    public static Action decide(boolean settingAudible, boolean engineCanHold,
                                boolean hasActive, boolean activeLive, boolean retryDue) {
        if (!settingAudible || !engineCanHold) {
            return hasActive ? Action.STOP : Action.WAIT;
        }
        if (!hasActive) {
            return Action.SPAWN;
        }
        if (activeLive) {
            return Action.KEEP;
        }
        return retryDue ? Action.SPAWN : Action.WAIT;
    }
}
