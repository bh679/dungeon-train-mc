package games.brennan.dungeontrain.advancement.requirement;

import java.util.Optional;

/**
 * The relay's per-advancement on/off switches — the boolean twin of {@link RequirementField}.
 * The jar's baseline is "enabled, required"; a raised flag is the operator's exception to it.
 *
 * <p>The relay's {@code advancement-flags.js} and the explorer's checkbox columns carry the same
 * two names; keep them in step.</p>
 */
public enum AdvancementFlag {

    /**
     * Dropped from the datapack as it loads: not earnable, not shown, its children re-parented to
     * its parent. Applied by {@link AdvancementDisabler} in the datapack apply.
     */
    DISABLED("disabled"),
    /**
     * Stays in the game, but the capstone ({@code dungeon_train/completionist}) no longer requires
     * it. Read live by {@code CompletionistAdvancement}.
     */
    NOT_REQUIRED("notRequired");

    private final String jsonKey;

    AdvancementFlag(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** The key under an advancement's entry in the relay payload's {@code flags} object. */
    public String jsonKey() {
        return jsonKey;
    }

    public static Optional<AdvancementFlag> byKey(String key) {
        for (AdvancementFlag f : values()) {
            if (f.jsonKey.equals(key)) return Optional.of(f);
        }
        return Optional.empty();
    }
}
