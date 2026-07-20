package games.brennan.dungeontrain.client;

/**
 * Pure decision core for the idle render-framerate cap (no Minecraft types, unit-testable —
 * mirrors {@link games.brennan.dungeontrain.ship.sable.PhysicsFreezeController#decide}).
 *
 * <p><b>Why this exists.</b> Vanilla 1.21.1 never throttles the render loop behind the pause
 * screen. {@code Minecraft#getFramerateLimit()} reads:</p>
 *
 * <pre>{@code
 * return this.level != null || this.screen == null && this.overlay == null
 *     ? this.window.getFramerateLimit() : 60;
 * }</pre>
 *
 * <p>{@code level != null} is true while paused in-world, so it short-circuits to the player's
 * full limit — the 60 fps fallback only ever applies at the main menu. Worse, {@code runTick}
 * only calls {@code RenderSystem.limitDisplayFPS} when the limit is {@code < 260}, so on
 * "Unlimited" (260) there is no cap at all: the full 3D world re-renders an unchanging frame at
 * whatever the GPU can manage. MC 1.21.1 also has no {@code inactivityFpsLimit} — that AFK
 * throttle only landed in 1.21.2 — so there is zero vanilla mitigation on this version.</p>
 *
 * <p>The simulation is already free while paused: {@code IntegratedServer.tickServer} skips
 * {@code super.tickServer()}, so {@code ServerLevel.tick} never fires and Sable's Rapier scene
 * never steps. The render loop is the entire cost, which is why capping it is the whole fix.</p>
 *
 * <p>DT's own per-frame work needs no separate gating — the {@code RenderLevelStageEvent}
 * handlers and HUD layers are driven by the frame rate and scale down with it automatically.</p>
 */
public final class FramerateThrottle {

    /** Default cap while idle. Low enough to matter thermally, high enough for menus to feel live. */
    public static final int DEFAULT_THROTTLE_FPS = 30;

    /** Config bounds for the cap. */
    public static final int MIN_THROTTLE_FPS = 5;
    public static final int MAX_THROTTLE_FPS = 120;

    private FramerateThrottle() {}

    /**
     * Is the throttle active right now? True while the game is paused, or whenever the window is
     * not focused (which covers alt-tabbed and minimised alike — GLFW reports both as inactive).
     *
     * <p>Callers pass raw booleans rather than a {@code Minecraft} handle so this class stays free
     * of client types and can be unit-tested on a bare JVM.</p>
     */
    public static boolean shouldThrottle(boolean paused, boolean windowActive, boolean enabled) {
        return enabled && (paused || !windowActive);
    }

    /**
     * Decide the framerate limit to apply.
     *
     * @param vanillaLimit the player's own limit, i.e. {@code window.getFramerateLimit()}
     * @return the throttled limit, or {@code vanillaLimit} unchanged when no throttle applies
     *
     * <p>The {@link Math#min} is load-bearing: a player who has set a 20 fps limit must not be
     * <em>raised</em> to 30 while paused. The throttle may only ever lower the rate.</p>
     */
    public static int decide(boolean paused, boolean windowActive, boolean enabled,
                             int throttleFps, int vanillaLimit) {
        if (!shouldThrottle(paused, windowActive, enabled)) return vanillaLimit;
        return Math.min(vanillaLimit, throttleFps);
    }
}
