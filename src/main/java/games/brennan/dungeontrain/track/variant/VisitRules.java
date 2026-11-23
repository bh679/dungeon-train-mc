package games.brennan.dungeontrain.track.variant;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * How a group sub-variant behaves across one player's visits — the "ordered sub-variants" settings
 * of a dimensional carriage group. See {@link OrderedGroupRoll} for how a roll reads them.
 *
 * <ul>
 *   <li>{@code order} — 0 draws any time (once every step is done); 1..{@link #MAX_ORDER} is a step.
 *       While a step has not been visited, only that step's members draw.</li>
 *   <li>{@code seenAgain} — false drops the member for a player who has visited it once.</li>
 *   <li>{@code afterVisits} / {@code laterWeight} — once the player has visited it
 *       {@code afterVisits} times (0 = never), the member draws at {@code laterWeight}.</li>
 * </ul>
 *
 * <p>Every field is omitted from JSON at its default, so a group without these settings round-trips
 * as the file it was. No Minecraft types, so it unit-tests without a bootstrap.</p>
 */
public record VisitRules(int order, boolean seenAgain, int afterVisits, int laterWeight) {

    public static final int MAX_ORDER = 9;
    public static final int MAX_AFTER_VISITS = 99;

    public static final String ORDER_KEY = "order";
    public static final String SEEN_AGAIN_KEY = "seenAgain";
    public static final String AFTER_VISITS_KEY = "afterVisits";
    public static final String LATER_WEIGHT_KEY = "laterWeight";

    /** No rules: drawn any time, seen again, weight never changes. */
    public static final VisitRules NONE = new VisitRules(0, true, 0, 0);

    public VisitRules {
        order = clamp(order, 0, MAX_ORDER);
        afterVisits = clamp(afterVisits, 0, MAX_AFTER_VISITS);
        laterWeight = TrackVariantGroup.clampWeight(laterWeight);
    }

    public boolean isNone() {
        return equals(NONE);
    }

    public boolean isOrdered() {
        return order > 0;
    }

    /** The weight this member draws at for a player who has visited it {@code visits} times. */
    public int weightAfter(int baseWeight, int visits) {
        return afterVisits > 0 && visits >= afterVisits ? laterWeight : baseWeight;
    }

    public VisitRules withOrder(int v) { return new VisitRules(v, seenAgain, afterVisits, laterWeight); }
    public VisitRules withSeenAgain(boolean v) { return new VisitRules(order, v, afterVisits, laterWeight); }
    public VisitRules withAfterVisits(int v) { return new VisitRules(order, seenAgain, v, laterWeight); }
    public VisitRules withLaterWeight(int v) { return new VisitRules(order, seenAgain, afterVisits, v); }

    /** Writes only the non-default fields onto a member entry. */
    public void writeTo(JsonObject entry) {
        if (order != 0) entry.addProperty(ORDER_KEY, order);
        if (!seenAgain) entry.addProperty(SEEN_AGAIN_KEY, false);
        if (afterVisits != 0) {
            entry.addProperty(AFTER_VISITS_KEY, afterVisits);
            entry.addProperty(LATER_WEIGHT_KEY, laterWeight);
        }
    }

    /** Tolerant reader: a missing or malformed field reads as its default. */
    public static VisitRules fromJson(JsonObject entry) {
        if (entry == null) return NONE;
        return new VisitRules(
            readInt(entry, ORDER_KEY, 0),
            readBool(entry, SEEN_AGAIN_KEY, true),
            readInt(entry, AFTER_VISITS_KEY, 0),
            readInt(entry, LATER_WEIGHT_KEY, 0));
    }

    private static int readInt(JsonObject o, String key, int fallback) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) return fallback;
        try {
            return el.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean readBool(JsonObject o, String key, boolean fallback) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) return fallback;
        return el.getAsBoolean();
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
