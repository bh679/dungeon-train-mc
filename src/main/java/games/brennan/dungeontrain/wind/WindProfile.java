package games.brennan.dungeontrain.wind;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The tunable shape of the train wind: one {@link WindCurve} per quantity, each a function of <b>blocks past the
 * end of the first Upside-down band</b>, plus a few profile-wide knobs.
 *
 * <ul>
 *   <li><b>Strength</b> ({@code base}, {@code gustStrength}) is {@code log}: it keeps rising for as long as the
 *       train runs, ever more slowly, with no upper limit.</li>
 *   <li><b>Gust frequency and length</b> ({@code gustsPerMin}, {@code gustSeconds}) are {@code approach}: each
 *       closes in on its cap forever without reaching it. {@code gustSeconds} is the <em>longest</em> a gust
 *       can be there; each gust rolls its own length between {@link WindField#MIN_GUST_SECONDS} and it.</li>
 *   <li>The rest (flutter, gust veer, heading wander and its period) are {@code approach} too.</li>
 * </ul>
 *
 * <p>Loaded from {@code data/dungeontrain/wind/profile.json} — the file {@code scripts/wind/} (the tuner)
 * reads, graphs and saves. Pure Java (Gson only) so {@link WindField} and its tests run without Minecraft,
 * and so the same JSON drives the server, the client and the tuner's JavaScript port.</p>
 *
 * @param enabled          master switch (the {@code /dungeontrain debug wind off} toggle is separate)
 * @param headingDeg       mean direction the wind blows TOWARD, degrees in the XZ plane: 0 = +X, 90 = +Z
 * @param sneakFactor      push multiplier while the local player is sneaking
 * @param airFactor        push multiplier for a living thing off the ground (jumping, falling)
 * @param visualLeadTicks  how far ahead of the push the client draws the wind (20 = 1 second)
 * @param multipliers      push multiplier per entity category
 * @param curves           how each quantity grows with distance; {@code null} = no wind
 */
public record WindProfile(boolean enabled, double headingDeg, double sneakFactor, double airFactor,
                          int visualLeadTicks, Multipliers multipliers, Curves curves) {

    /** Bundled resource path (classpath) of the default profile. */
    public static final String RESOURCE_PATH = "/data/dungeontrain/wind/profile.json";

    /** Hard ceiling on gust frequency: one gust slot per second, every slot firing. */
    public static final double MAX_GUSTS_PER_MIN = 60.0;

    /** A profile that never blows — used when the file is missing or malformed. */
    public static final WindProfile CALM = new WindProfile(false, 90.0, 1.0, 1.0, 20, Multipliers.UNIT, null);

    /**
     * The curves, one per quantity. Strengths are horizontal accelerations in blocks/tick² at multiplier 1.
     *
     * @param base          steady push
     * @param flutter       slow ±fraction wobble of the steady push (0 = perfectly constant)
     * @param gustsPerMin   expected gusts per minute (capped at {@link #MAX_GUSTS_PER_MIN})
     * @param gustStrength  peak extra push of a gust (each gust rolls 60-100% of it)
     * @param gustSeconds   the longest a gust can last; each one is 0.1 s up to this
     * @param gustVeer      ± degrees a gust swings the heading at its peak
     * @param wander        ± degrees the heading drifts around {@link WindProfile#headingDeg}
     * @param wanderSeconds roughly how long one drift swing takes
     */
    public record Curves(WindCurve base, WindCurve flutter, WindCurve gustsPerMin, WindCurve gustStrength,
                         WindCurve gustSeconds, WindCurve gustVeer, WindCurve wander, WindCurve wanderSeconds) {

        static final String[] NAMES = {"base", "flutter", "gustsPerMin", "gustStrength", "gustSeconds",
                "gustVeer", "wander", "wanderSeconds"};

        WindCurve[] all() {
            return new WindCurve[] {base, flutter, gustsPerMin, gustStrength, gustSeconds, gustVeer, wander,
                    wanderSeconds};
        }
    }

    /**
     * Every quantity evaluated at one distance — what {@link WindField} and the F3+8 panel read.
     *
     * @param at        blocks past the Upside-down end this was evaluated at
     * @param gustTicks the longest a gust can last here, in ticks (each gust rolls 2 ticks up to this)
     */
    public record Stage(double at, double base, double flutter, double gustsPerMin, double gustStrength,
                        double gustTicks, double gustVeer, double wander, double wanderSeconds) {}

    /** Push multiplier per entity category. */
    public record Multipliers(double player, double mob, double item, double projectile, double xp, double other) {
        public static final Multipliers UNIT = new Multipliers(1, 1, 1, 1, 1, 1);
    }

    /** Every quantity at {@code d} blocks past the Upside-down end; {@code null} with no curves. */
    public Stage stageAt(double d) {
        if (curves == null) return null;
        Curves c = curves;
        return new Stage(d,
                Math.max(0.0, c.base().at(d)),
                Math.max(0.0, c.flutter().at(d)),
                Math.min(MAX_GUSTS_PER_MIN, Math.max(0.0, c.gustsPerMin().at(d))),
                Math.max(0.0, c.gustStrength().at(d)),
                Math.max(WindField.MIN_GUST_SECONDS, c.gustSeconds().at(d)) * 20.0,
                Math.max(0.0, c.gustVeer().at(d)),
                Math.max(0.0, c.wander().at(d)),
                Math.max(1.0, c.wanderSeconds().at(d)));
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

        if (!o.has("curves") || !o.get("curves").isJsonObject()) {
            throw new IllegalArgumentException("wind profile needs a 'curves' object");
        }
        JsonObject co = o.getAsJsonObject("curves");
        WindCurve[] parsed = new WindCurve[Curves.NAMES.length];
        for (int i = 0; i < parsed.length; i++) {
            String name = Curves.NAMES[i];
            if (!co.has(name) || !co.get(name).isJsonObject()) {
                throw new IllegalArgumentException("wind profile curves is missing '" + name + "'");
            }
            parsed[i] = WindCurve.parse(co.getAsJsonObject(name), name);
        }
        Curves curves = new Curves(parsed[0], parsed[1], parsed[2], parsed[3], parsed[4], parsed[5], parsed[6],
                parsed[7]);
        return new WindProfile(
                !o.has("enabled") || o.get("enabled").getAsBoolean(),
                num(o, "headingDeg", 90),
                num(o, "sneakFactor", 0.35),
                Math.max(0, num(o, "airFactor", 1.0)),
                (int) Math.max(0, num(o, "visualLeadTicks", 20)),
                mult, curves);
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
        if (curves != null) {
            JsonObject c = new JsonObject();
            WindCurve[] all = curves.all();
            for (int i = 0; i < all.length; i++) c.add(Curves.NAMES[i], all[i].toJson());
            o.add("curves", c);
        }
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
}
