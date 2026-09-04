package games.brennan.dungeontrain.client.shader;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.client.TrainDebugState;
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
        ALL_NEW_OFF("all shader work OFF (pre-branch behaviour)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private static volatile Mode mode = Mode.ALL_ON;

    private ShaderBisect() {}

    /** Advance to the next mode and announce it. No-op without a live debug grant. */
    public static void cycle() {
        if (!TrainDebugState.permitted()) return;
        Mode[] all = Mode.values();
        mode = all[(mode.ordinal() + 1) % all.length];
        LOGGER.info("[DungeonTrain] Shader bisect: {}", mode.label());
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

    private static boolean allow(Mode disabledBy) {
        Mode m = mode;
        return m != disabledBy && m != Mode.ALL_NEW_OFF;
    }

    /** For the sweep log, so a run carries which mode produced it. */
    public static String token() {
        return mode.name().toLowerCase(Locale.ROOT);
    }
}
