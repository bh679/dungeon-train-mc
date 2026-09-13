package games.brennan.dungeontrain.block.stage;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The vanilla stone families a stage can resolve its stone placeholders to — the stone-side
 * counterpart of {@link StageWoodFamily}. Each family names up to seven {@link StoneKind}s
 * (Cobbled, Stone, Bricks, Polished, Cracked, Mossy, Feature); a kind a family lacks walks the
 * {@link StoneKind#fallback()} chain (Cracked→Cobbled, Mossy→Cracked, Feature→Polished,
 * Polished→Stone, Bricks→Stone, Cobbled↔Stone). Stairs / slab / wall of a kind are the
 * family-spelled siblings of that kind's block ({@link BlockFamilySpelling}), walking the same
 * chain when a kind's block has no such shape.
 */
public enum StageStoneFamily {
    STONE("stone", "cobblestone", "stone", "stone_bricks", "smooth_stone",
        "cracked_stone_bricks", "mossy_stone_bricks", "chiseled_stone_bricks"),
    DEEPSLATE("deepslate", "cobbled_deepslate", "deepslate", "deepslate_bricks", "polished_deepslate",
        "cracked_deepslate_bricks", null, "chiseled_deepslate", "deepslate_tiles", "cracked_deepslate_tiles"),
    BLACKSTONE("blackstone", "blackstone", "blackstone", "polished_blackstone_bricks", "polished_blackstone",
        "cracked_polished_blackstone_bricks", null, "chiseled_polished_blackstone", "gilded_blackstone"),
    TUFF("tuff", "tuff", "tuff", "tuff_bricks", "polished_tuff", null, null, "chiseled_tuff_bricks"),
    SANDSTONE("sandstone", "sandstone", "sandstone", "cut_sandstone", "smooth_sandstone",
        null, null, "chiseled_sandstone"),
    RED_SANDSTONE("red_sandstone", "red_sandstone", "red_sandstone", "cut_red_sandstone", "smooth_red_sandstone",
        null, null, "chiseled_red_sandstone"),
    NETHER_BRICK("nether_brick", "netherrack", "netherrack", "nether_bricks", "red_nether_bricks",
        "cracked_nether_bricks", null, "chiseled_nether_bricks"),
    QUARTZ("quartz", "quartz_block", "quartz_block", "quartz_bricks", "smooth_quartz",
        null, null, "chiseled_quartz_block", "quartz_pillar"),
    PRISMARINE("prismarine", "prismarine", "prismarine", "prismarine_bricks", "dark_prismarine",
        null, null, null),
    END_STONE("end_stone", "end_stone", "end_stone", "end_stone_bricks", null, null, null, null),
    MUD("mud", "packed_mud", "mud", "mud_bricks", null, null, null, null),
    ANDESITE("andesite", null, "andesite", null, "polished_andesite", null, null, null),
    DIORITE("diorite", null, "diorite", null, "polished_diorite", null, null, null),
    GRANITE("granite", null, "granite", null, "polished_granite", null, null, null),
    PURPUR("purpur", "purpur_block", "purpur_block", null, null, null, null, "purpur_pillar");

    /** The seven stone kinds, in placeholder order. */
    public enum StoneKind {
        COBBLED("cobbled"), STONE("stone"), BRICKS("bricks"), POLISHED("polished"),
        CRACKED("cracked"), MOSSY("mossy"), FEATURE("feature");

        private final String id;

        StoneKind(String id) {
            this.id = id;
        }

        /** Placeholder-name segment ({@code stage_stone_<id>}). */
        public String id() {
            return id;
        }

        /** The kind used when a family lacks this one. */
        public StoneKind fallback() {
            return switch (this) {
                case CRACKED -> COBBLED;
                case MOSSY -> CRACKED;
                case FEATURE -> POLISHED;
                case POLISHED, BRICKS, COBBLED -> STONE;
                case STONE -> COBBLED;
            };
        }
    }

    /** The three derived shapes of a kind. */
    public enum Shape {
        STAIRS("_stairs", "minecraft:stone_stairs"),
        SLAB("_slab", "minecraft:stone_slab"),
        WALL("_wall", "minecraft:cobblestone_wall");

