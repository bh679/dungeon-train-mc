package games.brennan.dungeontrain.client.shader;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.TrainDebugState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.Locale;

/**
 * Turns Dungeon Train's shader-path features off one at a time, live, on <b>F3 + 6</b>.
 *
 * <h2>Why this exists</h2>
 * <p>The faults worth chasing under a shader pack are <em>temporal</em> — carriage lighting
 * strobing on a moving train — and a temporal fault is invisible to the screenshot sweep and
 * cannot be read off a log. That leaves watching the screen, which means one build and one world
 * load per hypothesis. Two of those round trips bought two wrong answers, so the features became
 * switchable instead: one session, cycle the chord, and the guilty pass names itself.</p>
 *
 * <p>Only Dungeon Train's <em>own</em> shader-path additions are switchable here. That is
 * deliberate: {@link Mode#ALL_NEW_OFF} leaves the mod behaving as it did before any of this work,
 * so if a symptom survives that mode it is not a regression from it, which is as useful an answer
 * as naming a pass.</p>
 *
 * <p>Dev-only, and gated on the same access grant as the F3+4 and F3+5 panels. A player without
 * the grant cycles nothing, and the chord is consumed either way so its existence is not
 * advertised. Every predicate here is a plain volatile read on the render thread.</p>
 */
public final class ShaderBisect {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What is switched off. Cycled in order by the chord. */
    public enum Mode {
        ALL_ON("everything on"),
        NO_SKY_COLOUR("band sky colour OFF"),
        NO_SKYBOX_REOPEN("skybox hole reopen OFF"),
        NO_SPOOF("shader world spoof OFF"),
        NO_POST_FOG("post-composite fog OFF"),
        NO_BAND_LIGHTMAP("band lightmap pin/lift OFF"),
        ALL_NEW_OFF("all shader work OFF (pre-branch behaviour)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Shortest gap between two accepted steps.
     *
     * <p>Not politeness — correctness. {@code handleDebugKeys} fires on key <em>repeat</em> as well
     * as on the first press, so holding the chord for a moment tore through the whole cycle at the
     * OS repeat rate and left the tester unable to say which mode they were looking at. Measured at
     * 260-290 ms between repeats, so a 400 ms floor absorbs the repeat while still allowing
     * deliberate presses as fast as anyone can watch a result.</p>
     */
    private static final long MIN_STEP_MS = 400L;

    private static volatile Mode mode = Mode.ALL_ON;
    private static long lastStepAt = 0L;

    private ShaderBisect() {}

    /**
     * Advance to the next mode and announce it, on screen and in the log. No-op without a live
     * debug grant, and no-op for a key repeat inside {@link #MIN_STEP_MS}.
     */
    public static void cycle() {
        if (!TrainDebugState.permitted()) return;
        long now = System.currentTimeMillis();
        if (now - lastStepAt < MIN_STEP_MS) return;
        lastStepAt = now;

        Mode[] all = Mode.values();
        mode = all[(mode.ordinal() + 1) % all.length];
        LOGGER.info("[DungeonTrain] Shader bisect: {}", mode.label());
        announce();
    }

    /**
     * Put the mode above the hotbar. The F3+5 panel carries it too, but reading it there means
     * having the panel open, and the whole point of the chord is to be usable while watching the
     * train rather than the read-out.
     */
    private static void announce() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) return;
        int step = mode.ordinal();
        int last = Mode.values().length - 1;
        mc.gui.setOverlayMessage(
            Component.literal("Shader bisect " + step + "/" + last + ": " + mode.label()), false);
    }

    public static Mode mode() {
        return mode;
    }

    /** The panel line, or {@code ""} while nothing is switched off. */
    public static String describe() {
        return mode == Mode.ALL_ON ? "" : mode.label();
    }

    // --- The predicates the render path asks -------------------------------------------------------

    /** Whether {@code ShaderWorld} may tell Iris to render a different world. */
    public static boolean spoofEnabled() {
        return allow(Mode.NO_SPOOF);
    }

    /** Whether the band may tint {@code ClientLevel.getSkyColor}. */
    public static boolean skyColourEnabled() {
        return allow(Mode.NO_SKY_COLOUR);
    }

    /** Whether the skybox holes are reopened to the far plane for the pack's composite. */
    public static boolean skyboxReopenEnabled() {
        return allow(Mode.NO_SKYBOX_REOPEN);
    }

    /** Whether the dimensional-carriage fog is drawn after the pack's final pass. */
    public static boolean postFogEnabled() {
        return allow(Mode.NO_POST_FOG);
    }

    /**
     * Whether a band may pin the daylight and lift the lightmap floor.
     *
     * <p>The one switchable thing here that predates this branch, and it earns the exception: the
     * lift raises the lightmap's <em>floor</em>, which brightens enclosed space, and under a pack's
     * per-dimension programs that is indistinguishable by eye from light leaking through walls.
     * Being able to switch it off separates "Dungeon Train lifted it" from "the pack lit a world
     * whose light data says overworld", which are different faults with different fixes.</p>
     */
    public static boolean bandLightmapEnabled() {
        return allow(Mode.NO_BAND_LIGHTMAP);
    }

    private static boolean allow(Mode disabledBy) {
        Mode m = mode;
        if (m == disabledBy) return false;
        // ALL_NEW_OFF means "as the mod behaved before this branch", so it must not switch off the
        // band lightmap, which shipped long before any of this.
        return m != Mode.ALL_NEW_OFF || disabledBy == Mode.NO_BAND_LIGHTMAP;
    }

    /** For the sweep log, so a run carries which mode produced it. */
    public static String token() {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
