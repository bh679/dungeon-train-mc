package games.brennan.dungeontrain.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.cheat.RunIntegrity;
import games.brennan.dungeontrain.event.CheatDetectionEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

/**
 * Puts a run into <b>Free Play</b> when the player <em>uses</em> Effortless Building — behind the
 * same confirmation that gates {@code /gamemode} and {@code /give} (see {@link CheatDetectionEvents}).
 *
 * <p><b>Use, not presence.</b> Unlike {@link games.brennan.dungeontrain.cheat.CheatModIntegrity},
 * merely having the mod installed changes nothing: Effortless Building is a bundled, opt-in modpack
 * companion and ordinary single-block placement with no modifier is honest play. What taints is
 * reaching for the creative features — a multi-block build/break, an undo/redo of one, or switching a
 * mirror / array / radial modifier on.</p>
 *
 * <p><b>Why the actions are cancelled rather than held.</b> The command path re-dispatches the held
 * command after the player confirms; there is no equivalent here, so the gated packet is dropped and
 * the player simply repeats the action once Free Play is on. Cancelling is also what keeps a
 * <em>declined</em> prompt honest: the server never registers the modifier or runs the build, so the
 * feature genuinely did not apply.</p>
 *
 * <p>Called from {@code mixin.effortlessbuilding.EffortlessBuildingPacketHandlerMixin}, which hooks
 * Effortless Building's server-side packet handlers. Everything that touches the mod's own types goes
 * through reflection ({@link #enablesModifier}) because it is a runtime-optional mod, not a compile
 * dependency.</p>
 */
public final class EffortlessBuildingGate {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Shown on the confirm screen and in the Free Play chat notice ("You used %s."). */
    public static final String LABEL = "Effortless Building";

    /** Modifier JSON key carrying each modifier's on/off state (Effortless Building 4.2). */
    private static final String ENABLED_KEY = "enabled";

    private EffortlessBuildingGate() {}

    /**
     * Gate one Effortless Building action.
     *
     * @return true when the caller must cancel the action — either a confirmation is now open, or
     *         the player is offline/invalid. False when the action may proceed (the run is already
     *         Free Play, so there is nothing left to confirm).
     */
    public static boolean gate(ServerPlayer player) {
        if (player == null) return false;
        if (RunIntegrity.isCheated(player)) return false; // already Free Play — let it run
        return CheatDetectionEvents.requestFreePlayConfirm(player, LABEL);
    }

    /**
     * Does this {@code UpdateModifiersC2SPacket} switch a modifier <b>on</b>?
     *
     * <p>The packet carries the modifier list as a JSON string; each entry has an {@code "enabled"}
     * boolean. A packet that only turns modifiers off (or carries none) is not creative-feature use,
     * so it must not prompt — otherwise disabling a mirror would trip Free Play.</p>
     *
     * <p>Reads the record's {@code json()} accessor reflectively, since Effortless Building is not on
     * DT's compile classpath. Any failure — a renamed accessor in a future build, malformed JSON —
     * <b>fails open</b> (returns false, no prompt): the build-action hooks still catch actual builds,
     * and a false Free Play trip would be far worse than a missed one.</p>
     */
    public static boolean enablesModifier(Object packet) {
        try {
            Object json = packet.getClass().getMethod("json").invoke(packet);
            if (!(json instanceof String raw) || raw.isBlank()) return false;
            return anyEnabled(JsonParser.parseString(raw));
        } catch (Throwable t) {
            LOGGER.debug("[DungeonTrain] Could not read Effortless Building modifiers — not gating: {}",
                t.toString());
            return false;
        }
    }

    /**
     * True when any object anywhere in the tree has {@code "enabled": true}. Walks the whole
     * structure rather than assuming a shape, so nested modifiers (radial mirror slices) and a
     * future top-level rename are both covered. Package-visible for unit tests.
     */
    static boolean anyEnabled(JsonElement element) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (anyEnabled(child)) return true;
            }
            return false;
        }
        if (!element.isJsonObject()) return false;
        for (var entry : element.getAsJsonObject().entrySet()) {
            if (ENABLED_KEY.equals(entry.getKey())
                && entry.getValue().isJsonPrimitive()
                && entry.getValue().getAsJsonPrimitive().isBoolean()
                && entry.getValue().getAsBoolean()) {
                return true;
            }
            if (anyEnabled(entry.getValue())) return true;
        }
        return false;
    }
}
