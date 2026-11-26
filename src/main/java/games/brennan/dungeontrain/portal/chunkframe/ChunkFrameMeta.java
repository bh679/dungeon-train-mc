package games.brennan.dungeontrain.portal.chunkframe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.VariantState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import games.brennan.dungeontrain.template.TemplateGate;
import games.brennan.dungeontrain.worldgen.TrainPhase;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a frame says about where it is used — its {@code <name>.frame.json}, beside its structure.
 *
 * <p>{@link #rooms} is the chunk-dimension room variants the frame dresses. Null means <b>every</b>
 * chunk dimension, which is what a frame with no file (or a file without the list) does, so a new
 * frame is live everywhere until its author narrows it. An empty set is "nowhere" — a frame parked
 * without deleting it.</p>
 *
 * <p>{@link #weight} is how often the frame is picked against the other frames that dress the same
 * room.</p>
 *
 * <p>{@link #dimension} is how the rooms it dresses are enclosed — see {@link ChunkFrameDimension}.</p>
 *
 * <p>{@link #doorDistance} is how far out along the plain an {@link ChunkFrameDimension#ENDLESS_OPEN}
 * frame stands each door from the chunk — {@link #DEFAULT_DOOR_DISTANCE}, one chunk, unless its
 * author says otherwise. Read under that dimension only.</p>
 *
 * <p>{@link #ground} is the block variant that plain is laid in — one cell's candidates, authored in
 * the Block Variant menu like any other cell. Empty means {@link #DEFAULT_GROUND_BLOCK}. Kept in this
 * file rather than a sidecar of its own so save, rename, copy and delete carry it with the rest.</p>
 *
 * <p>{@link #gate} and {@link #stageId} say where along the train the frame may appear, as every
 * other template's spawn gate does: a Diff-Level range, the bands (phases) and dates it is allowed
 * in, or a Stage preset that owns the levels and bands. The effective gate is
 * {@link games.brennan.dungeontrain.editor.StageStore#effectiveGate}.</p>
 */
public record ChunkFrameMeta(Set<String> rooms, int weight, ChunkFrameDimension dimension,
                             TemplateGate gate, String stageId, int doorDistance,
                             List<VariantState> ground) {

    public static final int SCHEMA_VERSION = 1;
    public static final int MIN_WEIGHT = 1;
    public static final int MAX_WEIGHT = 100;

    /** One chunk of plain between the chunk and each door — what an Endless open frame says by default. */
    public static final int DEFAULT_DOOR_DISTANCE = 16;

    /** The most candidates the ground palette holds — the Block Variant menu's own per-cell cap. */
    public static final int MAX_GROUND_ENTRIES = 32;

    /** What an Endless open plain is laid in when its frame has not been given a ground. */
    public static final String DEFAULT_GROUND_BLOCK = "minecraft:grass_block";

    /** Every chunk dimension, weight 1 — a frame with nothing said about it. */
    public static final ChunkFrameMeta DEFAULT = new ChunkFrameMeta(null, MIN_WEIGHT);

    public ChunkFrameMeta {
        rooms = rooms == null ? null : Set.copyOf(new LinkedHashSet<>(rooms));
        weight = Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, weight));
        dimension = dimension == null ? ChunkFrameDimension.DEFAULT : dimension;
        gate = gate == null ? TemplateGate.DEFAULT : gate;
        stageId = stageId == null || stageId.isBlank() ? null : stageId.trim().toLowerCase(Locale.ROOT);
        doorDistance = new games.brennan.dungeontrain.portal.PortalRoomDoorDistance(doorDistance).value();
        ground = ground == null ? List.of() : List.copyOf(ground.size() > MAX_GROUND_ENTRIES
            ? ground.subList(0, MAX_GROUND_ENTRIES) : ground);
    }

    /** Rooms, weight, dimension, gate, stage and door distance, on the default ground. */
    public ChunkFrameMeta(Set<String> rooms, int weight, ChunkFrameDimension dimension,
                          TemplateGate gate, String stageId, int doorDistance) {
        this(rooms, weight, dimension, gate, stageId, doorDistance, List.of());
    }

    /** Rooms, weight, dimension, gate and stage, at the default door distance. */
    public ChunkFrameMeta(Set<String> rooms, int weight, ChunkFrameDimension dimension,
                          TemplateGate gate, String stageId) {
        this(rooms, weight, dimension, gate, stageId, DEFAULT_DOOR_DISTANCE);
    }

    /** Rooms and weight, enclosed in the default {@link ChunkFrameDimension#BEDROCK}, ungated. */
    public ChunkFrameMeta(Set<String> rooms, int weight) {
        this(rooms, weight, ChunkFrameDimension.DEFAULT);
    }

    /** Rooms, weight and dimension, ungated. */
    public ChunkFrameMeta(Set<String> rooms, int weight, ChunkFrameDimension dimension) {
        this(rooms, weight, dimension, TemplateGate.DEFAULT, null);
    }

    /** True when the frame dresses every chunk dimension. */
    public boolean allRooms() {
        return rooms == null;
    }

    /** True when the frame dresses {@code room}. */
    public boolean appliesTo(String room) {
        return rooms == null || rooms.contains(room);
    }

    /**
     * This meta with {@code room} switched on or off. Switching one off from "every room" first
     * spells "every room" out as {@code allRooms}, so the others stay on.
     */
    public ChunkFrameMeta toggled(String room, boolean on, List<String> allRooms) {
        Set<String> next = new LinkedHashSet<>(rooms == null ? allRooms : rooms);
        if (on) next.add(room); else next.remove(room);
        // Back to "every room" when it is every room, so a later chunk dimension joins it too.
        if (next.containsAll(allRooms)) return withRooms(null);
        return withRooms(next);
    }

    private ChunkFrameMeta withRooms(Set<String> newRooms) {
        return new ChunkFrameMeta(newRooms, weight, dimension, gate, stageId, doorDistance, ground);
    }

    public ChunkFrameMeta withAllRooms() {
        return withRooms(null);
    }

    public ChunkFrameMeta withNoRooms() {
        return withRooms(Set.of());
    }

    public ChunkFrameMeta withWeight(int newWeight) {
        return new ChunkFrameMeta(rooms, newWeight, dimension, gate, stageId, doorDistance, ground);
    }

    public ChunkFrameMeta withDimension(ChunkFrameDimension newDimension) {
        return new ChunkFrameMeta(rooms, weight, newDimension, gate, stageId, doorDistance, ground);
    }

    public ChunkFrameMeta withGate(TemplateGate newGate) {
        return new ChunkFrameMeta(rooms, weight, dimension, newGate, stageId, doorDistance, ground);
    }

    /** Linked to Stage {@code newStageId}, or back to Custom (its own gate) when null. */
    public ChunkFrameMeta withStage(String newStageId) {
        return new ChunkFrameMeta(rooms, weight, dimension, gate, newStageId, doorDistance, ground);
    }

    /** The same meta with each door {@code newDoorDistance} blocks out along an Endless open plain. */
    public ChunkFrameMeta withDoorDistance(int newDoorDistance) {
        return new ChunkFrameMeta(rooms, weight, dimension, gate, stageId, newDoorDistance, ground);
    }

    /** The same meta with its plain laid in {@code newGround} (empty: the default grass). */
    public ChunkFrameMeta withGround(List<VariantState> newGround) {
        return new ChunkFrameMeta(rooms, weight, dimension, gate, stageId, doorDistance, newGround);
    }

    /** {@code gate} with the min level changed; a max under it rises to meet it, as TemplateGate does. */
    public ChunkFrameMeta withMinLevel(int min) {
        return withGate(new TemplateGate(min, gate.maxLevel(), gate.phases(), gate.periods()));
    }

    /** {@code gate} with the max level changed ({@link TemplateGate#ALL} for no cap); never under the min. */
    public ChunkFrameMeta withMaxLevel(int max) {
        int clamped = max == TemplateGate.ALL ? max : Math.max(gate.minLevel(), max);
        return withGate(new TemplateGate(gate.minLevel(), clamped, gate.phases(), gate.periods()));
    }

    /** {@code gate} with {@code phase} allowed or not; turning off the last one leaves every phase on. */
    public ChunkFrameMeta withPhase(TrainPhase phase, boolean on) {
        Set<TrainPhase> next = EnumSet.noneOf(TrainPhase.class);
        next.addAll(gate.phases());
        if (on) next.add(phase); else next.remove(phase);
        return withGate(new TemplateGate(gate.minLevel(), gate.maxLevel(), next, gate.periods()));
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", SCHEMA_VERSION);
        o.addProperty("weight", weight);
        if (dimension != ChunkFrameDimension.DEFAULT) o.addProperty("dimension", dimension.id());
        if (doorDistance != DEFAULT_DOOR_DISTANCE) o.addProperty("doorDistance", doorDistance);
        if (!ground.isEmpty()) {
            // The bare-array cell form every other variant sidecar writes (lock id 0: one cell, no group).
            StringBuilder sb = new StringBuilder();
            CarriageVariantBlocks.appendCellJson(sb, ground, 0);
            o.add("ground", JsonParser.parseString(sb.toString()));
        }
        if (rooms != null) {
            JsonArray arr = new JsonArray();
            rooms.stream().sorted().forEach(arr::add);
            o.add("rooms", arr);
        }
        // The gate in the shape weights.json entries use, and only what differs from ungated.
        if (gate.minLevel() != 0) o.addProperty("minLevel", gate.minLevel());
        if (gate.maxLevel() != TemplateGate.ALL) o.addProperty("maxLevel", gate.maxLevel());
        if (gate.phases().size() != TemplateGate.ALL_PHASES.size()) {
            JsonArray arr = new JsonArray();
            gate.phases().forEach(p -> arr.add(p.name()));
            o.add("phases", arr);
        }
        if (!gate.periods().isEmpty()) {
            JsonArray arr = new JsonArray();
            gate.periods().forEach(arr::add);
            o.add("periods", arr);
        }
        if (stageId != null) o.addProperty("stage", stageId);
        return o;
    }

    /** Total: anything unreadable falls back to the default for that field. */
    public static ChunkFrameMeta fromJson(JsonObject o) {
        int weight = o.has("weight") && o.get("weight").isJsonPrimitive() ? o.get("weight").getAsInt() : MIN_WEIGHT;
        Set<String> rooms = null;
        if (o.has("rooms") && o.get("rooms").isJsonArray()) {
            rooms = new LinkedHashSet<>(strings(o.getAsJsonArray("rooms")));
        }
        ChunkFrameDimension dimension = o.has("dimension") && o.get("dimension").isJsonPrimitive()
            ? ChunkFrameDimension.parse(o.get("dimension").getAsString()) : ChunkFrameDimension.DEFAULT;
        int min = intOr(o, "minLevel", 0);
        int max = intOr(o, "maxLevel", TemplateGate.ALL);
        Set<TrainPhase> phases = EnumSet.noneOf(TrainPhase.class);
        if (o.has("phases") && o.get("phases").isJsonArray()) {
            for (String name : strings(o.getAsJsonArray("phases"))) {
                try {
                    phases.add(TrainPhase.valueOf(name.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // A phase this build does not know — dropped, as weights.json does.
                }
            }
        }
        List<String> periods = o.has("periods") && o.get("periods").isJsonArray()
            ? strings(o.getAsJsonArray("periods")) : List.of();
        String stage = o.has("stage") && o.get("stage").isJsonPrimitive() ? o.get("stage").getAsString() : null;
        int doorDistance = intOr(o, "doorDistance", DEFAULT_DOOR_DISTANCE);
        return new ChunkFrameMeta(rooms, weight, dimension, new TemplateGate(min, max, phases, new LinkedHashSet<>(periods)), stage,
            doorDistance, groundOf(o));
    }

    /** The file's {@code ground} cell, or empty when absent or unreadable — a plain is never why a frame fails. */
    private static List<VariantState> groundOf(JsonObject o) {
        if (!o.has("ground")) return List.of();
        try {
            CarriageVariantBlocks.ParsedCell cell = CarriageVariantBlocks.parseCellValue(o.get("ground"),
                BuiltInRegistries.BLOCK.asLookup(), "chunk frame ground", BlockPos.ZERO);
            return cell == null ? List.of() : cell.states();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static List<String> strings(JsonArray arr) {
        List<String> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (el.isJsonPrimitive()) out.add(el.getAsString());
        }
        return out;
    }
}
