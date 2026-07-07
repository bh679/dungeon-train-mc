package games.brennan.dungeontrain.editor;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stage-wide block replacement: rewrite every occurrence of a {@link Block} across all parts a
 * stage uses (per {@link StageBlockIndex#partsForStage}) — both the structural NBT templates and
 * the {@code .variants.json} candidate lists — then re-stamp the affected editor plots.
 *
 * <p>Replacement is per-<b>Block</b> and property-preserving: each occurrence's state maps to
 * {@code to.defaultBlockState()} with every property the target block shares copied over (stairs
 * keep facing/half/shape, slabs keep type, etc. — vanilla families share the same static
 * {@link Property} instances).</p>
 *
 * <p>Structural rewrite is a tag-level round trip through
 * {@link CarriagePartTemplateStore}'s own save path ({@code template.save(tag)} → mutate the
 * {@code palette}/{@code palettes} state entries → {@code new StructureTemplate().load(...)}) —
 * never an in-place reflective palette write, which would leave the palette's per-Block lookup
 * caches stale. Block-entity hygiene: when the replacement block has no block entity, the stale
 * {@code nbt} payload of rewritten {@code blocks} entries is dropped; BE→BE replacements keep the
 * payload best-effort (logged).</p>
 *
 * <p>There is no undo — callers must confirm first; in dev mode the source-tree git diff is the
 * recovery path.</p>
 */
public final class StageBlockReplacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What a {@link #replaceAcrossStage} run rewrote — feed for chat feedback and panel resync. */
    public record Result(List<StageBlockIndex.PartRef> partsTouched,
                         int paletteStatesRewritten, int sidecarStatesRewritten) {
        public boolean isEmpty() {
            return partsTouched.isEmpty();
        }
    }

    private StageBlockReplacer() {}

    /**
     * Replace {@code from} with the held block {@code to} across every part of stage {@code stageId},
     * preserving orientation. {@code toBeNbt} is the held item's captured block-entity data (see
     * #636's swap), applied to rewritten variant candidates when {@code to} carries a block entity.
     * Runs on the server thread.
     *
     * @throws IOException on unknown stage, same/air replacement, or a config-dir write failure.
     *                     Source-tree promotion failures are warn-logged, never thrown.
     */
    public static Result replaceAcrossStage(ServerLevel level, String stageId,
                                            Block from, Block to,
                                            net.minecraft.nbt.CompoundTag toBeNbt) throws IOException {
        if (from == null || to == null) throw new IOException("Unknown block.");
        if (from == to) throw new IOException("Source and replacement are the same block.");
        if (to == Blocks.AIR) throw new IOException("Replacing with air is not supported.");
        if (!StageStore.exists(stageId)) throw new IOException("No such stage: " + stageId);

        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        HolderGetter<Block> blockLookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);

        List<StageBlockIndex.PartRef> touched = new ArrayList<>();
        int paletteCount = 0;
        int sidecarCount = 0;
        for (StageBlockIndex.PartRef ref : StageBlockIndex.partsForStage(stageId)) {
            int p = rewriteTemplate(level, blockLookup, ref, dims, from, to);
            int s = rewriteSidecar(ref, dims, from, to, toBeNbt);
            if (p > 0 || s > 0) touched.add(ref);
            paletteCount += p;
            sidecarCount += s;
        }

        // Re-paint the affected plots (read-from-disk, so unsaved in-world edits on them are
        // clobbered — callers warn in chat). Only meaningful while CARRIAGES is stamped.
        if (!touched.isEmpty()
                && EditorStampedCategoryState.current().orElse(null) == EditorCategory.CARRIAGES) {
            for (StageBlockIndex.PartRef ref : touched) {
                CarriagePartEditor.stampPlot(level, ref.kind(), ref.name(), dims);
            }
            if (EditorStageSelection.effective() != null) {
                CarriageEditor.stampAllPlots(level, dims);
            }
        }

        LOGGER.info("[DungeonTrain] Stage replace: '{}' {} -> {} ({} part(s), {} palette state(s), "
                + "{} sidecar state(s)).",
            stageId, from, to, touched.size(), paletteCount, sidecarCount);
        return new Result(List.copyOf(touched), paletteCount, sidecarCount);
    }

    /** {@code to.defaultBlockState()} with every property shared with {@code fromState} copied over. */
    static BlockState transfer(BlockState fromState, Block to) {
        BlockState out = to.defaultBlockState();
        for (Property<?> p : fromState.getProperties()) {
            out = copyProperty(fromState, out, p);
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState copyProperty(BlockState src, BlockState dst,
                                                                     Property<T> p) {
        return dst.hasProperty(p) ? dst.setValue(p, src.getValue(p)) : dst;
    }

    /** Rewrite the part's structural NBT. Returns the number of palette states rewritten. */
    private static int rewriteTemplate(ServerLevel level, HolderGetter<Block> blockLookup,
                                       StageBlockIndex.PartRef ref, CarriageDims dims,
                                       Block from, Block to) throws IOException {
        Optional<StructureTemplate> stored =
            CarriagePartTemplateStore.get(level, ref.kind(), ref.name(), dims);
        if (stored.isEmpty()) return 0;

        CompoundTag tag = stored.get().save(new CompoundTag());
        Set<Integer> rewrittenIndices = new HashSet<>();
        int rewritten = 0;
        if (tag.contains("palette", Tag.TAG_LIST)) {
            rewritten += rewritePaletteList(tag.getList("palette", Tag.TAG_COMPOUND),
                blockLookup, from, to, rewrittenIndices);
        }
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = tag.getList("palettes", Tag.TAG_LIST);
            for (int i = 0; i < palettes.size(); i++) {
                rewritten += rewritePaletteList((ListTag) palettes.get(i),
                    blockLookup, from, to, rewrittenIndices);
            }
        }
        if (rewritten == 0) return 0;

        if (!(to instanceof EntityBlock) && tag.contains("blocks", Tag.TAG_LIST)) {
            ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
            for (int i = 0; i < blocks.size(); i++) {
                CompoundTag b = blocks.getCompound(i);
                if (rewrittenIndices.contains(b.getInt("state")) && b.contains("nbt")) {
                    b.remove("nbt");
                    LOGGER.info("[DungeonTrain] Stage replace: dropped block-entity nbt at {} in "
                        + "{}:{} (replacement has no block entity).", b.get("pos"),
                        ref.kind().id(), ref.name());
                }
            }
        }

        StructureTemplate fresh = new StructureTemplate();
        fresh.load(blockLookup, tag);
        CarriagePartTemplateStore.save(ref.kind(), ref.name(), fresh);
        if (EditorDevMode.isEnabled()) {
            try {
                CarriagePartTemplateStore.saveToSource(ref.kind(), ref.name(), fresh);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Stage replace: source write failed for part {}:{}: {} "
                    + "(config write succeeded).", ref.kind().id(), ref.name(), e.toString());
            }
        }
        return rewritten;
    }

    private static int rewritePaletteList(ListTag palette, HolderGetter<Block> blockLookup,
                                          Block from, Block to, Set<Integer> rewrittenIndices) {
        int n = 0;
        for (int i = 0; i < palette.size(); i++) {
            BlockState state = NbtUtils.readBlockState(blockLookup, palette.getCompound(i));
            if (!state.is(from)) continue;
            palette.set(i, NbtUtils.writeBlockState(transfer(state, to)));
            rewrittenIndices.add(i);
            n++;
        }
        return n;
    }

    /** Rewrite matching candidates in the part's variants sidecar. Returns states rewritten. */
    private static int rewriteSidecar(StageBlockIndex.PartRef ref, CarriageDims dims,
                                      Block from, Block to, CompoundTag toBeNbt) throws IOException {
        Vec3i partSize = ref.kind().dims(dims);
        CarriagePartVariantBlocks sidecar =
            CarriagePartVariantBlocks.loadFor(ref.kind(), ref.name(), partSize);
        if (sidecar.isEmpty()) return 0;

        int rewritten = 0;
        for (CarriageVariantBlocks.Entry entry : sidecar.entries()) {
            List<VariantState> updated = new ArrayList<>(entry.states().size());
            boolean changed = false;
            for (VariantState s : entry.states()) {
                if (!s.isMob() && !CarriageVariantBlocks.isEmptyPlaceholder(s.state())
                        && s.state().is(from)) {
                    // New block carries a BE → apply the held item's BE data (like #636); else drop.
                    CompoundTag nbt = (to instanceof EntityBlock) ? toBeNbt : null;
                    updated.add(s.withState(transfer(s.state(), to), nbt));
                    changed = true;
                    rewritten++;
                } else {
                    updated.add(s);
                }
            }
            if (changed) sidecar.put(entry.localPos(), updated);
        }
        if (rewritten == 0) return 0;

        sidecar.save(ref.kind(), ref.name());
        if (EditorDevMode.isEnabled()) {
            try {
                sidecar.saveToSource(ref.kind(), ref.name());
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Stage replace: sidecar source write failed for {}:{}: {} "
                    + "(config write succeeded).", ref.kind().id(), ref.name(), e.toString());
            }
        }
        return rewritten;
    }
}
