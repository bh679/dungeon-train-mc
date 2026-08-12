package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.EditorMirror;
import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.editor.EditorVariantMirror;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Save the Train Builder's work, from wherever the player happens to be standing.
 *
 * <p>The editor's own {@code /dungeontrain save} resolves its target from the plot the player is
 * inside, which is the right question when a world holds thirty templates in a grid. A builder
 * world holds exactly one, so standing position is irrelevant — and requiring the player to walk
 * back into the carriage before saving would be a rule with no reason behind it.</p>
 *
 * <p>Writes through the same two calls the editor's save makes
 * ({@code CarriageEditor.captureTemplate} then {@link CarriageTemplateStore#save}), so a builder
 * save and an editor save produce byte-identical templates.</p>
 */
public final class BuilderSave {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuilderSave() {}

    /** What happened, so the caller can tell the player. */
    public record Result(boolean saved, String variantId, String failure) {
        static Result ok(String variantId) {
            return new Result(true, variantId, null);
        }

        static Result failed(String failure) {
            return new Result(false, null, failure);
        }
    }

    public static Result save(ServerLevel level) {
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return Result.failed("not a builder world");
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);

        Optional<Integer> target = saveTarget(volumes.size(), BuilderDirtyCheck.dirtyCarriages(level));
        if (target.isEmpty()) {
            return Result.failed("nothing to save");
        }
        // A draft has no template to write to. The client asks for a name before getting here;
        // this is the backstop for a packet that arrives from anywhere else.
        String name = data.builderName();
        if (name == null || name.isEmpty()) {
            return Result.failed("this build has no name yet");
        }
        BoundingBox box = volumes.get(target.get());
        BlockPos origin = BuilderBounds.originOf(box);
        String subTypeToken = data.builderSubType();
        boolean portalRoom = BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(subTypeToken);
        BuilderNewOptions.SubType subType = subTypeOf(subTypeToken);
        try {
            if (portalRoom) {
                // No mirrorBeforeCapture: a portal room's mirror is rebuilt by the editor's own
                // save path, off the room's variant sidecar rather than the builder's flags — see
                // PortalRoomEditor.saveRoomFrom, which is the same body the Train Editor runs.
                savePortalRoom(level, origin, BuilderBounds.sizeOf(box), name);
            } else {
                // Mirror first, capture second — the same order every editor save() uses. Without it
                // a build with mirroring on saves only the half that was actually placed by hand.
                mirrorBeforeCapture(level, origin, dims);
                switch (subType) {
                    case CARRIAGE_ROOM -> saveContents(level, origin, dims, name);
                    case PARTS -> savePart(level, origin, dims, name, data.builderPartKind());
                    case WHOLE_CARRIAGE -> saveWholeCarriage(level, origin, dims, name, data.builderStage());
                }
            }
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Builder save failed for {}", name, t);
            return Result.failed(t.getMessage() == null ? t.toString() : t.getMessage());
        }

        // Re-baseline every volume, not just the saved one: a re-stamp will make them all match
        // the template that was just written, so leaving the others "dirty" would keep Save green
        // over work that no longer differs from disk.
        for (int i = 0; i < volumes.size(); i++) {
            BoundingBox b = volumes.get(i);
            Vec3i size = BuilderBounds.sizeOf(b);
            EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(i), level,
                    BuilderBounds.originOf(b), size.getX(), size.getY(), size.getZ());
        }
        LOGGER.info("[DungeonTrain] Builder save: wrote {} '{}' from volume {}",
                portalRoom ? BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE : subType.id(),
                name, target.get());
        return Result.ok(name);
    }

    /**
     * The build is a whole carriage: capture the whole volume, exactly as the editor's save does.
     *
     * <p>One capture, written twice.</p>
     *
     * <p>The <b>whole-carriage</b> copy is what the builder actually made — shell and interior in
     * one template, kept together, reopenable from the New screen. The <b>carriage-shell</b> copy
     * is what spawns: the train generator picks from {@code CarriageVariantRegistry} and rolls a
     * separate room over whatever it picks, and it has no idea whole carriages exist. Writing only
     * the first would mean every build made here silently stopped appearing in trains.</p>
     *
     * <p>So both, under the same name, until whole carriages join the spawn pool — at which point
     * the shell write and {@link #carryMirrorToTemplate} go with it. The two stores keep separate
     * subdirs, so sharing a name costs nothing.</p>
     */
    private static void saveWholeCarriage(ServerLevel level, BlockPos origin, CarriageDims dims,
                                          String name, String stageId) throws IOException {
        CarriageVariant variant = variantFor(name)
                .orElseThrow(() -> new IOException("could not resolve or create carriage '" + name + "'"));
        StructureTemplate template = WholeCarriagePlacer.captureTemplate(level, origin, dims);

        // Whole carriage first: the template has to be on disk before the registry points at it,
        // or a reload landing in between would leave a registered id with no file.
        WholeCarriage wholeCarriage = WholeCarriage.of(name);
        WholeCarriageTemplateStore.save(wholeCarriage, template);
        if (WholeCarriageRegistry.register(wholeCarriage)) {
            LOGGER.info("[DungeonTrain] Builder save: registered new whole carriage '{}'", name);
        }

        CarriageTemplateStore.save(variant, template);
        linkStage(variant.id(), stageId);
        carryMirrorToTemplate(level, variant, dims);
    }

    /**
     * The build is what's <em>inside</em> the carriage.
     *
     * <p>{@code CarriageContentsPlacer.captureTemplate} takes the carriage origin and offsets to the
     * interior itself, so the shell around it is never part of what gets written — a room saved here
     * drops into any carriage, which is the whole point of contents being separate.</p>
     */
    private static void saveContents(ServerLevel level, BlockPos origin, CarriageDims dims,
                                     String name) throws IOException {
        CarriageContents contents = CarriageContentsRegistry.find(name).orElse(null);
        StructureTemplate template = CarriageContentsPlacer.captureTemplate(level, origin, dims);
        if (contents == null) {
            CarriageContents.Custom created = new CarriageContents.Custom(name);
            // The template has to exist before the registry points at it, or a reload in between
            // would leave a registered id with nothing on disk.
            CarriageContentsStore.save(created, template);
            if (!CarriageContentsRegistry.register(created)) {
                throw new IOException("'" + name + "' is a reserved contents name");
            }
            LOGGER.info("[DungeonTrain] Builder save: registered new contents '{}'", name);
            return;
        }
        CarriageContentsStore.save(contents, template);
    }

    /**
     * The build is one reusable part of the shell.
     *
     * <p>Captured from the kind's <em>first</em> placement — the unmirrored one, which is the master
     * copy; walls and doors are stamped twice and the second is a mirror of this region, so writing
     * that one back would save a reflection of the thing being authored.</p>
     */
    private static void savePart(ServerLevel level, BlockPos origin, CarriageDims dims,
                                 String name, String partKindId) throws IOException {
        CarriagePartKind kind = CarriagePartKind.fromId(partKindId);
        if (kind == null) {
            throw new IOException("no part kind recorded for this build");
        }
        List<CarriagePartKind.Placement> placements = kind.placements(dims);
        if (placements.isEmpty()) {
            throw new IOException("part kind " + kind.id() + " has no placements");
        }
        BlockPos partOrigin = origin.offset(placements.get(0).originOffset());
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, partOrigin, kind.dims(dims), false, Blocks.AIR);
        CarriagePartTemplateStore.save(kind, name, template);
        if (CarriagePartRegistry.register(kind, name)) {
            LOGGER.info("[DungeonTrain] Builder save: registered new {} part '{}'", kind.id(), name);
        }
    }

    /**
     * Write the build back as a portal room template.
     *
     * <p>Straight through to the Train Editor's own save body, so a room saved here and a room saved
     * from {@code /dt editor portals} produce the same file — including the master-octant mirror
     * rebuild, which a separate implementation would omit and so store a half-built room.</p>
     *
     * <p>Nothing to do about the size afterwards: {@code PortalRoomTemplateStore.save} already calls
     * {@code PortalRoomSizes.settle}, which makes the written template the authority and spends any
     * pending override the size control had set.</p>
     */
    private static void savePortalRoom(ServerLevel level, BlockPos origin, Vec3i size, String name)
            throws IOException {
        PortalRoomEditor.saveRoomFrom(level, origin, size, name);
    }

    /**
     * Tolerant of a world saved before sub types were recorded — those were all carriages.
     *
     * <p>Also the fallback for {@code portal_room}, which is deliberately not one of these values.
     * Callers must test for that token <em>before</em> asking this, the way {@link #save} does; the
     * whole-carriage answer here is what an older world means by a blank field, not a claim that a
     * room is a carriage.</p>
     */
    private static BuilderNewOptions.SubType subTypeOf(String id) {
        for (BuilderNewOptions.SubType value : BuilderNewOptions.SubType.values()) {
            if (value.id().equals(id)) {
                return value;
            }
        }
        return BuilderNewOptions.SubType.WHOLE_CARRIAGE;
    }

    /**
     * Link the saved template to the Stage the build was started for, so a carriage built for the
     * desert stretch actually spawns there.
     *
     * <p>Skipped when no stage was picked — that leaves whatever link the template already had
     * rather than clearing it, since "I didn't choose" is not the same as "make it Custom".</p>
     */
    private static void linkStage(String variantId, String stageId) throws IOException {
        if (stageId == null || stageId.isEmpty() || !StageStore.exists(stageId)) {
            return;
        }
        CarriageWeights.setStage(variantId, stageId);
        LOGGER.info("[DungeonTrain] Builder save: linked carriage {} to stage {}", variantId, stageId);
    }

    /**
     * Which carriage backs the template.
     *
     * <p>The edited one, when there is one — a mode parks the same variant several times, and the
     * builder worked in whichever they walked into. Falling back to the first when nothing is
     * dirty keeps Save from looking broken on a click that simply had nothing new to write.</p>
     *
     * <p>Pure, so the choice is testable without a level.</p>
     */
    public static Optional<Integer> saveTarget(int volumeCount, List<Integer> dirtyIndices) {
        if (volumeCount <= 0) {
            return Optional.empty();
        }
        return dirtyIndices.stream().filter(i -> i >= 0 && i < volumeCount).findFirst()
                .or(() -> Optional.of(0));
    }

    /**
     * The template the build is written to: the one already registered under {@code name}, or a new
     * custom variant created for it.
     *
     * <p>Creating it is the point. The build's <em>source</em> — what its blocks were copied from —
     * is a different template, and writing there would overwrite whatever carriage you started
     * from; before this split, naming a build silently saved over the first registered carriage.
     * Saving twice under the same name overwrites that name, which is what a second Save means.</p>
     */
    private static Optional<CarriageVariant> variantFor(String name) {
        Optional<CarriageVariant> existing = CarriageVariantRegistry.find(name);
        if (existing.isPresent()) {
            return existing;
        }
        CarriageVariant.Custom created = new CarriageVariant.Custom(name);
        if (!CarriageVariantRegistry.register(created)) {
            LOGGER.warn("[DungeonTrain] Builder save: could not register '{}'", name);
            return CarriageVariantRegistry.find(name);
        }
        LOGGER.info("[DungeonTrain] Builder save: registered new carriage '{}'", name);
        return Optional.of(created);
    }

    /**
     * Apply the build's mirror setting to the blocks before they're captured.
     *
     * <p>The same save-time backstop {@code CarriageEditor.save} runs, and in the same order —
     * variant pools first so the structural pass preserves the cells it just reflected. Without it
     * a build authored with mirroring on would store only the half that was placed by hand, because
     * live mirroring can miss edits made before the toggle went on.</p>
     */
    private static void mirrorBeforeCapture(ServerLevel level, BlockPos origin, CarriageDims dims) {
        BuilderMirrorFlags flags = DungeonTrainWorldData.get(level).builderMirror();
        if (!flags.anyAxis()) {
            return;
        }
        BuilderCarriagePlot plot = BuilderCarriagePlot.of(level, origin, dims);
        if (plot != null) {
            EditorVariantMirror.rebuildFromMaster(level, plot);
        }
        EditorMirror.rebuildFromMaster(level, origin,
                new Vec3i(dims.length(), dims.height(), dims.width()),
                flags.x(), flags.y(), flags.z(), Set.of());
    }

    /**
     * Carry the build's mirror setting onto the template it was written to, so the editor sees the
     * same axes when it later opens what the builder made.
     */
    private static void carryMirrorToTemplate(ServerLevel level, CarriageVariant variant, CarriageDims dims) {
        BuilderMirrorFlags flags = DungeonTrainWorldData.get(level).builderMirror();
        try {
            CarriageVariantBlocks sidecar = CarriageVariantBlocks.loadFor(variant, dims);
            sidecar.setMirrorAxes(flags.x(), flags.y(), flags.z());
            sidecar.setMirrorVariants(flags.variants());
            sidecar.save(variant);
        } catch (Throwable t) {
            // Cosmetic: the geometry is already saved and symmetric. Only the editor's future view
            // of the axes is lost, so warn rather than failing a save that actually worked.
            LOGGER.warn("[DungeonTrain] Builder save: could not carry mirror flags to {}: {}",
                    variant.id(), t.toString());
        }
    }
}
