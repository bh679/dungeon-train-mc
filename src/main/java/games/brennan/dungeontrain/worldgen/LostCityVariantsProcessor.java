package games.brennan.dungeontrain.worldgen;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import games.brennan.dungeontrain.block.stage.StagePlaceholderBlocks;
import games.brennan.dungeontrain.config.DungeonTrainCommonConfig;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.GrowthShapes;
import games.brennan.dungeontrain.editor.MultiBlockVariants;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantGrowth;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Rolls a building's block-variant pools as it generates — the bridge between DT's variant documents
 * ({@link LostCityVariantDocs}) and a vanilla jigsaw placement, which never passes through a DT placer.
 *
 * <p>It sits in a processor list twice. Every per-block hook runs before any whole-list one, and a
 * stretched design duplicates floors in between, so:</p>
 * <ul>
 *   <li>{@code "phase": "mark"}, first in the list, tags each variant cell's block with the cell it
 *       came from. {@link LostCityStretchProcessor} copies that tag onto the floors and bays it adds,
 *       and {@link LostCitySwapProcessor} leaves a tagged block alone.</li>
 *   <li>{@code "phase": "roll"}, after the stretches and before bite / facade / truncate, rolls every
 *       tagged block — the template's and the copies' — writes the pick, grows its column and removes
 *       the tag. Each block it writes goes through the list's swaps first, so a design's recolour and
 *       its dry look cover the picks too.</li>
 * </ul>
 *
 * <p>The roll is seeded on the placement offset like every Lost City processor. An unlocked cell also
 * mixes in where its block ended up, so a floor the stretch added rolls on its own rather than repeating
 * the floor it was copied from; a cell in a lock group (or pointing at one) does not, so the group still
 * picks together across the whole building.</p>
 *
 * <p>With no document for the template, or with {@code lostCityBlockVariants} off, both phases hand back
 * exactly what they were given.</p>
 */
public final class LostCityVariantsProcessor extends StructureProcessor {

    /** Which half of the work this list entry does. */
    public enum Phase implements StringRepresentable {
        MARK("mark"), ROLL("roll");

        public static final Codec<Phase> CODEC = StringRepresentable.fromEnum(Phase::values);
        private final String id;

        Phase(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public static final MapCodec<LostCityVariantsProcessor> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("template").forGetter(p -> p.template),
            Phase.CODEC.fieldOf("phase").forGetter(p -> p.phase)
    ).apply(instance, LostCityVariantsProcessor::new));

    public static final StructureProcessorType<LostCityVariantsProcessor> TYPE = () -> CODEC;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The tag a marked block carries until its roll: the template-local cell, packed. */
    static final String MARKER = "dungeontrain:variant_cell";
    private static final long SALT = 0x7A21L;
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final AtomicBoolean WARNED_MOB = new AtomicBoolean();

    /** The setting; swapped in tests. */
    static volatile BooleanSupplier enabled = DungeonTrainCommonConfig::isLostCityBlockVariants;

    private final ResourceLocation template;
    private final Phase phase;

    public LostCityVariantsProcessor(ResourceLocation template, Phase phase) {
        this.template = template;
        this.phase = phase;
    }

    public ResourceLocation template() {
        return template;
    }

    public Phase phase() {
        return phase;
    }

    /** True for a block the mark phase tagged and the roll phase has not reached yet. */
    public static boolean isMarked(@Nullable StructureTemplate.StructureBlockInfo info) {
        return info != null && info.nbt() != null && info.nbt().contains(MARKER);
    }

    @Nullable
    private LostCityVariantDocs.Doc doc() {
        return enabled.getAsBoolean() ? LostCityVariantDocs.get(template) : null;
    }

    /** Only tag when this list also rolls — a tag nothing removes would reach the world's block entities. */
    private boolean rolled(StructurePlaceSettings settings) {
        for (StructureProcessor processor : settings.getProcessors()) {
            if (processor instanceof LostCityVariantsProcessor other && other.phase == Phase.ROLL
                    && other.template.equals(template)) return true;
        }
        return false;
    }

