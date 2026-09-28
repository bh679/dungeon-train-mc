package games.brennan.dungeontrain.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import games.brennan.dungeontrain.block.stage.StageStoneFamily;
import games.brennan.dungeontrain.block.stage.StageWoodFamily;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
 * @param stone         {@link StageStoneFamily#id()} for the stone set.
 * @param overrides     user overrides, placeholder name → block id; win over every derived slot and
 *                      survive re-bakes (the Stage Palette panel writes them).
 * @param woodLocked    the user chose {@code wood}; a re-bake keeps it instead of re-detecting.
 * @param stoneLocked   the user chose {@code stone}; likewise.
 */
public record StagePalette(List<String> solid, List<String> stairs, List<String> slabs,
                           String button, String pressurePlate, String wood, String stone,
                           Map<String, String> overrides, boolean woodLocked, boolean stoneLocked) {

    public static final int SOLID_SLOTS = 10;
    public static final int STAIRS_SLOTS = 2;
    public static final int SLAB_SLOTS = 2;

    public static final String K_SOLID = "solid";
    public static final String K_STAIRS = "stairs";
    public static final String K_SLABS = "slabs";
    public static final String K_BUTTON = "button";
    public static final String K_PRESSURE_PLATE = "pressurePlate";
    public static final String K_WOOD = "wood";
    public static final String K_STONE = "stone";
    public static final String K_OVERRIDES = "overrides";
    public static final String K_WOOD_LOCKED = "woodLocked";
    public static final String K_STONE_LOCKED = "stoneLocked";

    private static final String DEFAULT_SOLID = "minecraft:stone";
    private static final String DEFAULT_STAIRS = "minecraft:stone_stairs";
    private static final String DEFAULT_SLAB = "minecraft:stone_slab";
    private static final String DEFAULT_BUTTON = "minecraft:stone_button";
    private static final String DEFAULT_PLATE = "minecraft:stone_pressure_plate";

    /** What a placeholder becomes when no stage (or a stage without a palette) is in scope. */
    public static final StagePalette DEFAULT = new StagePalette(
        List.of(DEFAULT_SOLID), List.of(DEFAULT_STAIRS), List.of(DEFAULT_SLAB),
        DEFAULT_BUTTON, DEFAULT_PLATE, StageWoodFamily.FALLBACK.id(), StageStoneFamily.FALLBACK.id());

    public StagePalette {
        solid = nonEmpty(solid, DEFAULT_SOLID);
        stairs = nonEmpty(stairs, DEFAULT_STAIRS);
        slabs = nonEmpty(slabs, DEFAULT_SLAB);
        button = blankOr(button, DEFAULT_BUTTON);
        pressurePlate = blankOr(pressurePlate, DEFAULT_PLATE);
        wood = StageWoodFamily.byId(wood).orElse(StageWoodFamily.FALLBACK).id();
        stone = StageStoneFamily.byId(stone).orElse(StageStoneFamily.FALLBACK).id();
        overrides = cleanOverrides(overrides);
    }

    /** Derived-only shape: no overrides, families unlocked. */
    public StagePalette(List<String> solid, List<String> stairs, List<String> slabs,
                        String button, String pressurePlate, String wood, String stone) {
        this(solid, stairs, slabs, button, pressurePlate, wood, stone, null, false, false);
    }

    /** Pre-stone-set shape (six fields) — {@code stone} defaults to the plain stone family. */
    public StagePalette(List<String> solid, List<String> stairs, List<String> slabs,
                        String button, String pressurePlate, String wood) {
        this(solid, stairs, slabs, button, pressurePlate, wood, null);
    }

    /** The user override for placeholder {@code name}, or null. */
    public String override(String name) {
        return name == null ? null : overrides.get(name);
    }

    /** Copy with {@code name} overridden to {@code blockId} (null/blank clears the override). */
    public StagePalette withOverride(String name, String blockId) {
        Map<String, String> next = new LinkedHashMap<>(overrides);
        if (blockId == null || blockId.isBlank()) next.remove(name); else next.put(name, blockId.trim());
        return new StagePalette(solid, stairs, slabs, button, pressurePlate, wood, stone, next, woodLocked, stoneLocked);
    }

    /** Copy with the wood family set by the user ({@code null} ⇒ unlock, keep the current value). */
    public StagePalette withWood(StageWoodFamily family) {
        return new StagePalette(solid, stairs, slabs, button, pressurePlate,
            family == null ? wood : family.id(), stone, overrides, family != null, stoneLocked);
    }

    /** Copy with the stone family set by the user ({@code null} ⇒ unlock, keep the current value). */
    public StagePalette withStone(StageStoneFamily family) {
        return new StagePalette(solid, stairs, slabs, button, pressurePlate, wood,
            family == null ? stone : family.id(), overrides, woodLocked, family != null);
    }

    /**
     * {@code derived} (a fresh bake) carrying this palette's user state: overrides, and the locked
     * families. What every re-bake goes through so a user's choices are never re-derived away.
     */
    public StagePalette carryUserStateOnto(StagePalette derived) {
        return new StagePalette(derived.solid, derived.stairs, derived.slabs, derived.button,
            derived.pressurePlate, woodLocked ? wood : derived.wood, stoneLocked ? stone : derived.stone,
            overrides, woodLocked, stoneLocked);
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

    /** The stone family, never null (the constructor already fell back). */
    public StageStoneFamily stoneFamily() {
        return StageStoneFamily.byId(stone).orElse(StageStoneFamily.FALLBACK);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add(K_SOLID, toArray(solid));
        o.add(K_STAIRS, toArray(stairs));
        o.add(K_SLABS, toArray(slabs));
        o.addProperty(K_BUTTON, button);
        o.addProperty(K_PRESSURE_PLATE, pressurePlate);
        o.addProperty(K_WOOD, wood);
        o.addProperty(K_STONE, stone);
        if (!overrides.isEmpty()) {
            JsonObject ov = new JsonObject();
            for (Map.Entry<String, String> e : new TreeMap<>(overrides).entrySet()) {
                ov.addProperty(e.getKey(), e.getValue());
            }
            o.add(K_OVERRIDES, ov);
        }
        if (woodLocked) o.addProperty(K_WOOD_LOCKED, true);
        if (stoneLocked) o.addProperty(K_STONE_LOCKED, true);
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
            string(o.get(K_BUTTON)), string(o.get(K_PRESSURE_PLATE)), string(o.get(K_WOOD)),
            string(o.get(K_STONE)), overrides(o.get(K_OVERRIDES)),
            bool(o.get(K_WOOD_LOCKED)), bool(o.get(K_STONE_LOCKED)));
    }

    private static Map<String, String> cleanOverrides(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in != null) {
            for (Map.Entry<String, String> e : in.entrySet()) {
                if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null && !e.getValue().isBlank()) {
                    out.put(e.getKey().trim(), e.getValue().trim());
                }
            }
        }
        return Map.copyOf(out);
    }

    private static Map<String, String> overrides(JsonElement el) {
        Map<String, String> out = new LinkedHashMap<>();
        if (el != null && el.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                String v = string(e.getValue());
                if (v != null) out.put(e.getKey(), v);
            }
        }
        return out;
    }

    private static boolean bool(JsonElement el) {
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean() && el.getAsBoolean();
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
