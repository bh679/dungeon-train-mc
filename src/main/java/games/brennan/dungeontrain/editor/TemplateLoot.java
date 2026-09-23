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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    public enum Source { POOL, PREFAB, INLINE, TABLE }

    /**
     * One kind of loot block, and how many of it the template has.
     *
     * @param detail   the prefab id or loot table id, or empty
     * @param chance   percent chance the block is there at all: below 100 only for a variant
     * @param value    the best {@link LootValue} among the grouped blocks, already weighted by chance
     * @param topItems the best items it can give, best first; empty for a vanilla loot table
     */
    public record LootBlock(Block block, int count, Source source, String detail, int chance,
                            double value, List<Item> topItems) {
        public LootBlock {
            topItems = List.copyOf(topItems);
            detail = detail == null ? "" : detail;
        }

        /** True when the block only appears through a variant, so it may not spawn. */
        public boolean isVariant() {
            return chance < 100;
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
            Found f = judge(info.state(), info.nbt(), null, store, info.pos(), 100);
            if (f != null) found.add(f);
        }
        for (CarriageVariantBlocks.Entry e : variants) {
            int total = 0;
            for (VariantState s : e.states()) total += s.weight();
            for (VariantState s : e.states()) {
                int chance = total <= 0 ? 100 : Math.max(1, Math.round(100f * s.weight() / total));
                Found f = judge(s.state(), s.blockEntityNbt(), s.linkedLootPrefabId(), store,
                    e.localPos(), chance);
                if (f != null) found.add(f);
            }
        }
        return group(found);
    }

    /** One block before grouping. */
    private record Found(Block block, Source source, String detail, int chance, double value,
                         List<Item> topItems) {}

    /** What one block (or variant candidate) at {@code pos} would be given, or null for no loot. */
    private static Found judge(BlockState state, @Nullable CompoundTag nbt, @Nullable String prefabLink,
                               @Nullable ContainerContentsStore store, BlockPos pos, int chance) {
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
                    return new Found(block, Source.PREFAB, prefabLink, chance,
                        weight * LootValue.poolValue(pool, slots), LootValue.topItems(pool));
                }
            }
            if (store != null && store.hasPoolAt(pos)) {
                ContainerContentsPool pool = store.poolAt(pos);
                String link = store.linkAt(pos);
                return new Found(block, link == null ? Source.POOL : Source.PREFAB, link, chance,
                    weight * LootValue.poolValue(pool, slots), LootValue.topItems(pool));
            }
        }
        if (nbt != null && nbt.contains(NBT_LOOT_TABLE, Tag.TAG_STRING)) {
            return new Found(block, Source.TABLE, nbt.getString(NBT_LOOT_TABLE), chance,
                weight * LootValue.UNKNOWN_TABLE, List.of());
        }
        List<ItemStack> stacks = savedStacks(nbt);
        if (!stacks.isEmpty()) {
            return new Found(block, Source.INLINE, "", chance,
                weight * LootValue.stacksValue(stacks), LootValue.topStacks(stacks));
        }
        if (brushable) {
            // Stamping gives a bare brushable block a vanilla archaeology table.
            return new Found(block, Source.TABLE, ARCHAEOLOGY, chance,
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
     * Same block, same source, same chance: one entry with a count. The group keeps its most
     * valuable member's value, so the order reads "which chest is best", not "how many chests".
     */
    static List<LootBlock> group(List<Found> found) {
        Map<List<Object>, List<Found>> groups = new LinkedHashMap<>();
        for (Found f : found) {
            groups.computeIfAbsent(List.of(f.block(), f.source(), f.detail() == null ? "" : f.detail(),
                f.chance()), k -> new ArrayList<>()).add(f);
        }
        List<LootBlock> out = new ArrayList<>(groups.size());
        for (List<Found> members : groups.values()) {
            members.sort(Comparator.comparingDouble(Found::value).reversed());
            Found best = members.get(0);
            Set<Item> top = new LinkedHashSet<>();
            for (Found m : members) {
                for (Item i : m.topItems()) if (top.size() < LootValue.TOP_ITEMS) top.add(i);
            }
            out.add(new LootBlock(best.block(), members.size(), best.source(), best.detail(),
                best.chance(), best.value(), List.copyOf(top)));
        }
        out.sort(Comparator.comparingDouble(LootBlock::value).reversed()
            .thenComparing(Comparator.comparingInt(LootBlock::count).reversed()));
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
