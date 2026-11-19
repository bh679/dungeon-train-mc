package games.brennan.dungeontrain.block.flag;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.StagePlacementScope;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The Dungeon Train Flag 1–8 placeholders: editor-only blocks a builder puts in a template, each
 * becoming a real banner with one of the {@link FlagDesigns} when the room rolls. Within one roll
 * every copy of Flag N shows the same design and no two flag numbers share one ({@link FlagRoll}).
 *
 * <p>Swapped in the same two places stage placeholders resolve: template cells by
 * {@code FlagPlaceholderProcessor}, variant cells by {@code ContainerContentsPlacement}. Outside a
 * {@link StagePlacementScope} (every editor stamp) a flag stays a flag, so saving a plot keeps it.</p>
 */
public final class FlagPlaceholders {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How many numbered flags there are. */
    public static final int COUNT = 8;

    /** The name every rolled flag banner carries (a brand name, so not translated). */
    public static final String BANNER_NAME = "Dungeon Train";

    private static final List<DeferredBlock<FlagPlaceholderBlock>> STANDING = new ArrayList<>();
    private static final List<DeferredBlock<FlagWallPlaceholderBlock>> WALL = new ArrayList<>();
    private static final List<DeferredItem<StandingAndWallBlockItem>> ITEMS = new ArrayList<>();

    /** A placeholder swapped for its banner: the banner state and its block-entity tag. */
    public record Resolved(BlockState state, CompoundTag nbt) {}

    private FlagPlaceholders() {}

    /** Register the standing + wall block and the one item of every flag number. */
    public static void register(DeferredRegister.Blocks blocks, DeferredRegister.Items items) {
        for (int n = 1; n <= COUNT; n++) {
            final int slot = n;
            DeferredBlock<FlagPlaceholderBlock> standing = blocks.register("flag_" + slot,
                () -> new FlagPlaceholderBlock(slot, properties()));
            DeferredBlock<FlagWallPlaceholderBlock> wall = blocks.register("wall_flag_" + slot,
                () -> new FlagWallPlaceholderBlock(slot, properties()));
            STANDING.add(standing);
            WALL.add(wall);
            ITEMS.add(items.register("flag_" + slot, () -> new StandingAndWallBlockItem(
                standing.get(), wall.get(), new Item.Properties(), Direction.DOWN)));
        }
    }

    private static BlockBehaviour.Properties properties() {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.WHITE_BANNER).noLootTable();
    }

    /** The flag items, Flag 1 first: the creative tab feed. */
    public static List<DeferredItem<StandingAndWallBlockItem>> items() {
        return Collections.unmodifiableList(ITEMS);
    }

    /** True when {@code state} is any standing or wall flag placeholder. */
    public static boolean isFlag(BlockState state) {
        Block block = state.getBlock();
        return block instanceof FlagPlaceholderBlock || block instanceof FlagWallPlaceholderBlock;
    }

    /** The flag number of a placeholder state, or 0 when it is not one. */
    public static int slotOf(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof FlagPlaceholderBlock f) return f.slot();
        if (block instanceof FlagWallPlaceholderBlock f) return f.slot();
        return 0;
    }

    /**
     * {@link #resolve} with the roll of the placement in scope on this thread, or {@code null} when
     * {@code state} is not a flag or no placement is in scope (an editor stamp keeps its flags).
     */
    @Nullable
    public static Resolved resolveInScope(BlockState state, HolderLookup.Provider registries, long worldSeed) {
        if (!isFlag(state)) return null;
        OptionalLong key = StagePlacementScope.flagRollKey(worldSeed);
        if (key.isEmpty()) return null;
        Resolved resolved = resolve(state, registries, key.getAsLong());
        if (resolved != null) StagePlacementScope.noteFlagResolved();
        return resolved;
    }

    /** The banner flag placeholder {@code state} becomes in roll {@code rollKey}; null if not a flag. */
    @Nullable
    public static Resolved resolve(BlockState state, HolderLookup.Provider registries, long rollKey) {
        int slot = slotOf(state);
        if (slot == 0) return null;
        List<FlagDesigns.Design> designs = FlagDesigns.all();
        FlagDesigns.Design design = designs.get(FlagRoll.designIndex(rollKey, slot, designs.size()));

        BlockState banner;
        if (state.getBlock() instanceof FlagPlaceholderBlock) {
            banner = BannerBlock.byColor(design.base()).defaultBlockState()
                .setValue(BannerBlock.ROTATION, state.getValue(FlagPlaceholderBlock.ROTATION));
        } else {
            Block wall = BuiltInRegistries.BLOCK.get(
                ResourceLocation.withDefaultNamespace(design.base().getName() + "_wall_banner"));
            banner = wall.defaultBlockState()
                .setValue(WallBannerBlock.FACING, state.getValue(FlagWallPlaceholderBlock.FACING));
        }
        return new Resolved(banner, bannerTag(design, registries));
    }

    private static CompoundTag bannerTag(FlagDesigns.Design design, HolderLookup.Provider registries) {
        HolderLookup.RegistryLookup<BannerPattern> patterns = registries.lookupOrThrow(Registries.BANNER_PATTERN);
        BannerPatternLayers.Builder builder = new BannerPatternLayers.Builder();
        for (FlagDesigns.Layer layer : design.layers()) {
            ResourceKey<BannerPattern> key = ResourceKey.create(Registries.BANNER_PATTERN,
                ResourceLocation.withDefaultNamespace(layer.pattern()));
            Optional<Holder.Reference<BannerPattern>> pattern = patterns.get(key);
            if (pattern.isEmpty()) {
                LOGGER.warn("[DungeonTrain] Flags: design {} uses unknown pattern '{}'; layer skipped.",
                    design.id(), layer.pattern());
                continue;
            }
            builder.add(pattern.get(), layer.color());
        }
        CompoundTag tag = new CompoundTag();
        Tag layers = BannerPatternLayers.CODEC
            .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), builder.build())
            .getOrThrow();
        tag.put("patterns", layers);
        tag.putString("CustomName", Component.Serializer.toJson(Component.literal(BANNER_NAME), registries));
        return tag;
    }
}