    @Override
    @Nullable
    public StructureTemplate.StructureBlockInfo processBlock(LevelReader world, BlockPos offset, BlockPos pivot,
                                                              StructureTemplate.StructureBlockInfo original,
                                                              StructureTemplate.StructureBlockInfo target,
                                                              StructurePlaceSettings settings) {
        if (phase != Phase.MARK || target == null) return target;
        // The pad layer is Lost City Terrain Fit's: it reads those blocks as shipped.
        if (original.pos().getY() == 0) return target;
        LostCityVariantDocs.Doc doc = doc();
        if (doc == null || !doc.has(original.pos()) || !rolled(settings)) return target;
        return mark(target, original.pos());
    }

    static StructureTemplate.StructureBlockInfo mark(StructureTemplate.StructureBlockInfo target, BlockPos local) {
        CompoundTag nbt = target.nbt() == null ? new CompoundTag() : target.nbt().copy();
        nbt.putLong(MARKER, local.asLong());
        return new StructureTemplate.StructureBlockInfo(target.pos(), target.state(), nbt);
    }

    @Override
    public List<StructureTemplate.StructureBlockInfo> finalizeProcessing(ServerLevelAccessor level, BlockPos offset,
                                                                         BlockPos pivot,
                                                                         List<StructureTemplate.StructureBlockInfo> originals,
                                                                         List<StructureTemplate.StructureBlockInfo> processed,
                                                                         StructurePlaceSettings settings) {
        if (phase != Phase.ROLL) return processed;
        LostCityVariantDocs.Doc doc = doc();
        if (doc == null) return processed;
        Rolled rolled = LostCityPlacementMemo.get(
                new LostCityPlacementMemo.Key(this, offset.asLong(), LostCityPlacementMemo.fingerprint(processed, true)),
                () -> roll(doc.blocks(), offset, settings, processed));
        return rolled.isEmpty() ? processed : rolled.apply(processed);
    }

    /**
     * One block the roll puts down: over {@code processed[index]}, or appended when {@code index < 0}.
     * A {@code null} state takes the block out of the piece, as a swap to air does.
     */
    private record Put(int index, BlockPos pos, @Nullable BlockState state, @Nullable CompoundTag nbt) {
        @Nullable
        StructureTemplate.StructureBlockInfo info() {
            if (state == null) return null;
            // A fresh tag per call: vanilla writes a loot seed into a container's tag as it places it.
            return new StructureTemplate.StructureBlockInfo(pos, state, nbt == null ? null : nbt.copy());
        }
    }

    /** A placement's rolled blocks — what {@link LostCityPlacementMemo} keeps across the piece's chunk calls. */
    record Rolled(List<Put> puts) {
        boolean isEmpty() {
            return puts.isEmpty();
        }

        List<StructureTemplate.StructureBlockInfo> apply(List<StructureTemplate.StructureBlockInfo> processed) {
            List<StructureTemplate.StructureBlockInfo> out = new ArrayList<>(processed);
            for (Put put : puts) {
                if (put.index() >= 0) out.set(put.index(), put.info());
                else out.add(put.info());
            }
            out.removeIf(Objects::isNull);
            return out;
        }
    }

    /** The piece as the roll builds it up: what stands at each position, and the writes made so far. */
    private static final class Canvas {
        private final List<StructureTemplate.StructureBlockInfo> processed;
        private final BlockPos origin;
        /** The list's own swaps, applied to every block the roll writes as they were to the template's. */
        private final List<LostCitySwapProcessor> swaps;
        private final Map<Long, Integer> slotAt = new HashMap<>();
        /** Latest write per slot: a {@code processed} index, or {@code -1 - n} for the n-th appended block. */
        private final Map<Integer, Put> written = new HashMap<>();
        private final List<Integer> order = new ArrayList<>();
        private int appended;

