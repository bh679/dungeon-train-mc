package games.brennan.dungeontrain.editor;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageVariant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The blocks in a template that hand out loot, most valuable first — what the editor's Loot row
 * shows.
 *
 * <p>A template's own NBT rarely carries its loot. The roller fills a container at stamp time from,
 * in order: the {@link ContainerContentsStore} entry at its position (an authored pool or a link to
 * a {@link LootPrefabStore} prefab), else whatever the template saved in the block itself (an
 * {@code Items} list, a vanilla {@code LootTable}). A block variant can put a container in a cell
 * the template leaves as something else, carrying its own prefab link or NBT. This walks all three
 * the same way {@link ContainerContentsRoller#rollForPlacement} reads them, without rolling.</p>
 *
 * <p>Common code with no level: every store it reads answers from disk, so the builder's client
 * can call it next to where it tallies {@link TemplateCells#tallyBlockEntities}.</p>
 */
public final class TemplateLoot {

    /** Where a block's loot comes from — what its tooltip says. */
    public enum Source { POOL, PREFAB, INLINE, TABLE, DEFAULT }

    /**
     * One kind of loot block, and how many of it the template has.
     *
     * @param detail   the prefab id or loot table id, or empty
     * @param chance   percent chance the block ends up holding this loot: a variant's chance of
     *                 being placed, times a {@link Source#DEFAULT}'s chance of rolling
     * @param variant  whether the block only appears through a block variant, so may not spawn
     * @param value    the best {@link LootValue} among the grouped blocks, already weighted by chance
     * @param total    the grouped blocks' values summed — this kind's share of the template's loot
     * @param items    every item it can give, scored, best first; empty for a vanilla loot table
     */
    public record LootBlock(Block block, int count, Source source, String detail, int chance,
                            boolean variant, double value, double total,
                            List<LootValue.Scored> items) {
        public LootBlock {
            items = List.copyOf(items);
            detail = detail == null ? "" : detail;
        }

        /** The {@link LootValue#TOP_ITEMS} best items, for a tooltip's row of icons. */
        public List<Item> topItems() {
            return items.stream().limit(LootValue.TOP_ITEMS).map(LootValue.Scored::item).toList();
        }


        /** True when the block only appears through a variant, so it may not spawn. */
        public boolean isVariant() {
            return variant;
        }
    }

    /** The table a brushable block without its own NBT is given at stamp time. */
    static final String ARCHAEOLOGY = "archaeology";

    private static final String NBT_ITEMS = "Items";
    private static final String NBT_ITEM = "item";
    private static final String NBT_LOOT_TABLE = "LootTable";

    private TemplateLoot() {}

    /**
     * The loot blocks of the template {@code id} of {@code kind}, reading its containers store and
     * block-variant sidecar from disk.
     */
    public static List<LootBlock> of(StructureTemplate template, BuilderPhotoPaths.Kind kind,
                                     @Nullable String subKind, String id) {
        String plotKey = TemplateSidecars.plotKeyFor(kind, subKind, id);
        ContainerContentsStore store = plotKey == null ? null : ContainerContentsStore.loadFor(plotKey);
        return scan(TemplateCells.blockInfos(template), store, variantEntries(kind, subKind, id));
    }

    /** The loot blocks of a template with no sidecars — a relay build, a captured stage. */
    public static List<LootBlock> of(StructureTemplate template) {
        return scan(TemplateCells.blockInfos(template), null, List.of());
    }

    /**
     * The walk itself, over blocks already read out.
     *
     * <p>A cell with variants is judged by its candidates alone, since one of them replaces the
     * template's block there.</p>
     */
    public static List<LootBlock> scan(List<StructureTemplate.StructureBlockInfo> blocks,
                                       @Nullable ContainerContentsStore store,
                                       List<CarriageVariantBlocks.Entry> variants) {
        Map<BlockPos, CarriageVariantBlocks.Entry> variantAt = new HashMap<>();
        for (CarriageVariantBlocks.Entry e : variants) variantAt.put(e.localPos(), e);

        List<Found> found = new ArrayList<>();
        for (StructureTemplate.StructureBlockInfo info : blocks) {
            if (variantAt.containsKey(info.pos())) continue;
            Found f = judge(info.state(), info.nbt(), null, store, info.pos(), 100, false);
            if (f != null) found.add(f);
        }
        for (CarriageVariantBlocks.Entry e : variants) {
            int total = 0;
            for (VariantState s : e.states()) total += s.weight();
            List<Found> cell = new ArrayList<>();
            Map<Block, Integer> weightByBlock = new HashMap<>();
            for (VariantState s : e.states()) weightByBlock.merge(s.state().getBlock(), s.weight(), Integer::sum);
            for (VariantState s : e.states()) {
                int chance = total <= 0 ? 100 : Math.max(1, Math.round(100f * s.weight() / total));
                Found f = judge(s.state(), s.blockEntityNbt(), s.linkedLootPrefabId(), store,
                    e.localPos(), chance, true);
                if (f != null) cell.add(f);
            }
            found.addAll(mergeCell(cell, weightByBlock, total));
        }
        return group(found);
    }

    /**
     * One cell's candidates, one per block: three chest candidates with different prefabs are one
     * chest that is there at their summed chance, worth their summed (already chance-weighted)
     * value, described by the most valuable of them.
     */
    private static List<Found> mergeCell(List<Found> candidates, Map<Block, Integer> weightByBlock,
                                         int totalWeight) {
        Map<Block, List<Found>> byBlock = new LinkedHashMap<>();
        for (Found f : candidates) byBlock.computeIfAbsent(f.block(), k -> new ArrayList<>()).add(f);
        List<Found> out = new ArrayList<>(byBlock.size());
        for (List<Found> same : byBlock.values()) {
            same.sort(Comparator.comparingDouble(Found::value).reversed());
            Found best = same.get(0);
            // From the summed weights, not the summed rounded percents, so 1+1+1 of 3 reads 100.
            int weight = weightByBlock.getOrDefault(best.block(), 0);
            int chance = totalWeight <= 0 ? 100 : Math.max(1, Math.round(100f * weight / totalWeight));
            double value = 0;
            List<LootValue.Scored> items = new ArrayList<>();
            for (Found f : same) {
                value += f.value();
                items.addAll(f.items());
            }
            // Every candidate the same block means it is always there, whichever one is picked.
            int capped = Math.min(100, chance);
            out.add(new Found(best.block(), best.source(), best.detail(), capped,
                best.variant() && capped < 100, value, LootValue.merged(items)));
        }
        return out;
    }

    /** One block before grouping. */
    private record Found(Block block, Source source, String detail, int chance, boolean variant,
                         double value, List<LootValue.Scored> items) {}

    /** What one block (or variant candidate) at {@code pos} would be given, or null for no loot. */
    private static Found judge(BlockState state, @Nullable CompoundTag nbt, @Nullable String prefabLink,
                               @Nullable ContainerContentsStore store, BlockPos pos, int chance,
                               boolean variant) {
        if (state == null || state.isAir()) return null;
        boolean brushable = ContainerContentsRoller.isBrushable(state);
        if (!brushable && !ContainerContentsRoller.isContainerState(state)) return null;
        Block block = state.getBlock();
        double weight = chance / 100.0;

        if (!brushable) {
            int slots = ContainerContentsRoller.slotsForContainer(state);
            if (prefabLink != null) {
                ContainerContentsPool pool = LootPrefabStore.load(prefabLink)
                    .map(LootPrefabStore.Data::pool).orElse(null);
                if (pool != null && !pool.isEmpty()) {
                    return new Found(block, Source.PREFAB, prefabLink, chance, variant,
                        weight * LootValue.poolValue(pool, slots), LootValue.items(pool));
                }
            }
            if (store != null && store.hasPoolAt(pos)) {
                ContainerContentsPool pool = store.poolAt(pos);
                String link = store.linkAt(pos);
                return new Found(block, link == null ? Source.POOL : Source.PREFAB, link, chance, variant,
                    weight * LootValue.poolValue(pool, slots), LootValue.items(pool));
            }
        }
        if (nbt != null && nbt.contains(NBT_LOOT_TABLE, Tag.TAG_STRING)) {
            return new Found(block, Source.TABLE, nbt.getString(NBT_LOOT_TABLE), chance, variant,
                weight * LootValue.UNKNOWN_TABLE, List.of());
        }
        List<ItemStack> stacks = savedStacks(nbt);
        if (!stacks.isEmpty()) {
            return new Found(block, Source.INLINE, "", chance, variant,
                weight * LootValue.stacksValue(stacks), LootValue.stackItems(stacks));
        }
        String fallback = brushable ? null : BlockLootDefaults.prefabFor(state);
        if (fallback != null) {
            // An empty container of a covered type rolls this prefab one time in five.
            ContainerContentsPool pool = LootPrefabStore.load(fallback)
                .map(LootPrefabStore.Data::pool).orElse(null);
            if (pool != null && !pool.isEmpty()) {
                double odds = BlockLootDefaults.chancePct() / 100.0;
                return new Found(block, Source.DEFAULT, fallback,
                    Math.max(1, Math.round(chance * (float) odds)), variant,
                    weight * odds * LootValue.poolValue(pool, ContainerContentsRoller.slotsForContainer(state)),
                    LootValue.items(pool));
            }
        }
        if (brushable) {
            // Stamping gives a bare brushable block a vanilla archaeology table.
            return new Found(block, Source.TABLE, ARCHAEOLOGY, chance, variant,
                weight * LootValue.UNKNOWN_TABLE, List.of());
        }
        return null;
    }

    /**
     * The stacks a block entity already holds: an {@code Items} list, or the single {@code item} a
     * decorated pot or brushable block keeps. Read by id and count alone — enough to rank them.
     */
    static List<ItemStack> savedStacks(@Nullable CompoundTag nbt) {
        if (nbt == null) return List.of();
        List<ItemStack> out = new ArrayList<>();
        if (nbt.contains(NBT_ITEMS, Tag.TAG_LIST)) {
            ListTag items = nbt.getList(NBT_ITEMS, Tag.TAG_COMPOUND);
            for (int i = 0; i < items.size(); i++) addStack(out, items.getCompound(i));
        }
        if (nbt.contains(NBT_ITEM, Tag.TAG_COMPOUND)) addStack(out, nbt.getCompound(NBT_ITEM));
        return out;
    }

    private static void addStack(List<ItemStack> out, CompoundTag tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("id"));
        if (id == null) return;
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) return;
        // 1.21 writes "count" as an int; older saves wrote "Count" as a byte.
        int count = tag.contains("count") ? tag.getInt("count") : tag.getByte("Count");
        out.add(new ItemStack(item, Math.max(1, count)));
    }

    /**
     * Same block, and either always there or only a variant: one icon with a count. Twelve chests
     * with three different prefabs still read as one chest icon; the group keeps its most valuable
     * member's source and value, so the order reads "which chest is best", not "how many chests",
     * and the best chance any member has.
     */
    static List<LootBlock> group(List<Found> found) {
        Map<List<Object>, List<Found>> groups = new LinkedHashMap<>();
        for (Found f : found) {
            groups.computeIfAbsent(List.of(f.block(), f.variant()), k -> new ArrayList<>()).add(f);
        }
        List<LootBlock> out = new ArrayList<>(groups.size());
        for (List<Found> members : groups.values()) {
            members.sort(Comparator.comparingDouble(Found::value).reversed());
            Found best = members.get(0);
            List<LootValue.Scored> items = new ArrayList<>();
            double total = 0;
            int chance = 0;
            for (Found m : members) {
                items.addAll(m.items());
                total += m.value();
                chance = Math.max(chance, m.chance());
            }
            // Each member is one cell: judge yields one per template block, mergeCell one per block per cell.
            out.add(new LootBlock(best.block(), members.size(), best.source(), best.detail(),
                chance, best.variant(), best.value(), total, LootValue.merged(items)));
        }
        out.sort(Comparator.comparingDouble(LootBlock::value).reversed()
            .thenComparing(Comparator.comparingInt(LootBlock::count).reversed()));
        return List.copyOf(out);
    }

    /** The template's whole loot value: every loot block's chance-weighted value, summed. */
    public static double totalValue(List<LootBlock> loot) {
        double total = 0;
        for (LootBlock b : loot) total += b.total();
        return total;
    }

    /** One item the template can give: its score and the loot blocks it can turn up in. */
    public record ItemEntry(Item item, double score, List<LootBlock> sources) {
        public ItemEntry {
            sources = List.copyOf(sources);
        }
    }

    /** Every distinct item across {@code loot}, most valuable first, each with the blocks that hold it. */
    public static List<ItemEntry> allItems(List<LootBlock> loot) {
        Map<Item, Double> score = new LinkedHashMap<>();
        Map<Item, List<LootBlock>> sources = new LinkedHashMap<>();
        for (LootBlock b : loot) {
            for (LootValue.Scored s : b.items()) {
                score.merge(s.item(), s.score(), Math::max);
                sources.computeIfAbsent(s.item(), k -> new ArrayList<>()).add(b);
            }
        }
        List<ItemEntry> out = new ArrayList<>(score.size());
        score.forEach((item, sc) -> out.add(new ItemEntry(item, sc, sources.get(item))));
        out.sort(Comparator.comparingDouble(ItemEntry::score).reversed());
        return List.copyOf(out);
    }

    /** The block-variant cells of template {@code id}, unbounded; empty for a kind without them. */
    static List<CarriageVariantBlocks.Entry> variantEntries(BuilderPhotoPaths.Kind kind,
                                                            @Nullable String subKind, String id) {
        if (kind == null || id == null || id.isEmpty()) return List.of();
        try {
            return switch (kind) {
                case CARRIAGE -> {
                    CarriageVariant v = carriageVariant(id);
                    yield v == null ? List.of() : CarriageVariantBlocks.loadFor(v, null).entries();
                }
                case CONTENTS -> {
                    CarriageContents c = contents(id);
                    yield c == null ? List.of() : CarriageContentsVariantBlocks.loadFor(c, null).entries();
                }
                case PART -> {
                    CarriagePartKind part = CarriagePartKind.fromId(subKind);
                    yield part == null ? List.of() : CarriagePartVariantBlocks.loadFor(part, id, null).entries();
                }
                case TRACK -> {
                    TrackKind track = TrackKind.fromId(subKind);
                    yield track == null ? List.of() : TrackVariantBlocks.loadFor(track, id, null).entries();
                }
                case PORTAL_ROOM -> TrackVariantBlocks.loadFor(TrackKind.PORTAL_ROOM, id, null).entries();
                case CHUNK_FRAME -> games.brennan.dungeontrain.portal.chunkframe.ChunkFrameVariants.loadFor(id).entries();
                case CARRIAGE_GROUP -> List.of();
            };
        } catch (RuntimeException e) {
            // A malformed sidecar costs the Loot row its variants, not the sheet.
            return List.of();
        }
    }

    private static CarriageVariant carriageVariant(String id) {
        for (CarriagePlacer.CarriageType t : CarriagePlacer.CarriageType.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(id)) return CarriageVariant.of(t);
        }
        return CarriageVariant.NAME_PATTERN.matcher(id).matches() ? new CarriageVariant.Custom(id) : null;
    }

    private static CarriageContents contents(String id) {
        for (CarriageContents.ContentsType t : CarriageContents.ContentsType.values()) {
            if (t.name().toLowerCase(Locale.ROOT).equals(id)) return CarriageContents.of(t);
        }
        try {
            return new CarriageContents.Custom(id);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
