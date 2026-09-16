package games.brennan.dungeontrain.train;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import games.brennan.dungeontrain.block.prefab.PrefabAnchorBlock;
import games.brennan.dungeontrain.block.prefab.PrefabAnchorBlockEntity;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.ContainerContentsPlacement;
import games.brennan.dungeontrain.editor.ContainerContentsStore;
import games.brennan.dungeontrain.editor.PrefabTemplateStore;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantState;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.worldgen.SilentBlockOps;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Turns every {@link PrefabAnchorBlock} standing inside a freshly stamped template into the prefab
 * it is bound to.
 *
 * <p>Runs once per parent stamp, <b>after</b> every other block pass (shell, parts, variant sidecar,
 * contents) and before the footprint is collected — so anchors placed by a block-variant roll are
 * seen, and the prefab's blocks ride into the Sable sub-level with the rest. Each anchor:</p>
 * <ol>
 *   <li>is read for its name and {@code FACING};</li>
 *   <li>is cleared (block entity evicted first — see {@link SilentBlockOps#evictBlockEntity});</li>
 *   <li>has its prefab stamped with the prefab's local origin on the anchor and local {@code +X}
 *       along the facing ({@link PrefabAnchorBlock#rotationFor}), clipped to the parent's box
 *       via {@link StructurePlaceSettings#setBoundingBox} so nothing pokes out of the carriage;</li>
 *   <li>gets the prefab's own {@code .variants.json} laid over it in the same frame;</li>
 *   <li>is scanned again, so anchors <i>inside</i> the prefab resolve in turn.</li>
 * </ol>
 *
 * <p><b>No randomness here.</b> An anchor names one prefab; which anchor (if any) a cell holds is
 * decided by the parent's block-variant roll, which is already deterministic on
 * {@code (seed, index)}. That is what lets a portal corridor and its twin resolve to identical
 * blocks without either knowing about the other.</p>
 *
 * <p><b>Recursion guard: no ancestor repeat.</b> A prefab may not appear in its own ancestor chain;
 * an anchor that would close a cycle becomes air with a warning. There is no depth cap by design —
 * the pool is finite, so the chain is bounded by the number of distinct prefabs. {@link #FUSE} is
 * a defensive stop far beyond any real chain, so a corrupted file cannot hang a server tick.</p>
 *
 * <p>A {@code structure_void} cell in a prefab is not in its template at all (the editor captures
 * with {@code STRUCTURE_VOID} as the ignored block), so the parent's block stands there; an air
 * cell carves. Missing or unbound anchors become air.</p>
 */
public final class PrefabResolver {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Defensive stop only — never a design limit. See the class javadoc. */
    private static final int FUSE = 64;

    /** Names already warned about this session, so a missing prefab logs once rather than per stamp. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private PrefabResolver() {}

    /**
     * Resolve every anchor inside {@code box}.
     *
     * @param level     the level the parent was stamped into
     * @param box       the parent's world-space box (inclusive); prefabs are clipped to it
     * @param dims      the world's carriage dims (the store's load signature wants them)
     * @param seed      the roll seed the parent's sidecar used — the prefab's own sidecar rolls on it
     * @param rollIndex the parent's roll index (carriage index, pair key, tile index …)
     * @param relight   {@code true} when the blocks stay where they are put (editor plot, portal
     *                  twin, dimensional carriage); {@code false} on the spawn path, where Sable
     *                  lifts and relights them a tick later
     */
    public static void resolveWithin(ServerLevel level, BoundingBox box, CarriageDims dims,
                                     long seed, int rollIndex, boolean relight) {
        resolveWithin(level, box, dims, seed, rollIndex, relight, pos -> false);
    }

    /**
     * {@link #resolveWithin(ServerLevel, BoundingBox, CarriageDims, long, int, boolean)} that also
     * leaves every cell {@code keepOut} claims alone — a dimensional carriage's corridor mask, where
     * the twins standing through the room must not be written over.
     */
    public static void resolveWithin(ServerLevel level, BoundingBox box, CarriageDims dims,
                                     long seed, int rollIndex, boolean relight,
                                     Predicate<BlockPos> keepOut) {
        resolve(level, box, new Frame(box, keepOut, dims, seed, rollIndex, relight), List.of());
    }

    /** Everything about the parent stamp that every anchor in it shares. */
    private record Frame(BoundingBox clip, Predicate<BlockPos> keepOut, CarriageDims dims,
                         long seed, int rollIndex, boolean relight) {
        boolean writable(BlockPos pos) {
            return clip.isInside(pos) && !keepOut.test(pos);
        }
    }

    private static void resolve(ServerLevel level, BoundingBox scan, Frame frame, List<String> ancestors) {
        if (ancestors.size() >= FUSE) {
            LOGGER.error("[DungeonTrain] Prefab chain past {} deep at {} — stopping. Chain: {}",
                FUSE, scan, ancestors);
            return;
        }
        List<Anchor> anchors = findAnchors(level, scan);
        for (Anchor anchor : anchors) {
            resolveOne(level, anchor, frame, ancestors);
        }
    }

    /** One anchor's name, facing and position. */
    private record Anchor(BlockPos pos, String name, Direction facing) {}

    private static List<Anchor> findAnchors(ServerLevel level, BoundingBox box) {
        List<Anchor> out = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(),
                                                   box.maxX(), box.maxY(), box.maxZ())) {
            BlockState state = level.getBlockState(pos);
            if (!PrefabAnchorBlock.isAnchor(state)) continue;
            // Through the chunk, which promotes a PENDING (NBT-form) block entity from a
            // section-local stamp to a live one — level.getBlockEntity would miss it.
            LevelChunk chunk = level.getChunkAt(pos);
            BlockEntity be = chunk.getBlockEntity(pos);
            String name = be instanceof PrefabAnchorBlockEntity a ? a.prefab() : "";
            out.add(new Anchor(pos.immutable(), name, state.getValue(HorizontalDirectionalBlock.FACING)));
        }
        return out;
    }

    private static void resolveOne(ServerLevel level, Anchor anchor, Frame frame, List<String> ancestors) {
        // A prefab resolved earlier in this pass may have stamped over this cell already.
        if (!PrefabAnchorBlock.isAnchor(level.getBlockState(anchor.pos()))) return;
        BoundingBox clip = frame.clip();
        // The anchor goes first, whatever happens next: a prefab whose origin cell is void would
        // otherwise leave the marker standing in a live world.
        clearAnchor(level, anchor.pos());

        String name = anchor.name();
        if (name.isEmpty()) return;
        if (ancestors.contains(name)) {
            LOGGER.warn("[DungeonTrain] Prefab '{}' would nest inside itself ({} -> {}) at {} — left as air.",
                name, String.join(" -> ", ancestors), name, anchor.pos().toShortString());
            return;
        }
        Optional<StructureTemplate> template = PrefabTemplateStore.get(level, name, frame.dims());
        if (template.isEmpty()) {
            if (WARNED.add(name)) {
                LOGGER.warn("[DungeonTrain] Prefab '{}' has no template (anchor at {}) — left as air.",
                    name, anchor.pos().toShortString());
            }
            return;
        }

        Rotation rotation = PrefabAnchorBlock.rotationFor(anchor.facing());
        StructurePlaceSettings settings = new StructurePlaceSettings()
            .setIgnoreEntities(true)
            .setRotation(rotation)
            .setRotationPivot(BlockPos.ZERO)
            .setBoundingBox(clip)
            .addProcessor(new KeepOutProcessor(frame.keepOut()));
        StructureTemplate t = template.get();
        if (frame.relight()) {
            CarriagePlacer.stampTemplateRelit(level, anchor.pos(), t, settings);
        } else {
            CarriagePlacer.stampTemplateSectionLocal(level, anchor.pos(), t, settings);
        }

        BoundingBox placed = t.getBoundingBox(settings, anchor.pos());
        Vec3i size = t.getSize();
        // Two anchors to the same prefab in one parent roll their sidecars apart; the twin of a
        // portal corridor, standing on the same local offset, rolls the same.
        int cellIndex = Objects.hash(frame.rollIndex(), anchor.pos().getX() - clip.minX(),
            anchor.pos().getY() - clip.minY(), anchor.pos().getZ() - clip.minZ());
        applySidecar(level, anchor.pos(), name, size, rotation, frame, cellIndex);

        List<String> chain = new ArrayList<>(ancestors.size() + 1);
        chain.addAll(ancestors);
        chain.add(name);
        Optional<BoundingBox> inside = intersect(placed, clip);
        if (inside.isPresent()) {
            resolve(level, inside.get(), frame, chain);
        }
    }

    private static void clearAnchor(ServerLevel level, BlockPos pos) {
        SilentBlockOps.evictBlockEntity(level.getChunkAt(pos), pos);
        SilentBlockOps.setBlockSilent(level, pos, Blocks.AIR.defaultBlockState());
    }

    /**
     * The prefab's per-cell variant sidecar, rolled and laid in the anchor's frame — each authored
     * local cell is put through the same rotation the template was, then clipped like the template.
     */
    private static void applySidecar(ServerLevel level, BlockPos anchorPos, String name, Vec3i size,
                                     Rotation rotation, Frame frame, int cellIndex) {
        long seed = frame.seed();
        TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(TrackKind.PREFAB, name, size);
        if (sidecar.isEmpty()) return;
        String plotKey = ContainerContentsStore.trackPlotKey(TrackKind.PREFAB, name);
        for (CarriageVariantBlocks.Entry entry : sidecar.entries()) {
            BlockPos local = entry.localPos();
            BlockPos world = anchorPos.offset(
                StructureTemplate.transform(local, Mirror.NONE, rotation, BlockPos.ZERO));
            if (!frame.writable(world)) continue;
            VariantState picked = sidecar.resolve(local, seed, cellIndex);
            if (picked == null) continue;
            if (picked.isMob()) {
                games.brennan.dungeontrain.track.TrackVariantMobs.warnDropped("prefab-sidecar", local, picked.entityId());
                SilentBlockOps.setBlockSilent(level, world, Blocks.AIR.defaultBlockState());
                continue;
            }
            if (CarriageVariantBlocks.isEmptyPlaceholder(picked.state())) {
                SilentBlockOps.setBlockSilent(level, world, Blocks.AIR.defaultBlockState());
                continue;
            }
            BlockState state = RotationApplier.apply(
                StagePlacementScope.resolve(picked.state()), picked.rotation(), picked.half(),
                local, seed, cellIndex, sidecar.lockIdAt(local)).rotate(rotation);
            SilentBlockOps.evictBlockEntity(level.getChunkAt(world), world);
            ContainerContentsPlacement.place(level, world, state, picked.blockEntityNbt(),
                plotKey, local, seed, cellIndex, picked.linkedLootPrefabId());
        }
    }

    /** Drops every template cell {@code keepOut} claims, leaving what stands there. */
    private static final class KeepOutProcessor extends StructureProcessor {
        private static final StructureProcessorType<KeepOutProcessor> TYPE =
            () -> MapCodec.unit(new KeepOutProcessor(pos -> false));

        private final Predicate<BlockPos> keepOut;

        KeepOutProcessor(Predicate<BlockPos> keepOut) {
            this.keepOut = keepOut;
        }

        @Override
        @Nullable
        public StructureTemplate.StructureBlockInfo processBlock(
            LevelReader world, BlockPos offset, BlockPos pivot,
            StructureTemplate.StructureBlockInfo source, StructureTemplate.StructureBlockInfo target,
            StructurePlaceSettings settings
        ) {
            if (target == null) return null;
            return keepOut.test(target.pos()) ? null : target;
        }

        @Override
        protected StructureProcessorType<?> getType() {
            return TYPE;
        }
    }

    private static Optional<BoundingBox> intersect(BoundingBox a, BoundingBox b) {
        if (!a.intersects(b)) return Optional.empty();
        return Optional.of(new BoundingBox(
            Math.max(a.minX(), b.minX()), Math.max(a.minY(), b.minY()), Math.max(a.minZ(), b.minZ()),
            Math.min(a.maxX(), b.maxX()), Math.min(a.maxY(), b.maxY()), Math.min(a.maxZ(), b.maxZ())));
    }

    /** The inclusive world box of a template of {@code size} stamped at {@code origin}, unrotated. */
    public static BoundingBox boxOf(BlockPos origin, Vec3i size) {
        return new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + size.getX() - 1, origin.getY() + size.getY() - 1, origin.getZ() + size.getZ() - 1);
    }

    /** {@link #boxOf} for a carriage-shaped box. */
    public static BoundingBox boxOf(BlockPos origin, CarriageDims dims) {
        return boxOf(origin, new Vec3i(dims.length(), dims.height(), dims.width()));
    }
}