        Canvas(List<StructureTemplate.StructureBlockInfo> processed, BlockPos origin, List<LostCitySwapProcessor> swaps) {
            this.processed = processed;
            this.origin = origin;
            this.swaps = swaps;
            for (int i = 0; i < processed.size(); i++) slotAt.put(processed.get(i).pos().asLong(), i);
        }

        boolean wrote(int index) {
            return written.containsKey(index);
        }

        /** The block at {@code pos} as it stands now; air where the piece has none. */
        BlockState stateAt(BlockPos pos) {
            Integer slot = slotAt.get(pos.asLong());
            if (slot == null) return Blocks.AIR.defaultBlockState();
            Put put = written.get(slot);
            if (put == null) return processed.get(slot).state();
            return put.state() == null ? Blocks.AIR.defaultBlockState() : put.state();
        }

        /** The slot a write to {@code pos} lands in: the block standing there, or a new one appended. */
        int slotFor(BlockPos pos) {
            Integer slot = slotAt.get(pos.asLong());
            if (slot == null) {
                slot = -1 - appended++;
                slotAt.put(pos.asLong(), slot);
            }
            return slot;
        }

        /** Writes a rolled block into {@code slot}, through the list's swaps. */
        void place(int slot, BlockPos pos, BlockState state, @Nullable CompoundTag nbt) {
            BlockState out = state;
            for (LostCitySwapProcessor swap : swaps) {
                if (out == null || out.isAir()) break;
                out = swap.swapped(out, origin, pos);
            }
            put(slot, pos, out, out != null && out.hasBlockEntity() ? nbt : null);
        }

        void put(int slot, BlockPos pos, @Nullable BlockState state, @Nullable CompoundTag nbt) {
            if (written.put(slot, new Put(Math.max(slot, -1), pos, state, nbt)) == null) order.add(slot);
        }

        /** What the latest write left in {@code slot}; {@code null} when it took the block out. */
        @Nullable
        BlockState placed(int slot) {
            return written.get(slot).state();
        }

        Rolled rolled() {
            List<Put> puts = new ArrayList<>(order.size());
            for (int slot : order) puts.add(written.get(slot));          // appended slots keep their first-write order
            return new Rolled(List.copyOf(puts));
        }
    }

    /** Rolls every tagged block of {@code processed}. Pure: no level, no shared state. */
    Rolled roll(TrackVariantBlocks blocks, BlockPos origin, StructurePlaceSettings settings,
                List<StructureTemplate.StructureBlockInfo> processed) {
        long seed = origin.asLong() * GOLDEN + SALT;
        int[] a = LostCityStretchProcessor.Frame.of(settings).a();
        List<LostCitySwapProcessor> swaps = new ArrayList<>();
        for (StructureProcessor processor : settings.getProcessors()) {
            if (processor instanceof LostCitySwapProcessor swap) swaps.add(swap);
        }
        Canvas canvas = new Canvas(processed, origin, swaps);
        for (int i = 0; i < processed.size(); i++) {
            StructureTemplate.StructureBlockInfo info = processed.get(i);
            if (!isMarked(info) || canvas.wrote(i)) continue;            // a partner or column already took it
            BlockPos local = BlockPos.of(info.nbt().getLong(MARKER));
            BlockPos world = info.pos();
            List<VariantState> cell = blocks.statesAt(local);
            int lockId = blocks.lockIdAt(local);
            int index = cell == null || grouped(cell, lockId) ? 0 : fold(world.subtract(origin));
            VariantState picked = cell == null ? null : blocks.resolve(local, seed, index);
            if (picked == null) {                                          // every candidate a dead reference
                canvas.put(i, world, info.state(), unmarked(info.nbt()));
                continue;
            }
            if (picked.isMob() && WARNED_MOB.compareAndSet(false, true)) {
                LOGGER.warn("[DungeonTrain] Building variants: mob entries are not spawned at worldgen ({} cell {}); the cell is left empty",
                        template, local.toShortString());
            }
            for (MultiBlockVariants.Write write : MultiBlockVariants.expand(cell, blocks.spanAt(local), picked, local, seed, index,
                    v -> RotationApplier.apply(v.state(), v.rotation(), v.half(), v.active(), local, seed, index, lockId))) {
                BlockPos at = world.offset(turned(a, write.localPos().subtract(local)));
                // The cell's own block is this one, even where a chained stretch stacked another on its position.
                int slot = at.equals(world) ? i : canvas.slotFor(at);
                BlockState state = write.isAir() ? Blocks.AIR.defaultBlockState() : clean(write.state());
                CompoundTag nbt = !write.isAir() && state.hasBlockEntity() ? write.entry().blockEntityNbt() : null;
                canvas.place(slot, at, state, nbt);
                if (!write.isAir() && canvas.placed(slot) == state) {      // a swap kept it as rolled
                    grow(canvas, origin, slot, at, state, nbt, write.entry().growth(), seed, local, index);
                }
            }
            if (!canvas.wrote(i)) canvas.put(i, world, info.state(), unmarked(info.nbt()));
        }
        return canvas.rolled();
    }

