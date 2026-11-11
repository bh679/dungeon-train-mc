package games.brennan.dungeontrain.train.engine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rules of a drifting carriage's Drifting Engine Blocks, worked on the relay snapshot ({@code {l,h,w,cells:[{p,s,b?}]}}
 * — see {@code CarriageBlockSnapshot}) so they need no world and can be tested on their own.
 *
 * <ul>
 *   <li>A carriage may hold any number of engines; its fuel is their total.</li>
 *   <li>A fresh drifting carriage starts with {@link #FRESH_COAL}.</li>
 *   <li>Each time a carriage joins a world from a lease it burns one ({@link #burn}), from the first engine
 *       that still has coal ({@link #burnOne}): one engine runs dry and goes dark before the next is touched.</li>
 *   <li>A carriage that leaves a world at zero is retired by the relay, which refuses to lease it again.</li>
 *   <li>A carriage from before engines (the relay has no fuel for it) is retrofitted once:
 *       {@link #retrofitCoal} — three, or more for every block and item its riders added.</li>
 * </ul>
 */
public final class DriftEngine {

    /** Most coal one engine takes in: what its input accepts, and the most a retrofit gives. */
    public static final int MAX_COAL = 12;
    /**
     * Sanity bound on a stored count, an engine's or a carriage's. Engines fed above {@link #MAX_COAL}
     * before the cap came down keep their coal until it burns away, so a stored count is not held to it.
     */
    public static final int MAX_STORED = 10_000;
    /** Coal a brand-new drifting carriage starts with. */
    public static final int FRESH_COAL = 3;
    /** A legacy carriage needs MORE than this many added blocks (or any added item) to earn extra coal. */
    public static final int RETROFIT_BLOCK_THRESHOLD = 5;

    /** The block entity's coal count key, in the world save and in the relay blob alike. */
    public static final String TAG_COAL = "Coal";
    /**
     * How much of {@link #TAG_COAL} sits in the input slot — coal put in during the current world,
     * which can still be taken back. Cleared whenever the engine joins a new world: it all becomes
     * banked then.
     */
    public static final String TAG_INPUT = "Input";
    /** The block's registry name as the snapshot writes it. */
    public static final String ENGINE_ID = "dungeontrain:drifting_engine_block";
    private static final String BLOCK_ENTITY_ID = "dungeontrain:drifting_engine_block";

    private DriftEngine() {}

    /** A stored count (one engine's, or a carriage's total) held to 0..{@link #MAX_STORED}. */
    public static int clampCoal(int coal) {
        return Math.max(0, Math.min(MAX_STORED, coal));
    }

    /** One world's worth of coal burnt from a carriage's total; never below zero. */
    public static int burn(int coal) {
        return clampCoal(clampCoal(coal) - 1);
    }

    /**
     * How a total is shared over {@code engines} engines when their own counts are unknown or stale: the
     * last engines are filled to {@link #MAX_COAL} first and the first takes the rest, so the burn — which
     * takes from the first engine with coal — drains the first one. The first may hold more than
     * {@link #MAX_COAL} when the total needs it.
     */
    public static int[] share(int total, int engines) {
        int[] out = new int[Math.max(0, engines)];
        int left = clampCoal(total);
        for (int i = out.length - 1; i > 0; i--) {
            out[i] = Math.min(MAX_COAL, left);
            left -= out[i];
        }
        if (out.length > 0) out[0] = left;
        return out;
    }

    // ---- reading a snapshot ----

    /** Indices of every engine cell in {@code cells}, in cell order: the order they burn in. */
    public static List<Integer> findEngines(ListTag cells) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            if (isEngineCell(cells.getCompound(i))) out.add(i);
        }
        return out;
    }

    /** Index of the first engine cell in {@code cells} (the one that burns first), or -1 when there is none. */
    public static int findEngine(ListTag cells) {
        List<Integer> engines = findEngines(cells);
        return engines.isEmpty() ? -1 : engines.get(0);
    }

    static boolean isEngineCell(CompoundTag cell) {
        return ENGINE_ID.equals(cell.getCompound("s").getString("Name"));
    }

    private static int engineCoal(CompoundTag cell) {
        return clampCoal(cell.getCompound("b").getInt(TAG_COAL));
    }

    /** Coal in all the build's engines together, or -1 when it has none. */
    public static int coalIn(CompoundTag snap) {
        ListTag cells = snap.getList("cells", Tag.TAG_COMPOUND);
        List<Integer> engines = findEngines(cells);
        if (engines.isEmpty()) return -1;
        int total = 0;
        for (int i : engines) total += engineCoal(cells.getCompound(i));
        return clampCoal(total);
    }

    /**
     * The coal this carriage arrives with, before this world's burn. The relay's count wins when it has
     * one ({@code relayFuel >= 0}) — a blob only changes when someone edits, so it can be older than the
     * count. With no count, the engines in the blob; with no engine either, a one-time retrofit.
     */
    public static int arrivingCoal(int relayFuel, CompoundTag snap, CompoundTag origin) {
        if (relayFuel >= 0) return clampCoal(relayFuel);
        int inBlob = coalIn(snap);
        if (inBlob >= 0) return inBlob;
        return retrofitCoal(origin, snap);
    }

    // ---- writing a snapshot ----

    /**
     * {@code snap} with one coal burnt from its first engine that still has any, which goes dark if that
     * was its last. Unchanged when every engine is empty or there is none. Never mutates the argument.
     */
    public static CompoundTag burnOne(CompoundTag snap) {
        CompoundTag out = snap.copy();
        ListTag cells = out.getList("cells", Tag.TAG_COMPOUND);
        for (int i : findEngines(cells)) {
            CompoundTag cell = cells.getCompound(i);
            int coal = engineCoal(cell);
            if (coal <= 0) continue;
            cell.put("b", engineTag(cell.getCompound("b"), coal - 1));
            setLit(cell.getCompound("s"), coal - 1 > 0);
            return out;
        }
        return snap;
    }

    /**
     * {@code snap} with its engines holding {@code coal} between them. Engines whose counts already add up
     * to it keep them; otherwise the total is shared out ({@link #share}). A build with no engine (built
     * before engines, or saved by a version that dropped the unknown block) gets one, holding it all, in
     * place of a block facing the room or else on a free floor cell ({@link EngineSpot}).
     * Returns {@code snap} unchanged if there is no engine and no place for one. Never mutates the
     * argument.
     */
    public static CompoundTag withEngineCoal(CompoundTag snap, int coal) {
        CompoundTag out = snap.copy();
        ListTag cells = out.getList("cells", Tag.TAG_COMPOUND);
        List<Integer> engines = findEngines(cells);
        int clamped = clampCoal(coal);
        if (!engines.isEmpty()) {
            int[] counts = new int[engines.size()];
            int sum = 0;
            for (int k = 0; k < counts.length; k++) {
                counts[k] = engineCoal(cells.getCompound(engines.get(k)));
                sum += counts[k];
            }
            if (sum != clamped) counts = share(clamped, counts.length);
            for (int k = 0; k < counts.length; k++) {
                CompoundTag cell = cells.getCompound(engines.get(k));
                cell.put("b", engineTag(cell.getCompound("b"), counts[k]));
                setLit(cell.getCompound("s"), counts[k] > 0);
            }
            return out;
        }
        int l = out.getInt("l"), h = out.getInt("h"), w = out.getInt("w");
        Occupancy occ = Occupancy.of(cells);
        EngineSpot.Spot spot = EngineSpot.find(l, h, w, occ::solid, occ::grade);
        if (spot == null) return snap;
        CompoundTag state = new CompoundTag();
        state.putString("Name", ENGINE_ID);
        CompoundTag props = new CompoundTag();
        props.putString("facing", spot.facing());
        props.putString("lit", clamped > 0 ? "true" : "false");
        state.put("Properties", props);
        CompoundTag cell = spot.replaces() ? cells.getCompound(occ.index(spot.x(), spot.y(), spot.z())) : new CompoundTag();
        cell.put("p", new IntArrayTag(spot.pos()));
        cell.put("s", state);
        cell.put("b", engineTag(new CompoundTag(), clamped));
        if (!spot.replaces()) cells.add(cell);
        out.put("cells", cells);
        return out;
    }

    private static CompoundTag engineTag(CompoundTag existing, int coal) {
        CompoundTag b = existing.copy();
        b.putString("id", BLOCK_ENTITY_ID);
        b.putInt(TAG_COAL, coal);
        b.remove(TAG_INPUT); // a new world: everything is banked, the input starts empty
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
        return Math.min(MAX_COAL, FRESH_COAL + blocks + items);
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

    /** Which cells of a snapshot hold a block, and how readily the engine may take each one's place. */
    private record Occupancy(Map<Long, Integer> index, Map<Long, Integer> grades) {
        static Occupancy of(ListTag cells) {
            Map<Long, Integer> index = new HashMap<>();
            Map<Long, Integer> grades = new HashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                CompoundTag cell = cells.getCompound(i);
                int[] p = cell.getIntArray("p");
                if (p.length != 3) continue;
                index.put(key(p), i);
                // A block entity (a chest, a sign) is never building material, whatever it is called.
                if (!cell.contains("b")) grades.put(key(p), EngineSpot.grade(cell.getCompound("s").getString("Name")));
            }
            return new Occupancy(index, grades);
        }

        boolean solid(int x, int y, int z) {
            return index.containsKey(key(x, y, z));
        }

        int grade(int x, int y, int z) {
            return grades.getOrDefault(key(x, y, z), EngineSpot.NONE);
        }

        int index(int x, int y, int z) {
            return index.get(key(x, y, z));
        }
    }
}
