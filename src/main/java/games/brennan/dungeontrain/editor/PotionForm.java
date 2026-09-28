package games.brennan.dungeontrain.editor;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * Bottle form a random-potion placeholder entry spawns as. {@link #ANY} re-rolls the form per
 * spawn (drinkable / splash / lingering); the other three pin it. Persisted by {@link #id()},
 * so the enum order may change but the ids must not.
 */
public enum PotionForm {
    ANY("any", "Any", null),
    POTION("potion", "Potion", Items.POTION),
    SPLASH("splash", "Splash", Items.SPLASH_POTION),
    LINGERING("lingering", "Lingering", Items.LINGERING_POTION);

    private final String id;
    private final String label;
    private final @Nullable Item item;

    PotionForm(String id, String label, @Nullable Item item) {
        this.id = id;
        this.label = label;
        this.item = item;
    }

    /** Stable disk / wire id. */
    public String id() {
        return id;
    }

    /** Short menu label. */
    public String label() {
        return label;
    }

    /** The pinned bottle item, or {@code null} for {@link #ANY}. */
    public @Nullable Item item() {
        return item;
    }

    /** Next form in the click cycle: {@code ANY → POTION → SPLASH → LINGERING → ANY}. */
    public PotionForm next() {
        PotionForm[] all = values();
        return all[(ordinal() + 1) % all.length];
    }

    /** Parse a stored id; unknown or missing ids fall back to {@link #ANY}. */
    public static PotionForm parse(@Nullable String id) {
        if (id == null) return ANY;
        for (PotionForm f : values()) {
            if (f.id.equals(id)) return f;
        }
        return ANY;
    }

    /** Ordinal-addressed lookup for the wire; out-of-range falls back to {@link #ANY}. */
    public static PotionForm byOrdinal(int ordinal) {
        PotionForm[] all = values();
        return ordinal >= 0 && ordinal < all.length ? all[ordinal] : ANY;
    }
}
