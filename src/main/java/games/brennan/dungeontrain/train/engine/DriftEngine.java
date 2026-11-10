package games.brennan.dungeontrain.train.engine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.HashMap;
import java.util.Map;

/**
 * The rules of a drifting carriage's Engine Block, worked on the relay snapshot ({@code {l,h,w,cells:[{p,s,b?}]}}
 * — see {@code CarriageBlockSnapshot}) so they need no world and can be tested on their own.
 *
 * <ul>
 *   <li>A fresh drifting carriage starts with {@link #FRESH_COAL}.</li>
 *   <li>Each time a carriage joins a world from a lease it burns one ({@link #burn}).</li>
 *   <li>A carriage that leaves a world at zero is retired by the relay, which refuses to lease it again.</li>
 *   <li>A carriage from before engines (the relay has no fuel for it) is retrofitted once:
 *       {@link #retrofitCoal} — three, or more for every block and item its riders added.</li>
 * </ul>
 */
public final class DriftEngine {

    /** Most coal an engine holds: one stack. */
    public static final int MAX_COAL = 64;
    /** Coal a brand-new drifting carriage starts with. */
    public static final int FRESH_COAL = 3;
    /** A legacy carriage needs MORE than this many added blocks (or any added item) to earn extra coal. */
    public static final int RETROFIT_BLOCK_THRESHOLD = 5;

    /** The block entity's coal count key, in the world save and in the relay blob alike. */
    public static final String TAG_COAL = "Coal";
    /** The block's registry name as the snapshot writes it. */
    public static final String ENGINE_ID = "dungeontrain:engine_block";
    private static final String BLOCK_ENTITY_ID = "dungeontrain:engine_block";

    private DriftEngine() {}

    public static int clampCoal(int coal) {
        return Math.max(0, Math.min(MAX_COAL, coal));
    }

    /** One world's worth of coal burnt; never below zero. */
    public static int burn(int coal) {
        return clampCoal(clampCoal(coal) - 1);
    }

    // ---- reading a snapshot ----

