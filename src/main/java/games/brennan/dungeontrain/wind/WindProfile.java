package games.brennan.dungeontrain.wind;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The tunable shape of the train wind: a list of {@link Stage} keyframes keyed by <b>blocks past the end of
 * the first Upside-down band</b>, linearly interpolated, plus a few profile-wide knobs.
 *
 * <p>Loaded from {@code data/dungeontrain/wind/profile.json} — the file {@code scripts/wind/} (the tuner)
 * reads, graphs and saves. Pure Java (Gson only) so {@link WindField} and its tests run without Minecraft,
 * and so the same JSON drives the server, the client and the tuner's JavaScript port byte-for-byte.</p>
 *
 * <p>Before the first stage's {@code at} the first stage holds; past the last stage the last one holds, so
 * the wind keeps its final character through every later cycle.</p>
 *
 * @param enabled          master switch (the {@code /dungeontrain debug wind off} toggle is separate)
 * @param headingDeg       mean direction the wind blows TOWARD, degrees in the XZ plane: 0 = +X, 90 = +Z
 * @param sneakFactor      push multiplier while the local player is sneaking
 * @param airFactor        push multiplier for a living thing off the ground (jumping, falling)
 * @param visualLeadTicks  how far ahead of the push the client draws the wind (20 = 1 second)
 * @param multipliers      push multiplier per entity category
 * @param stages           keyframes, sorted by {@link Stage#at}
 */
public record WindProfile(boolean enabled, double headingDeg, double sneakFactor, double airFactor,
                          int visualLeadTicks,
                          Multipliers multipliers, List<Stage> stages) {

    /** Bundled resource path (classpath) of the default profile. */
    public static final String RESOURCE_PATH = "/data/dungeontrain/wind/profile.json";

    /** A profile that never blows — used when the file is missing or malformed. */
    public static final WindProfile CALM = new WindProfile(false, 90.0, 1.0, 1.0, 20, Multipliers.UNIT, List.of());

    public WindProfile {
        stages = stages.stream().sorted(Comparator.comparingDouble(Stage::at)).toList();
    }

    /**
     * One keyframe. All strengths are horizontal accelerations in blocks/tick² at multiplier 1.
     *
     * @param at            blocks past the Upside-down end where this keyframe sits
     * @param base          steady push
     * @param flutter       slow ±fraction wobble of the steady push (0 = perfectly constant)
     * @param gustsPerMin   expected gusts per minute
     * @param gustStrength  peak extra push of a gust (each gust rolls 60-100% of it)
     * @param gustTicks     how long one gust lasts, ticks (fast rise, slower fall)
     * @param gustVeer      ± degrees a gust swings the heading at its peak
     * @param wander        ± degrees the heading drifts around {@link WindProfile#headingDeg}
     * @param wanderSeconds roughly how long one drift swing takes
     */
    public record Stage(double at, double base, double flutter, double gustsPerMin, double gustStrength,
                        double gustTicks, double gustVeer, double wander, double wanderSeconds) {

        static Stage lerp(Stage a, Stage b, double f) {
            return new Stage(
                    mix(a.at, b.at, f), mix(a.base, b.base, f), mix(a.flutter, b.flutter, f),
                    mix(a.gustsPerMin, b.gustsPerMin, f), mix(a.gustStrength, b.gustStrength, f),
                    mix(a.gustTicks, b.gustTicks, f), mix(a.gustVeer, b.gustVeer, f),
                    mix(a.wander, b.wander, f), mix(a.wanderSeconds, b.wanderSeconds, f));
        }

        private static double mix(double a, double b, double f) {
            return a + (b - a) * f;
        }
    }

    /** Push multiplier per entity category. */
    public record Multipliers(double player, double mob, double item, double projectile, double xp, double other) {
        public static final Multipliers UNIT = new Multipliers(1, 1, 1, 1, 1, 1);
    }

    /** The interpolated keyframe at {@code d} blocks past the Upside-down end; {@code null} with no stages. */
    public Stage stageAt(double d) {
        if (stages.isEmpty()) return null;
        Stage first = stages.get(0);
        if (d <= first.at()) return first;
        for (int i = 0; i < stages.size() - 1; i++) {
            Stage a = stages.get(i);
            Stage b = stages.get(i + 1);
            if (d < b.at()) {
                double span = b.at() - a.at();
                return span <= 0 ? b : Stage.lerp(a, b, (d - a.at()) / span);
            }
        }
        return stages.get(stages.size() - 1);
    }

    // ---- JSON ---------------------------------------------------------------------------------

    /** Parse a profile; throws {@link IllegalArgumentException} with a readable message on bad input. */
    public static WindProfile parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("wind profile is not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) throw new IllegalArgumentException("wind profile must be a JSON object");
        JsonObject o = root.getAsJsonObject();

        JsonObject m = o.has("multipliers") && o.get("multipliers").isJsonObject()
                ? o.getAsJsonObject("multipliers") : new JsonObject();
        Multipliers mult = new Multipliers(
                num(m, "player", 1), num(m, "mob", 1), num(m, "item", 1),
                num(m, "projectile", 1), num(m, "xp", 1), num(m, "other", 1));

        List<Stage> stages = new ArrayList<>();
        if (o.has("stages") && o.get("stages").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("stages");
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) throw new IllegalArgumentException("wind stage must be an object: " + e);
                JsonObject s = e.getAsJsonObject();
                stages.add(new Stage(
                        num(s, "at", 0), nonNeg(s, "base"), nonNeg(s, "flutter"), nonNeg(s, "gustsPerMin"),
                        nonNeg(s, "gustStrength"), Math.max(1, num(s, "gustTicks", 30)), nonNeg(s, "gustVeer"),
                        nonNeg(s, "wander"), Math.max(1, num(s, "wanderSeconds", 60))));
            }
        }
        return new WindProfile(
                !o.has("enabled") || o.get("enabled").getAsBoolean(),
                num(o, "headingDeg", 90),
                num(o, "sneakFactor", 0.35),
                Math.max(0, num(o, "airFactor", 1.0)),
                (int) Math.max(0, num(o, "visualLeadTicks", 20)),
                mult, stages);
    }

    /** Serialise back to the same shape {@link #parse} reads (used by the sync packet). */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("headingDeg", headingDeg);
        o.addProperty("sneakFactor", sneakFactor);
        o.addProperty("airFactor", airFactor);
        o.addProperty("visualLeadTicks", visualLeadTicks);
        JsonObject m = new JsonObject();
        m.addProperty("player", multipliers.player());
        m.addProperty("mob", multipliers.mob());
        m.addProperty("item", multipliers.item());
        m.addProperty("projectile", multipliers.projectile());
        m.addProperty("xp", multipliers.xp());
        m.addProperty("other", multipliers.other());
        o.add("multipliers", m);
        JsonArray arr = new JsonArray();
        for (Stage s : stages) {
            JsonObject j = new JsonObject();
            j.addProperty("at", s.at());
            j.addProperty("base", s.base());
            j.addProperty("flutter", s.flutter());
            j.addProperty("gustsPerMin", s.gustsPerMin());
            j.addProperty("gustStrength", s.gustStrength());
            j.addProperty("gustTicks", s.gustTicks());
            j.addProperty("gustVeer", s.gustVeer());
            j.addProperty("wander", s.wander());
            j.addProperty("wanderSeconds", s.wanderSeconds());
            arr.add(j);
        }
        o.add("stages", arr);
        return o.toString();
    }

    private static double num(JsonObject o, String key, double fallback) {
        if (!o.has(key) || o.get(key).isJsonNull()) return fallback;
        try {
            double v = o.get(key).getAsDouble();
            if (!Double.isFinite(v)) throw new IllegalArgumentException("wind profile '" + key + "' is not finite");
            return v;
        } catch (ClassCastException | IllegalStateException | NumberFormatException e) {
            throw new IllegalArgumentException("wind profile '" + key + "' must be a number", e);
        }
    }

    private static double nonNeg(JsonObject o, String key) {
        return Math.max(0, num(o, key, 0));
    }
}
