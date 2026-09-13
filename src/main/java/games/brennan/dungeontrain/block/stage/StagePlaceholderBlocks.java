package games.brennan.dungeontrain.block.stage;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.block.stage.StageStoneFamily.Shape;
import games.brennan.dungeontrain.block.stage.StageStoneFamily.StoneKind;
import games.brennan.dungeontrain.block.stage.StageWoodFamily.WoodKind;
import games.brennan.dungeontrain.editor.StageBlockReplacer;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.template.Stage;
import games.brennan.dungeontrain.template.StagePalette;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The <b>stage placeholder blocks</b> — mod blocks a builder places in a carriage part / contents
 * template that are swapped for a real block of whichever {@link Stage} the carriage lands in at
 * generation time, so one build serves every stage.
 *
 * <p>Fifty-seven blocks: ten solid slots ({@code stage_block_1..10}, the stage's most-used full
 * cubes, looping when a stage has fewer), two stairs and two slab slots derived from the first two
 * solids, a button and a pressure plate, the thirteen-block wood set ({@code stage_log} …
 * {@code stage_trapdoor}) resolved through the stage's {@link StageWoodFamily}, and the
 * twenty-eight-block stone set ({@code stage_stone_<kind>[_stairs|_slab|_wall]} for the seven
 * {@link StoneKind}s) resolved through its {@link StageStoneFamily}. Each placeholder
 * extends the matching vanilla base class so builders orient it normally and the block-state
 * properties carry over on swap via {@link StageBlockReplacer#transfer}.</p>
 *
 * <p>Which block each slot becomes is read from the stage's baked {@link StagePalette}
 * ({@code editor.StagePaletteBaker}); a stage with no palette, or no stage in scope, resolves through
 * {@link StagePalette#DEFAULT} so a placeholder never reaches a live world outside the editor. The
 * swap itself runs in {@code train.StagePlaceholderProcessor} (template stamps) and the three
 * variant-sidecar appliers (see {@link #resolve(BlockState, String)}).</p>
 */
public final class StagePlaceholderBlocks {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One placeholder: registry name, how it maps a palette to a target block id, and whether the
     * slot only <em>repeats</em> an earlier one under that palette (a looped read past the end of
     * the solid / stairs / slab list — the inventory dims those).
     */
    public record Placeholder(String name, Function<StagePalette, String> target,
                              Predicate<StagePalette> repeat) {
        public Placeholder(String name, Function<StagePalette, String> target) {
            this(name, target, pal -> false);
        }
    }

    private static final List<Placeholder> PLACEHOLDERS = buildPlaceholders();

    /** Registered blocks in {@link #PLACEHOLDERS} order (creative-tab order). */
    private static final List<DeferredBlock<Block>> BLOCKS = new ArrayList<>();
    private static final List<DeferredItem<BlockItem>> ITEMS = new ArrayList<>();

    /** {@code Block → Placeholder}, built lazily once registration has run. */
    private static volatile Map<Block, Placeholder> byBlock;

    private StagePlaceholderBlocks() {}

    // ---------------------------------------------------------------- registration

    /** Register every placeholder block + block item on the mod's deferred registers. */
    public static void register(DeferredRegister.Blocks blocks, DeferredRegister.Items items) {
        for (Placeholder p : PLACEHOLDERS) {
            DeferredBlock<Block> block = blocks.register(p.name(), factoryFor(p.name()));
            BLOCKS.add(block);
            ITEMS.add(items.register(p.name(), () -> new BlockItem(block.get(), new Item.Properties())));
        }
    }

    /** Registered block items in slot order — the creative tab feed. */
    public static List<DeferredItem<BlockItem>> items() {
        return Collections.unmodifiableList(ITEMS);
    }

    /** Registered blocks in slot order. */
    public static List<DeferredBlock<Block>> blocks() {
        return Collections.unmodifiableList(BLOCKS);
    }

    /** The catalogue in slot order — the Stage Palette panel's row feed. */
    public static List<Placeholder> placeholders() {
        return PLACEHOLDERS;
    }

    /**
     * What placeholder {@code p} becomes under {@code palette}: the user override when one is set,
     * else the derived slot value.
     */
    public static String effectiveTarget(Placeholder p, StagePalette palette) {
        StagePalette pal = palette == null ? StagePalette.DEFAULT : palette;
        String ov = pal.override(p.name());
        return ov != null ? ov : p.target().apply(pal);
    }

    /** True when {@code p} only repeats an earlier slot under {@code palette} (looped list read). */
    public static boolean isRepeat(Placeholder p, StagePalette palette) {
        return p.repeat().test(palette == null ? StagePalette.DEFAULT : palette);
    }

    /** All placeholder registry names (without namespace), slot order. */
    public static List<String> names() {
        List<String> out = new ArrayList<>(PLACEHOLDERS.size());
        for (Placeholder p : PLACEHOLDERS) out.add(p.name());
        return out;
    }

    // ---------------------------------------------------------------- queries

    /** True when {@code block} is one of the stage placeholders. */
    public static boolean isPlaceholder(Block block) {
        return block != null && index().containsKey(block);
    }

    /** True when {@code state} is a stage placeholder. */
    public static boolean isPlaceholder(BlockState state) {
        return state != null && isPlaceholder(state.getBlock());
    }

    /**
     * The real block state {@code state} becomes in stage {@code stageId}: the palette's block for
     * this slot with every shared block-state property copied over (stairs facing/half/shape, door
     * hinge/open, log axis, waterlogged …). Non-placeholders come back unchanged. A null / unknown
     * stage or an unbaked stage resolves through {@link StagePalette#DEFAULT}.
     */
    public static BlockState resolve(BlockState state, String stageId) {
        if (state == null) return null;
        Placeholder p = index().get(state.getBlock());
        if (p == null) return state;
        return resolve(state, p, paletteFor(stageId));
    }

    /** {@link #resolve(BlockState, String)} against an explicit palette. */
    public static BlockState resolve(BlockState state, StagePalette palette) {
        if (state == null) return null;
        Placeholder p = index().get(state.getBlock());
        if (p == null) return state;
        return resolve(state, p, palette == null ? StagePalette.DEFAULT : palette);
    }

    private static BlockState resolve(BlockState state, Placeholder p, StagePalette palette) {
        String id = effectiveTarget(p, palette);
        Block target = lookup(id);
        if (target == null) {
            // Hand-edited palette pointing at an unknown block — degrade to the built-in default
            // rather than stamping a placeholder into the world.
            LOGGER.warn("[DungeonTrain] Stage palette maps {} to unknown block '{}'; using default.",
                p.name(), id);
            target = lookup(p.target().apply(StagePalette.DEFAULT));
            if (target == null) return state;
        }
        return StageBlockReplacer.transfer(state, target);
    }

    /** The baked palette of {@code stageId}, or {@link StagePalette#DEFAULT}. */
    public static StagePalette paletteFor(String stageId) {
        if (stageId == null || stageId.isBlank()) return StagePalette.DEFAULT;
        return StageStore.get(stageId.toLowerCase(Locale.ROOT))
            .map(Stage::palette).orElse(StagePalette.DEFAULT);
    }

    private static Block lookup(String id) {
        if (id == null || id.isBlank()) return null;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) return null;
        return BuiltInRegistries.BLOCK.get(rl);
    }

    private static Map<Block, Placeholder> index() {
        Map<Block, Placeholder> map = byBlock;
        if (map == null) {
            synchronized (StagePlaceholderBlocks.class) {
                map = byBlock;
                if (map == null) {
                    map = new IdentityHashMap<>();
                    for (int i = 0; i < BLOCKS.size(); i++) {
                        map.put(BLOCKS.get(i).get(), PLACEHOLDERS.get(i));
                    }
                    byBlock = map;
                }
            }
        }
        return map;
    }

    // ---------------------------------------------------------------- catalogue

    private static List<Placeholder> buildPlaceholders() {
        List<Placeholder> out = new ArrayList<>();
        for (int i = 0; i < StagePalette.SOLID_SLOTS; i++) {
            final int slot = i;
            out.add(new Placeholder("stage_block_" + (i + 1), pal -> pal.solid(slot), pal -> slot >= pal.solid().size()));
        }
        for (int i = 0; i < StagePalette.STAIRS_SLOTS; i++) {
            final int slot = i;
            out.add(new Placeholder("stage_stairs_" + (i + 1), pal -> pal.stairs(slot), pal -> slot >= pal.stairs().size()));
        }
        for (int i = 0; i < StagePalette.SLAB_SLOTS; i++) {
            final int slot = i;
            out.add(new Placeholder("stage_slab_" + (i + 1), pal -> pal.slab(slot), pal -> slot >= pal.slabs().size()));
        }
        out.add(new Placeholder("stage_button", StagePalette::button));
        out.add(new Placeholder("stage_pressure_plate", StagePalette::pressurePlate));
        out.add(wood("stage_log", WoodKind.LOG));
        out.add(wood("stage_stripped_log", WoodKind.STRIPPED_LOG));
        out.add(wood("stage_wood", WoodKind.WOOD));
        out.add(wood("stage_stripped_wood", WoodKind.STRIPPED_WOOD));
        out.add(wood("stage_planks", WoodKind.PLANKS));
        out.add(wood("stage_wood_stairs", WoodKind.STAIRS));
        out.add(wood("stage_wood_slab", WoodKind.SLAB));
        out.add(wood("stage_fence", WoodKind.FENCE));
        out.add(wood("stage_fence_gate", WoodKind.FENCE_GATE));
        out.add(wood("stage_wood_button", WoodKind.BUTTON));
        out.add(wood("stage_wood_pressure_plate", WoodKind.PRESSURE_PLATE));
        out.add(wood("stage_door", WoodKind.DOOR));
        out.add(wood("stage_trapdoor", WoodKind.TRAPDOOR));
        for (StoneKind kind : StoneKind.values()) {
            String base = stoneName(kind);
            out.add(new Placeholder(base, pal -> pal.stoneFamily().block(kind)));
            out.add(new Placeholder(base + "_stairs", pal -> stoneShape(pal, kind, Shape.STAIRS)));
            out.add(new Placeholder(base + "_slab", pal -> stoneShape(pal, kind, Shape.SLAB)));
            out.add(new Placeholder(base + "_wall", pal -> stoneShape(pal, kind, Shape.WALL)));
        }
        return List.copyOf(out);
    }

    /** {@code stage_stone_<kind>}; the plain kind is {@code stage_stone} itself. */
    private static String stoneName(StoneKind kind) {
        return kind == StoneKind.STONE ? "stage_stone" : "stage_stone_" + kind.id();
    }

    private static String stoneShape(StagePalette pal, StoneKind kind, Shape shape) {
        return pal.stoneFamily().shape(kind, shape, id -> lookup(id) != null);
    }

    private static Placeholder wood(String name, WoodKind kind) {
        return new Placeholder(name, pal -> pal.woodFamily().block(kind));
    }

    /**
     * The vanilla base class for a placeholder — the same one the resolved block uses, so the
     * placeholder carries the same block-state properties and the same placement/orientation UX.
     */
    private static Supplier<Block> factoryFor(String name) {
        if (name.startsWith("stage_block_")) {
            return () -> new Block(stone());
        }
        if (name.startsWith("stage_stone")) {
            if (name.endsWith("_stairs")) return () -> new StairBlock(Blocks.STONE.defaultBlockState(), stone());
            if (name.endsWith("_slab")) return () -> new SlabBlock(stone());
            if (name.endsWith("_wall")) return () -> new WallBlock(copyOf(Blocks.COBBLESTONE_WALL));
            return () -> new Block(stone());
        }
        if (name.startsWith("stage_stairs_")) {
            return () -> new StairBlock(Blocks.STONE.defaultBlockState(), stone());
        }
        if (name.startsWith("stage_slab_")) {
            return () -> new SlabBlock(stone());
        }
        return switch (name) {
            case "stage_button" -> () -> new ButtonBlock(BlockSetType.STONE, 20, copyOf(Blocks.STONE_BUTTON));
            case "stage_pressure_plate" ->
                () -> new PressurePlateBlock(BlockSetType.STONE, copyOf(Blocks.STONE_PRESSURE_PLATE));
            case "stage_log", "stage_stripped_log", "stage_wood", "stage_stripped_wood" ->
                () -> new RotatedPillarBlock(copyOf(Blocks.OAK_LOG));
            case "stage_planks" -> () -> new Block(copyOf(Blocks.OAK_PLANKS));
            case "stage_wood_stairs" ->
                () -> new StairBlock(Blocks.OAK_PLANKS.defaultBlockState(), copyOf(Blocks.OAK_STAIRS));
            case "stage_wood_slab" -> () -> new SlabBlock(copyOf(Blocks.OAK_SLAB));
            case "stage_fence" -> () -> new FenceBlock(copyOf(Blocks.OAK_FENCE));
            case "stage_fence_gate" -> () -> new FenceGateBlock(WoodType.OAK, copyOf(Blocks.OAK_FENCE_GATE));
            case "stage_wood_button" -> () -> new ButtonBlock(BlockSetType.OAK, 30, copyOf(Blocks.OAK_BUTTON));
            case "stage_wood_pressure_plate" ->
                () -> new PressurePlateBlock(BlockSetType.OAK, copyOf(Blocks.OAK_PRESSURE_PLATE));
            case "stage_door" -> () -> new DoorBlock(BlockSetType.OAK, copyOf(Blocks.OAK_DOOR));
            case "stage_trapdoor" -> () -> new TrapDoorBlock(BlockSetType.OAK, copyOf(Blocks.OAK_TRAPDOOR));
            default -> throw new IllegalStateException("No factory for stage placeholder " + name);
        };
    }

    private static BlockBehaviour.Properties stone() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.STONE).noLootTable();
    }

    /** Full copy of a vanilla base's properties, minus its loot table (placeholders never drop). */
    private static BlockBehaviour.Properties copyOf(Block base) {
        return BlockBehaviour.Properties.ofFullCopy(base).noLootTable();
    }
}