        final String suffix;
        final String lastResort;

        Shape(String suffix, String lastResort) {
            this.suffix = suffix;
            this.lastResort = lastResort;
        }
    }

    /** Family used when a stage has no stone family block in its tally at all. */
    public static final StageStoneFamily FALLBACK = STONE;

    private static final String NS = "minecraft:";
    private static final String LAST_RESORT_BLOCK = NS + "stone";

    private final String id;
    private final Map<StoneKind, String> blocks = new EnumMap<>(StoneKind.class);
    /** Blocks that identify the family in a tally but are no kind of their own (deepslate tiles…). */
    private final Set<String> extraMembers = new LinkedHashSet<>();

    StageStoneFamily(String id, String cobbled, String stone, String bricks, String polished,
                     String cracked, String mossy, String feature, String... extras) {
        this.id = id;
        for (String e : extras) extraMembers.add(NS + e);
        put(StoneKind.COBBLED, cobbled);
        put(StoneKind.STONE, stone);
        put(StoneKind.BRICKS, bricks);
        put(StoneKind.POLISHED, polished);
        put(StoneKind.CRACKED, cracked);
        put(StoneKind.MOSSY, mossy);
        put(StoneKind.FEATURE, feature);
    }

    private void put(StoneKind kind, String name) {
        if (name != null) blocks.put(kind, NS + name);
    }

    /** Lowercase family id — the {@code "stone"} value persisted in a stage palette. */
    public String id() {
        return id;
    }

    /** The block this family names for {@code kind} itself, or null when it has none. */
    public String own(StoneKind kind) {
        return blocks.get(kind);
    }

    /** Block for {@code kind}, walking the fallback chain; {@code minecraft:stone} as a last resort. */
    public String block(StoneKind kind) {
        StoneKind k = kind;
        for (int i = 0; i < StoneKind.values().length; i++) {
            String b = blocks.get(k);
            if (b != null) return b;
            k = k.fallback();
        }
        return LAST_RESORT_BLOCK;
    }

    /**
     * Stairs / slab / wall for {@code kind}: the family-spelled sibling of the first block along
     * the fallback chain that has one ({@code exists} answers registry membership).
     */
    public String shape(StoneKind kind, Shape shape, Predicate<String> exists) {
        StoneKind k = kind;
        for (int i = 0; i < StoneKind.values().length; i++) {
            String b = blocks.get(k);
            if (b != null) {
                String v = BlockFamilySpelling.variantOf(b, shape.suffix, exists);
                if (v != null) return v;
            }
            k = k.fallback();
        }
        return shape.lastResort;
    }

    /** Every block id that identifies this family: its own kinds plus their existing shapes. */
    public Set<String> members(Predicate<String> exists) {
        Set<String> out = new LinkedHashSet<>(blocks.values());
        out.addAll(extraMembers);
        for (String b : out.toArray(new String[0])) {
            for (Shape s : Shape.values()) {
                String v = BlockFamilySpelling.variantOf(b, s.suffix, exists);
                if (v != null) out.add(v);
            }
        }
        return out;
    }

    /** The family whose {@link #id()} is {@code id} (case-insensitive), if any. */
    public static Optional<StageStoneFamily> byId(String id) {
        if (id == null) return Optional.empty();
        String key = id.trim().toLowerCase(Locale.ROOT);
        for (StageStoneFamily f : values()) {
            if (f.id.equals(key)) return Optional.of(f);
        }
        return Optional.empty();
    }

    /**
     * The family owning {@code blockId}, if any. Declaration order decides ties (blackstone's
     * {@code polished_blackstone*} never collides with stone's; {@code netherrack} is nether brick's).
     */
    public static Optional<StageStoneFamily> owning(String blockId, Predicate<String> exists) {
        if (blockId == null) return Optional.empty();
        for (StageStoneFamily f : values()) {
            if (f.members(exists).contains(blockId)) return Optional.of(f);
        }
        return Optional.empty();
    }
}
