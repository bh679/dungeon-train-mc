package games.brennan.dungeontrain.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Client half of "Effortless Building only works in Creative" — see
 * {@link games.brennan.dungeontrain.compat.EffortlessBuildingGate#blockedOutsideCreative} for the
 * server half, which is the one that holds.
 *
 * <p>Outside Creative the mod's client tick is skipped, so its Alt radial menu, modifier screen and
 * undo / redo keys do nothing; its previews are not drawn; and its click interception stands down,
 * so right and left clicks are plain vanilla. Called from
 * {@code mixin.effortlessbuilding.EffortlessBuildingClientTickMixin} and
 * {@code EffortlessBuildingClientInterceptMixin}.</p>
 *
 * <p>Effortless Building is not a compile dependency, so its key mappings and
 * {@code BuildPipelineClient.cancelCurrentSequence} are reached reflectively. Any failure there only
 * logs once: the tick, preview and click hooks still hold without it.</p>
 */
public final class EffortlessBuildingClientGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String EB_PACKAGE = "neoforge.nl.requios.effortlessbuilding.";
    private static final String KEY_BINDINGS_CLASS = EB_PACKAGE + "utilities.KeyBindings";
    private static final String PIPELINE_CLIENT_CLASS = EB_PACKAGE + "buildpipeline.BuildPipelineClient";
    private static final String[] KEY_FIELDS = {"openRadialMenu", "openModifiersScreen", "undo", "redo"};

    /** Whether the last tick was blocked, so a half-drawn shape is cancelled once on the switch. */
    private static boolean wasBlocked;
    private static List<KeyMapping> keys;
    private static boolean reflectionFailed;

    private EffortlessBuildingClientGate() {}

    /** True when the local player is in a world and not in Creative. */
    public static boolean isBlocked() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.gameMode != null && mc.gameMode.getPlayerMode() != GameType.CREATIVE;
    }

    /**
     * Run in place of the mod's client tick while blocked: throws away queued presses of its keys
     * (so they don't all fire on the next switch to Creative), closes its screens if one was open
     * when the mode changed, and drops any shape already being drawn.
     */
    public static void onBlockedTick() {
        Minecraft mc = Minecraft.getInstance();
        for (KeyMapping key : keys()) {
            while (key.consumeClick()) {
                // discard
            }
        }
        if (mc.screen != null && mc.screen.getClass().getName().startsWith(EB_PACKAGE)) {
            mc.setScreen(null);
        }
        if (!wasBlocked) {
            wasBlocked = true;
            cancelCurrentSequence();
        }
    }

    /** Called from the mod's client tick when it is allowed to run. */
    public static void onAllowedTick() {
        wasBlocked = false;
    }

    private static List<KeyMapping> keys() {
        if (keys != null) return keys;
        List<KeyMapping> found = new ArrayList<>();
        try {
            Class<?> bindings = Class.forName(KEY_BINDINGS_CLASS);
            for (String name : KEY_FIELDS) {
                Field field = bindings.getField(name);
                if (field.get(null) instanceof KeyMapping key) found.add(key);
            }
        } catch (Throwable t) {
            logFailure("key mappings", t);
        }
        // Not cached while still empty: the mod registers its mappings after this class may first run.
        if (!found.isEmpty()) keys = List.copyOf(found);
        return found;
    }

    private static void cancelCurrentSequence() {
        try {
            Method cancel = Class.forName(PIPELINE_CLIENT_CLASS).getMethod("cancelCurrentSequence");
            cancel.invoke(null);
        } catch (Throwable t) {
            logFailure("cancelCurrentSequence", t);
        }
    }

    private static void logFailure(String what, Throwable t) {
        if (reflectionFailed) return;
        reflectionFailed = true;
        LOGGER.warn("[DungeonTrain] Could not reach Effortless Building {} — the Creative-only block "
            + "still holds through the tick and click hooks: {}", what, t.toString());
    }
}