    /** Index of the first engine cell in {@code cells}, or -1 when the build has none. */
    public static int findEngine(ListTag cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (isEngineCell(cells.getCompound(i))) return i;
        }
        return -1;
    }

    static boolean isEngineCell(CompoundTag cell) {
        return ENGINE_ID.equals(cell.getCompound("s").getString("Name"));
    }

    /** Coal in the build's engine, or -1 when it has none. */
    public static int coalIn(CompoundTag snap) {
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        int i = findEngine(cells);
        if (i < 0) return -1;
        return clampCoal(cells.getCompound(i).getCompound("b").getInt(TAG_COAL));
    }

    /**
     * The coal this carriage arrives with, before this world's burn. The relay's count wins when it has
     * one ({@code relayFuel >= 0}) — a blob only changes when someone edits, so it can be older than the
     * count. With no count, the engine in the blob; with no engine either, a one-time retrofit.
     */
    public static int arrivingCoal(int relayFuel, CompoundTag snap, CompoundTag origin) {
        if (relayFuel >= 0) return clampCoal(relayFuel);
        int inBlob = coalIn(snap);
        if (inBlob >= 0) return inBlob;
        return retrofitCoal(origin, snap);
    }

    // ---- writing a snapshot ----

    /**
     * {@code snap} with its engine holding {@code coal}: the existing engine updated, or — when the build
     * has none (built before engines, or saved by a version that dropped the unknown block) — a new one
     * stamped on a free floor cell. Returns {@code snap} unchanged if there is no engine and no room for
     * one. Never mutates the argument.
     */
    public static CompoundTag withEngineCoal(CompoundTag snap, int coal) {
        CompoundTag out = snap.copy();
        ListTag cells = out.getList("cells", Tag.TAG_COMPOUND);
        int i = findEngine(cells);
        int clamped = clampCoal(coal);
        if (i >= 0) {
            CompoundTag cell = cells.getCompound(i);
            cell.put("b", engineTag(cell.getCompound("b"), clamped));
            setLit(cell.getCompound("s"), clamped > 0);
            return out;
        }
        int l = out.getInt("l"), h = out.getInt("h"), w = out.getInt("w");
        Occupancy occ = Occupancy.of(cells);
        int[] spot = EngineSpot.find(l, h, w, occ::solid);
        if (spot == null) return snap;
        CompoundTag cell = new CompoundTag();
        cell.put("p", new IntArrayTag(spot.clone()));
        CompoundTag state = new CompoundTag();
        state.putString("Name", ENGINE_ID);
        CompoundTag props = new CompoundTag();
        props.putString("facing", EngineSpot.facing(spot, w));
        props.putString("lit", clamped > 0 ? "true" : "false");
        state.put("Properties", props);
        cell.put("s", state);
        cell.put("b", engineTag(new CompoundTag(), clamped));
        cells.add(cell);
        out.put("cells", cells);
        return out;
    }

    private static CompoundTag engineTag(CompoundTag existing, int coal) {
        CompoundTag b = existing.copy();
        b.putString("id", BLOCK_ENTITY_ID);
        b.putInt(TAG_COAL, coal);
        return b;
    }

    private static void setLit(CompoundTag state, boolean lit) {
        CompoundTag props = state.getCompound("Properties");
        props.putString("lit", lit ? "true" : "false");
        state.put("Properties", props);
    }

    // ---- retrofit ----

    /**
     * Coal for a carriage that predates engines. Three, unless its riders added more than
     * {@link #RETROFIT_BLOCK_THRESHOLD} blocks or any items since it first went up — then three plus one
     * for every added block still standing and every added item (counting inside shulker boxes), capped
     * at {@link #MAX_COAL}. {@code origin} is the build as first uploaded; null (no history) gives three.
     */
    public static int retrofitCoal(CompoundTag origin, CompoundTag current) {
        if (origin == null || current == null) return FRESH_COAL;
        int blocks = blocksAdded(origin, current);
        int items = Math.max(0, itemsIn(current) - itemsIn(origin));
        if (blocks <= RETROFIT_BLOCK_THRESHOLD && items == 0) return FRESH_COAL;
        return clampCoal(FRESH_COAL + blocks + items);
    }

    /** Cells holding a block now that held nothing, or a different block, in {@code origin}. */
    static int blocksAdded(CompoundTag origin, CompoundTag current) {
        Map<Long, String> before = names(origin.getList("cells", Tag.TAG_COMPOUND));
        int added = 0;
        ListTag now = current.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < now.size(); i++) {
            CompoundTag cell = now.getCompound(i);
            if (isEngineCell(cell)) continue;
            int[] p = cell.getIntArray("p");
            if (p.length != 3) continue;
            String name = cell.getCompound("s").getString("Name");
            if (!name.equals(before.get(key(p)))) added++;
        }
        return added;
    }

    /** Every item in the build's block entities, nested containers (shulker boxes) included. */
    static int itemsIn(CompoundTag snap) {
        int total = 0;
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag cell = cells.getCompound(i);
            if (isEngineCell(cell) || !cell.contains("b", Tag.TAG_COMPOUND)) continue;
            total += countItems(cell.getCompound("b"));
        }
        return total;
    }

    /**
     * An item stack in 1.21 NBT is a compound with a string {@code id} and a numeric {@code count}; its
     * contents (a shulker's {@code minecraft:container}, a bundle's) sit under {@code components}. Walking
     * every compound and list finds them all without knowing each container's layout.
     */
    private static int countItems(Tag tag) {
        if (tag instanceof CompoundTag c) {
            int n = 0;
            if (c.get("id") instanceof StringTag && c.get("count") instanceof NumericTag count) {
                n += Math.max(0, count.getAsInt());
            }
            for (String k : c.getAllKeys()) n += countItems(c.get(k));
            return n;
        }
        if (tag instanceof ListTag list) {
            int n = 0;
            for (Tag t : list) n += countItems(t);
            return n;
        }
        return 0;
    }

    private static Map<Long, String> names(ListTag cells) {
        Map<Long, String> out = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            CompoundTag cell = cells.getCompound(i);
            int[] p = cell.getIntArray("p");
            if (p.length == 3) out.put(key(p), cell.getCompound("s").getString("Name"));
        }
        return out;
    }

    static long key(int[] p) {
        return key(p[0], p[1], p[2]);
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /** Which cells of a snapshot hold a block. */
    private record Occupancy(java.util.Set<Long> filled) {
        static Occupancy of(ListTag cells) {
            java.util.Set<Long> set = new java.util.HashSet<>();
            for (int i = 0; i < cells.size(); i++) {
                int[] p = cells.getCompound(i).getIntArray("p");
                if (p.length == 3) set.add(key(p));
            }
            return new Occupancy(set);
        }

        boolean solid(int x, int y, int z) {
            return filled.contains(key(x, y, z));
        }
    }
}
