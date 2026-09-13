package games.brennan.dungeontrain.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.brennan.dungeontrain.block.stage.StageWoodFamily;

import java.util.ArrayList;
import java.util.List;

/**
 * The baked per-stage answer for every stage placeholder block — what {@code stage_block_N},
 * {@code stage_stairs_N}, {@code stage_slab_N}, {@code stage_button}, {@code stage_pressure_plate}
 * and the {@code stage_*} wood set become when a carriage lands in this stage.
 *
 * <p>Persisted as the {@code "palette"} object of a stage in {@code stages.json}, written by
 * {@code editor.StagePaletteBaker} and hand-editable. Every field is a plain block id so a builder
 * can override the derivation without touching code. Lists shorter than the slot count are read
 * back looped (see {@link #solid(int)}); a stage without a palette resolves through
 * {@link #DEFAULT}.</p>
 *
 * @param solid         the 10 solid slots, most-used first.
 * @param stairs        the 2 stairs slots.
 * @param slabs         the 2 slab slots.
 * @param button        block id for {@code stage_button}.
 * @param pressurePlate block id for {@code stage_pressure_plate}.
 * @param wood          {@link StageWoodFamily#id()} for the wood set.
 */
public record StagePalette(List<String> solid, List<String> stairs, List<String> slabs,
                           String button, String pressurePlate, String wood) {

    public static final int SOLID_SLOTS = 10;
    public static final int STAIRS_SLOTS = 2;
    public static final int SLAB_SLOTS = 2;

    public static final String K_SOLID = "solid";
    public static final String K_STAIRS = "stairs";
    public static final String K_SLABS = "slabs";
    public static final String K_BUTTON = "button";
    public static final String K_PRESSURE_PLATE = "pressurePlate";
    public static final String K_WOOD = "wood";

    private static final String DEFAULT_SOLID = "minecraft:stone";
    private static final String DEFAULT_STAIRS = "minecraft:stone_stairs";
    private static final String DEFAULT_SLAB = "minecraft:stone_slab";
    private static final String DEFAULT_BUTTON = "minecraft:stone_button";
    private static final String DEFAULT_PLATE = "minecraft:stone_pressure_plate";

    /** What a placeholder becomes when no stage (or a stage without a palette) is in scope. */
    public static final StagePalette DEFAULT = new StagePalette(
        List.of(DEFAULT_SOLID), List.of(DEFAULT_STAIRS), List.of(DEFAULT_SLAB),
        DEFAULT_BUTTON, DEFAULT_PLATE, StageWoodFamily.FALLBACK.id());

    public StagePalette {
        solid = nonEmpty(solid, DEFAULT_SOLID);
        stairs = nonEmpty(stairs, DEFAULT_STAIRS);
        slabs = nonEmpty(slabs, DEFAULT_SLAB);
        button = blankOr(button, DEFAULT_BUTTON);
        pressurePlate = blankOr(pressurePlate, DEFAULT_PLATE);
        wood = StageWoodFamily.byId(wood).orElse(StageWoodFamily.FALLBACK).id();
    }

    /** Solid slot {@code index} (0-based); lists shorter than the slot count loop. */
    public String solid(int index) {
        return looped(solid, index);
    }

    /** Stairs slot {@code index} (0-based), looped. */
    public String stairs(int index) {
        return looped(stairs, index);
    }

    /** Slab slot {@code index} (0-based), looped. */
    public String slab(int index) {
        return looped(slabs, index);
    }

    /** The wood family, never null (the constructor already fell back). */
    public StageWoodFamily woodFamily() {
        return StageWoodFamily.byId(wood).orElse(StageWoodFamily.FALLBACK);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add(K_SOLID, toArray(solid));
        o.add(K_STAIRS, toArray(stairs));
        o.add(K_SLABS, toArray(slabs));
        o.addProperty(K_BUTTON, button);
        o.addProperty(K_PRESSURE_PLATE, pressurePlate);
        o.addProperty(K_WOOD, wood);
        return o;
    }

    /**
     * Parse a palette; {@code null} for anything that is not an object, so a stage without one keeps
     * {@code palette == null} (and the baker knows it still needs baking). Missing/blank fields
     * inside an object fall back per the constructor.
     */
    public static StagePalette fromJson(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        return new StagePalette(
            strings(o.get(K_SOLID)), strings(o.get(K_STAIRS)), strings(o.get(K_SLABS)),
            string(o.get(K_BUTTON)), string(o.get(K_PRESSURE_PLATE)), string(o.get(K_WOOD)));
    }

    private static String looped(List<String> list, int index) {
        int n = list.size();
        int i = ((Math.max(0, index) % n) + n) % n;
        return list.get(i);
    }

    private static List<String> nonEmpty(List<String> in, String fallback) {
        List<String> out = new ArrayList<>();
        if (in != null) {
            for (String s : in) {
                if (s != null && !s.isBlank()) out.add(s.trim());
            }
        }
        if (out.isEmpty()) out.add(fallback);
        return List.copyOf(out);
    }

    private static String blankOr(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s.trim();
    }

    private static JsonArray toArray(List<String> list) {
        JsonArray arr = new JsonArray();
        for (String s : list) arr.add(s);
        return arr;
    }

    private static List<String> strings(JsonElement el) {
        List<String> out = new ArrayList<>();
        if (el != null && el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) out.add(e.getAsString());
            }
        }
        return out;
    }

    private static String string(JsonElement el) {
        return (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString())
            ? el.getAsString() : null;
    }
}
