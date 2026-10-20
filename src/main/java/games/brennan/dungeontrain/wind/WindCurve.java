package games.brennan.dungeontrain.wind;

import com.google.gson.JsonObject;

/**
 * How one wind quantity changes with distance {@code d} (blocks past the Upside-down end). Two shapes:
 * <ul>
 *   <li><b>log</b> — {@code scale × ln(1 + d / ref)}: 0 at the start, rising forever but ever more slowly (no
 *       upper limit). Used for strength.</li>
 *   <li><b>approach</b> — {@code max − (max − start) × ½^(d / halfLife)}: starts at {@code start} and closes half
 *       the remaining gap to {@code max} every {@code halfLife} blocks, getting ever closer without reaching
 *       it. {@code max} may be below {@code start} (a quantity that shrinks toward a floor). Used for gust
 *       frequency and length, and the rest.</li>
 * </ul>
 * Mirrored in {@code scripts/wind/web/wind.js} ({@code curveAt}); keep the arithmetic identical.
 */
public record WindCurve(Kind kind, double scale, double ref, double start, double max, double halfLife) {

    public enum Kind { LOG, APPROACH }

    public static WindCurve log(double scale, double ref) {
        return new WindCurve(Kind.LOG, scale, ref, 0, 0, 0);
    }

    public static WindCurve approach(double start, double max, double halfLife) {
        return new WindCurve(Kind.APPROACH, 0, 0, start, max, halfLife);
    }

    public static WindCurve constant(double v) {
        return approach(v, v, 1);
    }

    /** The value at {@code d} (clamped to 0 before the start). */
    public double at(double d) {
        double x = Math.max(0.0, d);
        if (kind == Kind.LOG) {
            return scale * Math.log(1.0 + x / ref);
        }
        return max - (max - start) * Math.pow(0.5, x / halfLife);
    }

    static WindCurve parse(JsonObject o, String name) {
        String kind = o.has("kind") ? o.get("kind").getAsString() : "";
        return switch (kind) {
            case "log" -> {
                double ref = num(o, "ref", name);
                if (ref <= 0) throw new IllegalArgumentException("wind curve '" + name + "' ref must be > 0");
                yield log(num(o, "scale", name), ref);
            }
            case "approach" -> {
                double half = num(o, "halfLife", name);
                if (half <= 0) throw new IllegalArgumentException("wind curve '" + name + "' halfLife must be > 0");
                yield approach(num(o, "start", name), num(o, "max", name), half);
            }
            default -> throw new IllegalArgumentException(
                    "wind curve '" + name + "' kind must be \"log\" or \"approach\", got '" + kind + "'");
        };
    }

    JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (kind == Kind.LOG) {
            o.addProperty("kind", "log");
            o.addProperty("scale", scale);
            o.addProperty("ref", ref);
        } else {
            o.addProperty("kind", "approach");
            o.addProperty("start", start);
            o.addProperty("max", max);
            o.addProperty("halfLife", halfLife);
        }
        return o;
    }

    private static double num(JsonObject o, String key, String name) {
        if (!o.has(key)) throw new IllegalArgumentException("wind curve '" + name + "' is missing '" + key + "'");
        try {
            double v = o.get(key).getAsDouble();
            if (!Double.isFinite(v)) throw new IllegalArgumentException("wind curve '" + name + "." + key + "' is not finite");
            return v;
        } catch (ClassCastException | IllegalStateException | NumberFormatException e) {
            throw new IllegalArgumentException("wind curve '" + name + "." + key + "' must be a number", e);
        }
    }
}
