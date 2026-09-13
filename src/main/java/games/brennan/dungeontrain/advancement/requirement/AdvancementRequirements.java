package games.brennan.dungeontrain.advancement.requirement;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What every requirement advancement currently asks for, as loaded by the last datapack apply.
 *
 * <p>Populated by {@link RequirementJsonRewriter} from the raw advancement JSON (after the relay
 * override has been applied) and read by the code-granted advancements —
 * {@code PacifistAdvancement}, {@code FarStartAdvancement}, {@code AchievementEvents} — in place
 * of the constants they used to carry. That is what makes the JSON the single source of truth:
 * the trigger-driven advancements read their threshold from their criterion instance, and the
 * code-driven ones read it from here, and both came from the same rewritten JSON.</p>
 *
 * <p>Static, because there is one {@code ServerAdvancementManager} per server and the datapack
 * apply that fills this runs before any player can be on it. Replaced wholesale on every apply
 * (a {@code /reload} included) and cleared when the server stops.</p>
 */
public final class AdvancementRequirements {

    /**
     * One advancement's requirement.
     *
     * @param criterion the criterion name the field sits under ({@code "milestone"}, ...)
     * @param field     which numeric field it is
     * @param shipped   the number the datapack carries
     * @param effective the number in force — the relay override when there is one, else shipped
     */
    public record Requirement(String criterion, RequirementField field, long shipped, long effective) {
        public boolean overridden() {
            return shipped != effective;
        }
    }

    private static volatile Map<ResourceLocation, Requirement> current = Collections.emptyMap();

    private AdvancementRequirements() {}

    /** The effective value for {@code id}, or {@code fallback} when the datapack has no requirement for it. */
    public static long value(ResourceLocation id, long fallback) {
        Requirement r = current.get(id);
        return r == null ? fallback : r.effective();
    }

    /** {@link #value(ResourceLocation, long)} narrowed to an int, saturating rather than wrapping. */
    public static int intValue(ResourceLocation id, int fallback) {
        long v = value(id, fallback);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(Integer.MIN_VALUE, v));
    }

    public static Optional<Requirement> get(ResourceLocation id) {
        return Optional.ofNullable(current.get(id));
    }

    /** An immutable copy of everything loaded, in datapack order. */
    public static Map<ResourceLocation, Requirement> snapshot() {
        return current;
    }

    /** Replace the loaded set — called once per datapack apply with the full map. */
    static void replace(Map<ResourceLocation, Requirement> loaded) {
        current = Collections.unmodifiableMap(new LinkedHashMap<>(loaded));
    }

    /** Forget everything — the server this described is gone. */
    public static void clear() {
        current = Collections.emptyMap();
    }
}