    /**
     * Grows a column entry from its cell, as {@code GrowthPass} does in a live level, against the piece's
     * own blocks: it stops at the first occupied space and at the pad, and never overwrites. Survival is
     * not checked — a jigsaw piece is placed with known shapes, which the baked vines already rely on.
     */
    private static void grow(Canvas canvas, BlockPos origin, int slot, BlockPos at, BlockState state, @Nullable CompoundTag nbt,
                             VariantGrowth growth, long seed, BlockPos local, int index) {
        if (!growth.on() || !GrowthShapes.canGrow(state) || GrowthShapes.isScaffolding(state)) return;
        int step = GrowthShapes.effectiveDir(state, growth.dir()) == VariantGrowth.Dir.UP ? 1 : -1;
        int length = growth.rollLength(seed, local.asLong(), index);
        int n = 1;
        while (n < length) {
            BlockPos next = at.above(step * n);
            if (next.getY() <= origin.getY() || !GrowthShapes.isFree(state, canvas.stateAt(next))) break;
            n++;
        }
        if (n < 2) return;
        List<BlockState> column = GrowthShapes.column(state, growth.dir(), n, growth.tip());
        canvas.place(slot, at, clean(column.get(0)), nbt);
        for (int i = 1; i < n; i++) {
            BlockPos next = at.above(step * i);
            canvas.place(canvas.slotFor(next), next, clean(column.get(i)), null);
        }
    }

    /** A lock group, or a cell that follows one, picks together everywhere — stretch copies included. */
    private static boolean grouped(List<VariantState> cell, int lockId) {
        if (lockId != 0) return true;
        for (VariantState state : cell) {
            if (state.isGroupRef()) return true;
        }
        return false;
    }

    /** A placeholder is air in the world, whichever path it came by. */
    private static BlockState clean(BlockState state) {
        return CarriageVariantBlocks.isEmptyPlaceholder(state) || StagePlaceholderBlocks.isPlaceholder(state)
                ? Blocks.AIR.defaultBlockState() : state;
    }

    @Nullable
    private static CompoundTag unmarked(CompoundTag nbt) {
        CompoundTag out = nbt.copy();
        out.remove(MARKER);
        return out.isEmpty() ? null : out;
    }

    /** A template-local offset in world terms: {@code A·local}, {@code a} row-major as {@link LostCityStretchProcessor.Frame}. */
    private static BlockPos turned(int[] a, BlockPos d) {
        if (d.equals(BlockPos.ZERO)) return BlockPos.ZERO;
        return new BlockPos(a[0] * d.getX() + a[1] * d.getY() + a[2] * d.getZ(),
                a[3] * d.getX() + a[4] * d.getY() + a[5] * d.getZ(),
                a[6] * d.getX() + a[7] * d.getY() + a[8] * d.getZ());
    }

    private static int fold(BlockPos relative) {
        long z = relative.asLong() * GOLDEN;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        return (int) (z ^ (z >>> 31));
    }

    @Override
    protected StructureProcessorType<?> getType() {
        return TYPE;
    }
}
