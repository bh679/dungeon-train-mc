package games.brennan.dungeontrain.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;

/**
 * Shared JSON (de)serialisation for the per-template weight stores
 * ({@code CarriageWeights}, {@code CarriageContentsWeights}, {@code TrackVariantWeights}). Each
 * entry's JSON value is <b>either</b> a bare integer (weight only, default gate — the legacy form,
 * fully backward-compatible) <b>or</b> an object:
 *
 * <pre>{ "weight": 5, "minLevel": 3, "maxLevel": "all", "phases": ["NETHER","VOID"] }</pre>
 *
 * <p>The object form is emitted only when the gate is non-default ({@link TemplateGate#isDefault()}),
 * and within it each gate field is omitted when it is at its own default (minLevel 0, maxLevel
 * {@link TemplateGate#ALL}, all phases). So a store full of plain weights round-trips byte-identically
 * to the pre-feature {@code {"id": int}} files. {@code maxLevel} accepts a number or the string
 * {@code "all"} (= {@link TemplateGate#ALL}), reusing the idiom from the mob difficulty band.</p>
 */
public final class TemplateWeightCodec {

    private TemplateWeightCodec() {}

    public static final String K_WEIGHT = "weight";
    public static final String K_MIN = "minLevel";
    public static final String K_MAX = "maxLevel";
    public static final String K_PHASES = "phases";
    /** Optional link to a named Stage (its gate becomes the entry's effective gate). Absent = Custom. */
    public static final String K_STAGE = "stage";
    /** String accepted (and never emitted — absence means the same) for {@link TemplateGate#ALL}. */
    public static final String MAX_ALL = "all";

    /**
     * Parse one entry value into a {@link TemplateMeta}, applying {@code clampWeight} (the store's
     * {@code [MIN,MAX]} clamp) to the weight. Returns {@code null} when the value is neither a finite
     * number nor an object carrying a finite numeric {@code weight} — the caller skips + logs, exactly
     * as the old per-store {@code parseWeight} did for non-numeric values.
     */
    public static TemplateMeta parseEntry(JsonElement el, IntUnaryOperator clampWeight) {
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            Integer w = finiteRound(el);
            return w == null ? null : TemplateMeta.of(clampWeight.applyAsInt(w));
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            JsonElement we = o.get(K_WEIGHT);
            if (we == null || !we.isJsonPrimitive() || !we.getAsJsonPrimitive().isNumber()) return null;
            Integer w = finiteRound(we);
            if (w == null) return null;
            return new TemplateMeta(clampWeight.applyAsInt(w), parseGate(o), parseStage(o));
        }
        return null;
    }

    /** The optional Stage link on an entry object; {@code null} (Custom) when absent or blank. */
    public static String parseStage(JsonObject o) {
        JsonElement el = o.get(K_STAGE);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String s = el.getAsString().trim().toLowerCase(Locale.ROOT);
            return s.isEmpty() ? null : s;
        }
        return null;
    }

    private static Integer finiteRound(JsonElement el) {
        double raw;
        try {
            raw = el.getAsDouble();
        } catch (Exception e) {
            return null;
        }
        if (Double.isNaN(raw) || Double.isInfinite(raw)) return null;
        return (int) Math.round(raw);
    }

    /** Parse the gate fields from an entry object; every missing field falls back to its default. */
    public static TemplateGate parseGate(JsonObject o) {
        int min = numberOr(o.get(K_MIN), 0);
        int max = parseMax(o.get(K_MAX));
        EnumSet<TrainPhase> phases = parsePhases(o.get(K_PHASES));
        return new TemplateGate(min, max, phases);
    }

    private static int numberOr(JsonElement el, int fallback) {
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return el.getAsInt();
        }
        return fallback;
    }

    private static int parseMax(JsonElement el) {
        if (el != null && el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString() && MAX_ALL.equalsIgnoreCase(p.getAsString())) return TemplateGate.ALL;
            if (p.isNumber()) return p.getAsInt();
        }
        return TemplateGate.ALL;
    }

    /** Absent / non-array / unparseable ⇒ {@code null} (the gate ctor treats that as "all phases"). */
    private static EnumSet<TrainPhase> parsePhases(JsonElement el) {
        if (el == null || !el.isJsonArray()) return null;
        EnumSet<TrainPhase> set = EnumSet.noneOf(TrainPhase.class);
        for (JsonElement e : el.getAsJsonArray()) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) continue;
            TrainPhase ph = phaseByName(e.getAsString());
            if (ph != null) set.add(ph);
        }
        return set.isEmpty() ? null : set;
    }

    private static TrainPhase phaseByName(String s) {
        try {
            return TrainPhase.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Serialise an id→meta map to a JSON object with keys sorted (stable diffs): a bare int for
     * default-gate entries, the object form otherwise.
     */
    public static JsonObject toJson(Map<String, TemplateMeta> byId) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, TemplateMeta> e : new TreeMap<>(byId).entrySet()) {
            TemplateMeta meta = e.getValue();
            // Bare-int only when both axes are at their no-op default: default inline gate AND
            // no Stage link. A linked entry always takes the object form (it carries "stage").
            if (meta.gate().isDefault() && meta.stageId() == null) {
                out.addProperty(e.getKey(), meta.weight());
            } else {
                out.add(e.getKey(), entryObject(meta));
            }
        }
        return out;
    }

    private static JsonObject entryObject(TemplateMeta meta) {
        JsonObject o = new JsonObject();
        o.addProperty(K_WEIGHT, meta.weight());
        writeGateFields(o, meta.gate());
        if (meta.stageId() != null) o.addProperty(K_STAGE, meta.stageId());
        return o;
    }

    /**
     * Emit the non-default gate fields ({@link #K_MIN}, {@link #K_MAX}, {@link #K_PHASES}) into
     * {@code o}; each field is omitted when it sits at its own default (minLevel 0, maxLevel
     * {@link TemplateGate#ALL}, all phases), so a {@link TemplateGate#isDefault() default} gate adds
     * nothing. Shared by the weight stores and the carriage-parts sidecar codec so both emit the
     * identical on-disk gate shape.
     */
    public static void writeGateFields(JsonObject o, TemplateGate g) {
        if (g.minLevel() != 0) o.addProperty(K_MIN, g.minLevel());
        if (g.maxLevel() != TemplateGate.ALL) o.addProperty(K_MAX, g.maxLevel());
        if (g.phases().size() != TrainPhase.values().length) {
            JsonArray arr = new JsonArray();
            // Emit in enum order for stable diffs.
            for (TrainPhase ph : TrainPhase.values()) {
                if (g.phases().contains(ph)) arr.add(ph.name());
            }
            o.add(K_PHASES, arr);
        }
    }
}
