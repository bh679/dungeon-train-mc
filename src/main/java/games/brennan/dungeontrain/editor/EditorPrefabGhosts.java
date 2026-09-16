package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.block.prefab.PrefabAnchorBlock;
import games.brennan.dungeontrain.block.prefab.PrefabAnchorBlockEntity;
import games.brennan.dungeontrain.net.EditorPrefabGhostsPacket;
import games.brennan.dungeontrain.template.Template;
import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The prefab ghosts: for every bound prefab anchor standing in an editor plot, the blocks its prefab
 * would stamp there — the picture an author builds the parent around.
 *
 * <p>Same maths as {@code train.PrefabResolver}, without the writes: the prefab's local origin lands
 * on the anchor, local {@code +X} runs along the anchor's facing
 * ({@link PrefabAnchorBlock#rotationFor}), cells are clipped to the plot's box (what generation clips
 * to the parent), a {@code structure_void} cell is not in the template and so shows the plot's own
 * block, and an anchor <i>inside</i> the prefab ghosts its own prefab in turn — with the same
 * no-ancestor-repeat rule, so a cycle stops where the resolver would leave air.</p>
 *
 * <p>Anchors come from {@link PrefabAnchorIndex}, never from a block walk; a prefab's cells are read
 * once per design change off the template's own NBT ({@link StructureTemplate#save}) — the palettes
 * are private, and the tag is the one public, complete view of them.</p>
 */
public final class EditorPrefabGhosts {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Defensive stop only — the resolver's fuse, for the same reason. */
    private static final int FUSE = 64;

    /** One prefab's authored cells, in template-local coordinates. */
    private record Cell(BlockPos local, BlockState state, @Nullable String anchorTo) {}

    private record CachedCells(long generation, List<Cell> cells) {}

    private static final Map<String, CachedCells> CELLS = new ConcurrentHashMap<>();

    private EditorPrefabGhosts() {}

    /**
     * Every ghost cell in {@code level}'s editor plots, absolute positions. Empty when no anchor is
     * bound, or none stands in a plot of the resident category.
     */
    public static List<EditorPrefabGhostsPacket.Ghost> snapshot(ServerLevel level, CarriageDims dims) {
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        boolean capped = false;
        for (BlockPos anchorPos : PrefabAnchorIndex.anchorsIn(level.dimension())) {
            BlockState state = level.getBlockState(anchorPos);
            if (!PrefabAnchorBlock.isAnchor(state)) continue;
            BlockEntity be = level.getBlockEntity(anchorPos);
            String name = be instanceof PrefabAnchorBlockEntity a ? a.prefab() : "";
            if (name.isEmpty()) continue;
            BoundingBox clip = plotBoxAround(level, anchorPos, dims);
            if (clip == null) continue;
            ghost(level, dims, anchorPos, state.getValue(HorizontalDirectionalBlock.FACING), name, clip,
                List.of(), cells);
            if (cells.size() > EditorPrefabGhostsPacket.MAX_GHOSTS) { capped = true; break; }
        }
        if (capped) {
            LOGGER.warn("[DungeonTrain] Prefab ghosts: more than {} cells in view — snapshot truncated.",
                EditorPrefabGhostsPacket.MAX_GHOSTS);
        }
        List<EditorPrefabGhostsPacket.Ghost> out = new ArrayList<>(Math.min(cells.size(), EditorPrefabGhostsPacket.MAX_GHOSTS));
        for (Map.Entry<BlockPos, BlockState> e : cells.entrySet()) {
            if (out.size() >= EditorPrefabGhostsPacket.MAX_GHOSTS) break;
            out.add(new EditorPrefabGhostsPacket.Ghost(e.getKey(), e.getValue()));
        }
        return out;
    }

    /** The box of the plot {@code pos} stands in — cage excluded — or null outside every plot. */
    @Nullable
    private static BoundingBox plotBoxAround(ServerLevel level, BlockPos pos, CarriageDims dims) {
        Optional<EditorCategory.Located> located = EditorCategory.locateAt(pos, dims);
        if (located.isEmpty()) return null;
        Template model = located.get().model();
        BlockPos origin = model.editorPlotOrigin(level, dims);
        Vec3i size = model.plotSize(dims);
        if (origin == null || size == null) return null;
        return new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX() - 1, origin.getY() + size.getY() - 1, origin.getZ() + size.getZ() - 1);
    }

    private static void ghost(ServerLevel level, CarriageDims dims, BlockPos anchorPos, Direction facing,
                              String name, BoundingBox clip, List<String> ancestors,
                              Map<BlockPos, BlockState> into) {
        if (ancestors.size() >= FUSE || ancestors.contains(name)) return;
        List<Cell> cells = cellsOf(level, dims, name);
        if (cells == null) return;
        Rotation rotation = PrefabAnchorBlock.rotationFor(facing);
        List<String> chain = new ArrayList<>(ancestors.size() + 1);
        chain.addAll(ancestors);
        chain.add(name);
        // The anchor cell itself becomes whatever the prefab puts there — air when nothing does.
        into.remove(anchorPos);
        for (Cell cell : cells) {
            BlockPos world = anchorPos.offset(StructureTemplate.transform(cell.local(), Mirror.NONE, rotation, BlockPos.ZERO));
            if (!clip.isInside(world)) continue;
            if (cell.anchorTo() != null) {
                // A nested anchor is not drawn; its prefab is.
                Direction nested = rotation.rotate(cell.state().getValue(HorizontalDirectionalBlock.FACING));
                into.remove(world);
                if (!cell.anchorTo().isEmpty()) ghost(level, dims, world, nested, cell.anchorTo(), clip, chain, into);
                continue;
            }
            BlockState rotated = cell.state().rotate(rotation);
            if (rotated.isAir()) {
                into.remove(world);   // an air cell carves — nothing to draw, but it does undo an earlier ghost
                continue;
            }
            into.put(world, rotated);
        }
    }

    /**
     * {@code name}'s authored cells, cached until the next {@link PrefabAnchorIndex#generation()} move
     * (a save or delete bumps it). Null when the prefab has no template.
     */
    @Nullable
    private static List<Cell> cellsOf(ServerLevel level, CarriageDims dims, String name) {
        long gen = PrefabAnchorIndex.generation();
        CachedCells cached = CELLS.get(name);
        if (cached != null && cached.generation() == gen) return cached.cells();
        Optional<StructureTemplate> template = PrefabTemplateStore.get(level, name, dims);
        if (template.isEmpty()) {
            CELLS.remove(name);
            return null;
        }
        List<Cell> cells = readCells(level, template.get());
        CELLS.put(name, new CachedCells(gen, cells));
        return cells;
    }

    /** Walk the template's saved tag: every palette entry and block, in template-local space. */
    private static List<Cell> readCells(ServerLevel level, StructureTemplate template) {
        CompoundTag tag = template.save(new CompoundTag());
        HolderGetter<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
        // One palette in every template the editor writes; the multi-palette form is vanilla's
        // random-variant feature and reads the first palette, which is what a stamp uses by default.
        ListTag palette = tag.contains("palette", Tag.TAG_LIST)
            ? tag.getList("palette", Tag.TAG_COMPOUND)
            : tag.getList("palettes", Tag.TAG_LIST).isEmpty() ? new ListTag()
                : tag.getList("palettes", Tag.TAG_LIST).getList(0);
        List<BlockState> states = new ArrayList<>(palette.size());
        for (int i = 0; i < palette.size(); i++) states.add(NbtUtils.readBlockState(blocks, palette.getCompound(i)));

        ListTag list = tag.getList("blocks", Tag.TAG_COMPOUND);
        List<Cell> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag b = list.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            int idx = b.getInt("state");
            if (pos.size() != 3 || idx < 0 || idx >= states.size()) continue;
            BlockState state = states.get(idx);
            String anchorTo = null;
            if (PrefabAnchorBlock.isAnchor(state)) {
                String bound = b.contains("nbt", Tag.TAG_COMPOUND)
                    ? PrefabAnchorBlockEntity.prefabIn(b.getCompound("nbt")) : "";
                anchorTo = bound.isEmpty() ? "" : bound;   // "" = unbound anchor: draw nothing there
            }
            out.add(new Cell(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)), state, anchorTo));
        }
        return out;
    }
}
