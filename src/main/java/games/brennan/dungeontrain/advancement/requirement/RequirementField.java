package games.brennan.dungeontrain.advancement.requirement;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonArray;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * The numeric criterion fields that ARE an advancement's requirement — the "1,000" in "Traverse
 * 1,000 carriages". One entry per condition key the Dungeon Train triggers accept, with the unit
 * the number is in and how it renders as the description's translation argument.
 *
 * <p>This enum is the allowlist for everything requirement-shaped: the relay override is refused
 * for any other field, the datapack rewrite only looks at these keys, and the description argument
 * is formatted per entry. The relay's {@code advancement-requirements.js} and the explorer editor
 * carry the same four names; keep them in step.</p>
 */
public enum RequirementField {

    /** A plain count — carriages, biomes, chests, players, roof-run groups. */
    THRESHOLD("threshold", Unit.COUNT),
    /** Book reads. */
    THRESHOLD_READS("thresholdReads", Unit.COUNT),
    /** Distance in metres. */
    THRESHOLD_METERS("thresholdMeters", Unit.METRES),
    /** Time aboard, in game ticks (20/s; 72,000 = one hour). */
    THRESHOLD_TICKS("thresholdTicks", Unit.TICKS);

    /** What the raw number means, so the editor can label it and the description can phrase it. */
    public enum Unit { COUNT, METRES, TICKS }

    public static final long TICKS_PER_HOUR = 72_000L;
    private static final long HOURS_PER_DAY = 24L;

    /** Lang keys the tick argument renders through — plural-aware, one per whole-unit form. */
    public static final String HOURS_KEY = "dungeontrain.requirement.hours";
    public static final String DAYS_KEY = "dungeontrain.requirement.days";

    private static final NumberFormat GROUPED = NumberFormat.getIntegerInstance(Locale.US);

    private final String jsonKey;
    private final Unit unit;

    RequirementField(String jsonKey, Unit unit) {
        this.jsonKey = jsonKey;
        this.unit = unit;
    }

    /** The key as it appears under {@code criteria.<name>.conditions}. */
    public String jsonKey() {
        return jsonKey;
    }

    public Unit unit() {
        return unit;
    }

    /** The field whose JSON key is {@code key}, if it is one of ours. */
    public static Optional<RequirementField> byKey(String key) {
        if (key == null) return Optional.empty();
        for (RequirementField f : values()) {
            if (f.jsonKey.equals(key)) return Optional.of(f);
        }
        return Optional.empty();
    }

    /** The first requirement field present in a {@code conditions} object, if any. */
    public static Optional<RequirementField> in(JsonObject conditions) {
        if (conditions == null) return Optional.empty();
        for (RequirementField f : values()) {
            JsonElement v = conditions.get(f.jsonKey);
            if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) return Optional.of(f);
        }
        return Optional.empty();
    }

    /**
     * The description's translation argument for {@code value} — what {@code %s} becomes.
     *
     * <p>Counts and metres become a grouped integer string ({@code "1,000"}), a plain primitive
     * in the component's {@code with} array. Ticks become a nested translatable so "2 hours",
     * "1 hour" and "3 days" localise: {@link #HOURS_KEY}{@code .one/.other} with the hour count,
     * or {@link #DAYS_KEY} when the value is a whole number of days. Sub-hour tick values (none
     * shipped) still render, as a fractional hour.</p>
     */
    public JsonElement descriptionArgument(long value) {
        if (unit != Unit.TICKS) {
            return new JsonPrimitive(GROUPED.format(value));
        }
        double hours = value / (double) TICKS_PER_HOUR;
        boolean wholeHours = value % TICKS_PER_HOUR == 0;
        if (wholeHours && (value / TICKS_PER_HOUR) % HOURS_PER_DAY == 0 && value > 0) {
            long days = value / TICKS_PER_HOUR / HOURS_PER_DAY;
            return pluralTranslatable(DAYS_KEY, days, GROUPED.format(days));
        }
        String shown = wholeHours ? GROUPED.format(value / TICKS_PER_HOUR)
            : String.format(Locale.US, "%.1f", hours);
        long pluralCount = wholeHours ? value / TICKS_PER_HOUR : 2;
        return pluralTranslatable(HOURS_KEY, pluralCount, shown);
    }

    /** {@code {translate: key.one|key.other, with:[shown]}} — the mod's plural-key convention. */
    private static JsonElement pluralTranslatable(String key, long count, String shown) {
        JsonObject o = new JsonObject();
        o.addProperty("translate", key + (count == 1 ? ".one" : ".other"));
        JsonArray with = new JsonArray();
        with.add(shown);
        o.add("with", with);
        return o;
    }
}
