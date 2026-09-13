package games.brennan.dungeontrain.block.stage;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The vanilla wood families a stage can resolve its wood placeholders to — a Java port of the
 * {@code overworld} / {@code nether} / {@code BAMBOO} / {@code BAMBOO_MOSAIC} maps in
 * {@code scripts/parts/clone-wood-stage.py}, extended with the fence gate, door and trapdoor.
 *
 * <p>Each family answers {@link #block(WoodKind)} with a {@code minecraft:} block id. Nether woods
 * swap log/wood for stem/hyphae; bamboo has no log at all (both pillar kinds are the bamboo block);
 * bamboo mosaic keeps bamboo's fittings but swaps planks/stairs/slab for the mosaic set and inverts
 * the pillar blocks for contrast, exactly as the clone script does.</p>
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
    WARPED("warped");

    /** Placeholder kinds in the wood set — one per {@code stage_*} wood block. */
    public enum WoodKind {
        LOG, STRIPPED_LOG, WOOD, STRIPPED_WOOD, PLANKS, STAIRS, SLAB, FENCE, FENCE_GATE,
        BUTTON, PRESSURE_PLATE, DOOR, TRAPDOOR
    }

    /** Family used when a stage has no wood in its tally at all. */
    public static final StageWoodFamily FALLBACK = SPRUCE;

    private static final String NS = "minecraft:";

    private final String id;
    private final Map<WoodKind, String> blocks;

    StageWoodFamily(String id) {
        this.id = id;
        this.blocks = Map.copyOf(buildBlocks(id));
    }

    /** Lowercase family id — the {@code "wood"} value persisted in a stage palette. */
    public String id() {
        return id;
    }

    /** Fully-qualified block id for {@code kind} in this family. */
    public String block(WoodKind kind) {
        return blocks.get(kind);
    }

    /** True when {@code blockId} is one of this family's 13 blocks. */
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

    /** The family owning {@code blockId}, if any. Mosaic-only blocks resolve to {@link #BAMBOO_MOSAIC}. */
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
            default -> overworld(w);
        };
    }

    private static Map<WoodKind, String> overworld(String w) {
        return new java.util.EnumMap<>(Map.ofEntries(
            Map.entry(WoodKind.LOG, NS + w + "_log"),
            Map.entry(WoodKind.STRIPPED_LOG, NS + "stripped_" + w + "_log"),
            Map.entry(WoodKind.WOOD, NS + w + "_wood"),
            Map.entry(WoodKind.STRIPPED_WOOD, NS + "stripped_" + w + "_wood"),
            Map.entry(WoodKind.PLANKS, NS + w + "_planks"),
            Map.entry(WoodKind.STAIRS, NS + w + "_stairs"),
            Map.entry(WoodKind.SLAB, NS + w + "_slab"),
            Map.entry(WoodKind.FENCE, NS + w + "_fence"),
            Map.entry(WoodKind.FENCE_GATE, NS + w + "_fence_gate"),
            Map.entry(WoodKind.BUTTON, NS + w + "_button"),
            Map.entry(WoodKind.PRESSURE_PLATE, NS + w + "_pressure_plate"),
            Map.entry(WoodKind.DOOR, NS + w + "_door"),
            Map.entry(WoodKind.TRAPDOOR, NS + w + "_trapdoor")));
    }

    private static Map<WoodKind, String> nether(String w) {
        Map<WoodKind, String> m = overworld(w);
        m.put(WoodKind.LOG, NS + w + "_stem");
        m.put(WoodKind.STRIPPED_LOG, NS + "stripped_" + w + "_stem");
        m.put(WoodKind.WOOD, NS + w + "_hyphae");
        m.put(WoodKind.STRIPPED_WOOD, NS + "stripped_" + w + "_hyphae");
        return m;
    }

    private static Map<WoodKind, String> bamboo() {
        Map<WoodKind, String> m = overworld("bamboo");
        m.put(WoodKind.LOG, NS + "bamboo_block");
        m.put(WoodKind.STRIPPED_LOG, NS + "stripped_bamboo_block");
        m.put(WoodKind.WOOD, NS + "bamboo_block");
        m.put(WoodKind.STRIPPED_WOOD, NS + "stripped_bamboo_block");
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
