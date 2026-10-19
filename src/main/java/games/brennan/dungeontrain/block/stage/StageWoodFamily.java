package games.brennan.dungeontrain.block.stage;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The wood families a stage can resolve its wood placeholders to — the vanilla woods (a Java port of the
 * {@code overworld} / {@code nether} / {@code BAMBOO} / {@code BAMBOO_MOSAIC} maps in
 * {@code scripts/parts/clone-wood-stage.py}, extended with the fence gate, door, trapdoor and leaves) plus
 * every complete wood set from the mods DT hard-depends on: VanillaBackport's pale oak, Biomes O' Plenty
 * and the two BetterX mods.
 *
 * <p>Each family answers {@link #block(WoodKind)} with a fully-qualified block id. Nether woods swap
 * log/wood for stem/hyphae; bamboo has no log at all (both pillar kinds are the bamboo block); bamboo
 * mosaic keeps bamboo's fittings but swaps planks/stairs/slab for the mosaic set and inverts the pillar
 * blocks for contrast, exactly as the clone script does. Leaves are the tree's own; the nether woods use
 * their fungus's wart block, and bamboo (which has none) the jungle's.</p>
 *
 * <p>Modded woods come in two naming {@link Style}s. {@code VANILLA} is the vanilla pattern under another
 * namespace (Biomes O' Plenty, and pale oak under {@code minecraft:}). {@code BCL} is BCLib's
 * ({@code <w>_stripped_log}, {@code <w>_bark} for wood, {@code <w>_gate}, {@code <w>_plate}). Kinds a mod
 * does not ship are filled per family — a stem-only wood uses its stem for every pillar kind, and a tree
 * without leaves gets its own cap or foliage block — so every kind always answers non-null (a null would
 * break the editor's palette sync).</p>
 *
 * <p>{@link #id()} is the bare wood name for {@code minecraft:} woods and the namespaced id otherwise
 * ({@code biomesoplenty:willow} vs {@code betternether:willow}); it is persisted in a stage palette and
 * sent as a ≤32-char string.</p>
 */
public enum StageWoodFamily {
    OAK("oak"),
    SPRUCE("spruce"),
    BIRCH("birch"),
    JUNGLE("jungle"),
    ACACIA("acacia"),
    DARK_OAK("dark_oak"),
    MANGROVE("mangrove"),
    CHERRY("cherry"),
    BAMBOO("bamboo"),
    BAMBOO_MOSAIC("bamboo_mosaic"),
    CRIMSON("crimson"),
    WARPED("warped"),

    // VanillaBackport — registers pale oak under minecraft:.
    PALE_OAK("pale_oak"),

    // Biomes O' Plenty.
    BOP_FIR(Mods.BOP, "fir", Style.VANILLA),
    BOP_PINE(Mods.BOP, "pine", Style.VANILLA),
    BOP_REDWOOD(Mods.BOP, "redwood", Style.VANILLA),
    BOP_MAHOGANY(Mods.BOP, "mahogany", Style.VANILLA),
    BOP_JACARANDA(Mods.BOP, "jacaranda", Style.VANILLA),
    BOP_PALM(Mods.BOP, "palm", Style.VANILLA),
    BOP_WILLOW(Mods.BOP, "willow", Style.VANILLA),
    BOP_DEAD(Mods.BOP, "dead", Style.VANILLA),
    BOP_MAGIC(Mods.BOP, "magic", Style.VANILLA),
    BOP_MAPLE(Mods.BOP, "maple", Style.VANILLA, Mods.BOP + ":red_maple_leaves"),
    BOP_UMBRAN(Mods.BOP, "umbran", Style.VANILLA),
    BOP_HELLBARK(Mods.BOP, "hellbark", Style.VANILLA),
    BOP_EMPYREAL(Mods.BOP, "empyreal", Style.VANILLA),

    // BetterNether.
    BN_ANCHOR_TREE(Mods.BN, "anchor_tree", Style.BCL),
    BN_GLOOMWOOD(Mods.BN, "gloomwood", Style.BCL),
    BN_GLOOMWOOD_DARK(Mods.BN, "gloomwood_dark", Style.BCL, Mods.BN + ":gloomwood_leaves"),
    BN_MUSHROOM_FIR(Mods.BN, "mushroom_fir", Style.BCL, "minecraft:brown_mushroom_block"),
    BN_NETHER_MUSHROOM(Mods.BN, "nether_mushroom", Style.BCL_STEM, "minecraft:red_mushroom_block"),
    BN_NETHER_REED(Mods.BN, "nether_reed", Style.BCL_STEM, Mods.BN + ":nether_reed_roof"),
    BN_NETHER_SAKURA(Mods.BN, "nether_sakura", Style.BCL),
    BN_RUBEUS(Mods.BN, "rubeus", Style.BCL),
    BN_STALAGNATE(Mods.BN, "stalagnate", Style.BCL, "minecraft:shroomlight"),
    BN_WART(Mods.BN, "wart", Style.BCL, "minecraft:nether_wart_block"),
    BN_WILLOW(Mods.BN, "willow", Style.BCL),

    // BetterEnd.
    BE_DRAGON_TREE(Mods.BE, "dragon_tree", Style.BCL),
    BE_HELIX_TREE(Mods.BE, "helix_tree", Style.BCL),
    BE_LACUGROVE(Mods.BE, "lacugrove", Style.BCL),
    BE_LUCERNIA(Mods.BE, "lucernia", Style.BCL),
    BE_PYTHADENDRON(Mods.BE, "pythadendron", Style.BCL),
    BE_TENANEA(Mods.BE, "tenanea", Style.BCL),
    BE_END_LOTUS(Mods.BE, "end_lotus", Style.BCL, Mods.BE + ":end_lotus_leaf"),
    BE_JELLYSHROOM(Mods.BE, "jellyshroom", Style.BCL, Mods.BE + ":jellyshroom_cap_purple"),
    BE_MOSSY_GLOWSHROOM(Mods.BE, "mossy_glowshroom", Style.BCL, Mods.BE + ":mossy_glowshroom_cap"),
    BE_UMBRELLA_TREE(Mods.BE, "umbrella_tree", Style.BCL, Mods.BE + ":umbrella_tree_membrane");

    /** Placeholder kinds in the wood set — one per {@code stage_*} wood block. */
    public enum WoodKind {
        LOG, STRIPPED_LOG, WOOD, STRIPPED_WOOD, PLANKS, STAIRS, SLAB, FENCE, FENCE_GATE,
        BUTTON, PRESSURE_PLATE, DOOR, TRAPDOOR, LEAVES
    }

    /** How a modded wood names its blocks. */
    enum Style {
        /** Vanilla's pattern under the mod's namespace. */
        VANILLA,
        /** BCLib's pattern: {@code <w>_stripped_log}, {@code <w>_bark}, {@code <w>_gate}, {@code <w>_plate}. */
        BCL,
        /** BCLib's pattern for a wood with only a stem — the stem stands in for every pillar kind. */
        BCL_STEM
    }

    /** Namespaces of the mods that contribute wood families. */
    private static final class Mods {
        static final String BOP = "biomesoplenty";
        static final String BN = "betternether";
        static final String BE = "betterend";
    }

    /** Family used when a stage has no wood in its tally at all. */
    public static final StageWoodFamily FALLBACK = SPRUCE;

    private static final String VANILLA_NS = "minecraft";
    private static final String NS = VANILLA_NS + ":";

    private final String id;
    private final String namespace;
    private final Map<WoodKind, String> blocks;

    /** A vanilla ({@code minecraft:}) wood. */
    StageWoodFamily(String id) {
        this.id = id;
        this.namespace = VANILLA_NS;
        this.blocks = Map.copyOf(buildBlocks(id));
    }

    StageWoodFamily(String namespace, String name, Style style) {
        this(namespace, name, style, null);
    }

    /** A modded wood; {@code leaves} replaces the {@code <w>_leaves} the mod does not ship (null keeps it). */
    StageWoodFamily(String namespace, String name, Style style, String leaves) {
        this.id = namespace + ":" + name;
        this.namespace = namespace;
        Map<WoodKind, String> m = style == Style.VANILLA
            ? overworld(namespace + ":", name)
            : bcl(namespace + ":", name, style == Style.BCL_STEM);
        if (leaves != null) m.put(WoodKind.LEAVES, leaves);
        this.blocks = Map.copyOf(m);
    }

    /** Family id — the {@code "wood"} value persisted in a stage palette. */
    public String id() {
        return id;
    }

    /** Namespace of the mod whose wood this is ({@code minecraft} for vanilla and pale oak). */
    public String namespace() {
        return namespace;
    }

    /** Fully-qualified block id for {@code kind} in this family. */
    public String block(WoodKind kind) {
        return blocks.get(kind);
    }

    /** True when {@code blockId} is one of this family's 14 blocks. */
    public boolean contains(String blockId) {
        return blockId != null && blocks.containsValue(blockId);
    }

    /** The family whose {@link #id()} is {@code id} (case-insensitive), if any. */
    public static Optional<StageWoodFamily> byId(String id) {
        if (id == null) return Optional.empty();
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (StageWoodFamily f : values()) {
            if (f.id.equals(key)) return Optional.of(f);
        }
        return Optional.empty();
    }

    /**
     * The family owning {@code blockId}, if any. Mosaic-only blocks resolve to {@link #BAMBOO_MOSAIC}.
     * A block shared as a fill (a wart or mushroom block standing in for leaves) resolves to the first
     * family listing it, so a vanilla family wins over a modded one.
     */
    public static Optional<StageWoodFamily> owning(String blockId) {
        if (blockId == null) return Optional.empty();
        // Mosaic shares most ids with bamboo — check it first so only the mosaic-specific blocks
        // (bamboo_mosaic, its stairs, its slab) land on the mosaic family.
        if (blockId.equals(NS + "bamboo_mosaic") || blockId.equals(NS + "bamboo_mosaic_stairs")
            || blockId.equals(NS + "bamboo_mosaic_slab")) {
            return Optional.of(BAMBOO_MOSAIC);
        }
        for (StageWoodFamily f : values()) {
            if (f == BAMBOO_MOSAIC) continue;
            if (f.contains(blockId)) return Optional.of(f);
        }
        return Optional.empty();
    }

    private static Map<WoodKind, String> buildBlocks(String w) {
        return switch (w) {
            case "crimson", "warped" -> nether(w);
            case "bamboo" -> bamboo();
            case "bamboo_mosaic" -> bambooMosaic();
            default -> overworld(NS, w);
        };
    }

    private static Map<WoodKind, String> overworld(String ns, String w) {
        return new EnumMap<>(Map.ofEntries(
            Map.entry(WoodKind.LOG, ns + w + "_log"),
            Map.entry(WoodKind.STRIPPED_LOG, ns + "stripped_" + w + "_log"),
            Map.entry(WoodKind.WOOD, ns + w + "_wood"),
            Map.entry(WoodKind.STRIPPED_WOOD, ns + "stripped_" + w + "_wood"),
            Map.entry(WoodKind.PLANKS, ns + w + "_planks"),
            Map.entry(WoodKind.STAIRS, ns + w + "_stairs"),
            Map.entry(WoodKind.SLAB, ns + w + "_slab"),
            Map.entry(WoodKind.FENCE, ns + w + "_fence"),
            Map.entry(WoodKind.FENCE_GATE, ns + w + "_fence_gate"),
            Map.entry(WoodKind.BUTTON, ns + w + "_button"),
            Map.entry(WoodKind.PRESSURE_PLATE, ns + w + "_pressure_plate"),
            Map.entry(WoodKind.DOOR, ns + w + "_door"),
            Map.entry(WoodKind.TRAPDOOR, ns + w + "_trapdoor"),
            Map.entry(WoodKind.LEAVES, ns + w + "_leaves")));
    }

    private static Map<WoodKind, String> bcl(String ns, String w, boolean stemOnly) {
        Map<WoodKind, String> m = new EnumMap<>(WoodKind.class);
        if (stemOnly) {
            String stem = ns + w + "_stem";
            m.put(WoodKind.LOG, stem);
            m.put(WoodKind.STRIPPED_LOG, stem);
            m.put(WoodKind.WOOD, stem);
            m.put(WoodKind.STRIPPED_WOOD, stem);
        } else {
            m.put(WoodKind.LOG, ns + w + "_log");
            m.put(WoodKind.STRIPPED_LOG, ns + w + "_stripped_log");
            m.put(WoodKind.WOOD, ns + w + "_bark");
            m.put(WoodKind.STRIPPED_WOOD, ns + w + "_stripped_bark");
        }
        m.put(WoodKind.PLANKS, ns + w + "_planks");
        m.put(WoodKind.STAIRS, ns + w + "_stairs");
        m.put(WoodKind.SLAB, ns + w + "_slab");
        m.put(WoodKind.FENCE, ns + w + "_fence");
        m.put(WoodKind.FENCE_GATE, ns + w + "_gate");
        m.put(WoodKind.BUTTON, ns + w + "_button");
        m.put(WoodKind.PRESSURE_PLATE, ns + w + "_plate");
        m.put(WoodKind.DOOR, ns + w + "_door");
        m.put(WoodKind.TRAPDOOR, ns + w + "_trapdoor");
        m.put(WoodKind.LEAVES, ns + w + "_leaves");
        return m;
    }

    private static Map<WoodKind, String> nether(String w) {
        Map<WoodKind, String> m = overworld(NS, w);
        m.put(WoodKind.LOG, NS + w + "_stem");
        m.put(WoodKind.STRIPPED_LOG, NS + "stripped_" + w + "_stem");
        m.put(WoodKind.WOOD, NS + w + "_hyphae");
        m.put(WoodKind.STRIPPED_WOOD, NS + "stripped_" + w + "_hyphae");
        // Huge fungi grow wart blocks where trees grow leaves; crimson's is plain nether wart.
        m.put(WoodKind.LEAVES, NS + (w.equals("crimson") ? "nether" : w) + "_wart_block");
        return m;
    }

    private static Map<WoodKind, String> bamboo() {
        Map<WoodKind, String> m = overworld(NS, "bamboo");
        m.put(WoodKind.LOG, NS + "bamboo_block");
        m.put(WoodKind.STRIPPED_LOG, NS + "stripped_bamboo_block");
        m.put(WoodKind.WOOD, NS + "bamboo_block");
        m.put(WoodKind.STRIPPED_WOOD, NS + "stripped_bamboo_block");
        // Bamboo has no leaves of its own; it grows in jungles.
        m.put(WoodKind.LEAVES, NS + "jungle_leaves");
        return m;
    }

    private static Map<WoodKind, String> bambooMosaic() {
        Map<WoodKind, String> m = bamboo();
        m.put(WoodKind.PLANKS, NS + "bamboo_mosaic");
        m.put(WoodKind.STAIRS, NS + "bamboo_mosaic_stairs");
        m.put(WoodKind.SLAB, NS + "bamboo_mosaic_slab");
        // Pillars inverted against the mosaic, as in clone-wood-stage.py.
        m.put(WoodKind.LOG, NS + "stripped_bamboo_block");
        m.put(WoodKind.STRIPPED_LOG, NS + "bamboo_block");
        m.put(WoodKind.WOOD, NS + "stripped_bamboo_block");
        m.put(WoodKind.STRIPPED_WOOD, NS + "bamboo_block");
        return m;
    }
}
