package games.brennan.dungeontrain.builder;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.CarriageEditor;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.EditorMirror;
import games.brennan.dungeontrain.editor.EditorPlotSnapshots;
import games.brennan.dungeontrain.editor.EditorVariantMirror;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.editor.BlockVariantPlot;
import games.brennan.dungeontrain.template.TemplateDecor;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantBlocks;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupPlacer;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
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
import net.minecraft.world.level.block.Block;
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

    /**
     * What happened, so the caller can tell the player — and, on success, exactly what was written.
     *
     * <p>{@link #written} is how a save reaches anything beyond the local template store (today: the
     * relay upload behind {@code BuilderRelayUpload}). It is filled in by the arm that did the writing
     * rather than re-derived afterwards: each arm already resolved the volume it captured, and a second
     * implementation of "where is this build and how big is it" is a second implementation to get
     * wrong — the part arm's origin is offset into the kind's first placement, and a portal room's size
     * is the author's rather than {@link CarriageDims}.</p>
     */
    public record Result(boolean saved, String variantId, String failure, Written written) {
        static Result ok(String variantId, Written written) {
            return new Result(true, variantId, null, written);
        }

        static Result failed(String failure) {
            return new Result(false, null, failure, null);
        }
    }

    /**
     * The build a save just wrote: which store owns it, its id there, and the world volume its blocks
     * occupy.
     *
     * @param kind     which store the template went to — the same {@link BuilderPhotoPaths.Kind} the
     *                 photo of it is filed under
     * @param id       the template id within that store
     * @param subKind  the part or track kind, whose id-space the {@code id} belongs to; empty for the
     *                 kinds that have one flat namespace
     * @param origin   world position of the volume's minimum corner
     * @param size     the volume's extent, in blocks
     */
    public record Written(BuilderPhotoPaths.Kind kind, String id, String subKind, BlockPos origin, Vec3i size) {
        public Written {
            subKind = subKind == null ? "" : subKind;
        }
    }

    /**
     * Save the build in this builder world, and — on success — ask for a backup.
     *
     * <p>The backup request is the whole reason this wraps {@link #saveInternal}: a build that has
     * just been written exists nowhere else, and before backups were taken here a carriage authored
     * mid-session sat in no archive until the next world load. The request returns immediately and
     * is debounced, so saving repeatedly while iterating costs nothing extra — see
     * {@link games.brennan.dungeontrain.data.PlayerDataBackupHook}.</p>
     */
    public static Result save(ServerLevel level) {
        Result result = saveInternal(level);
        if (result.saved()) {
            games.brennan.dungeontrain.data.PlayerDataBackupHook.onTemplateSaved();
        }
        return result;
    }

    private static Result saveInternal(ServerLevel level) {
        if (!level.dimensionTypeRegistration().is(BuilderWorldLayout.BUILDER_DIMENSION_TYPE)) {
            return Result.failed("not a builder world");
        }
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        CarriageDims dims = data.dims();
        // A track-side build is written to a different store from a different volume, and the
        // carriage arm below has no reading of it — the sub type it switches on names parts of a
        // carriage, and a rail is not one. Branch before any of that.
        TrackKind trackKind = BuilderTrackBuild.kindOf(data);
        if (trackKind != null) {
            return saveTrack(level, data, dims, trackKind);
        }
        List<BoundingBox> volumes = BuilderBounds.volumesFor(level);

        if (volumes.isEmpty()) {
            return Result.failed("nothing to save");
        }
        // A draft has no template to write to. The client asks for a name before getting here;
        // this is the backstop for a packet that arrives from anywhere else.
        String name = data.builderName();
        if (name == null || name.isEmpty()) {
            return Result.failed("this build has no name yet");
        }
        String subTypeToken = data.builderSubType();
        boolean portalRoom = BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE.equals(subTypeToken);
        BuilderNewOptions.SubType subType = subTypeOf(subTypeToken);
        // From outside the wall, a Carriage Room names the CARRIAGE the room is in — that is what the
        // Open screen browsed and handed over (BuilderOpenScreen sends Kind.CARRIAGE for the
        // stage-drilled list). Writing contents for it would file a carriage's blocks in the contents
        // store under a carriage's name, where nothing would ever look for them.
        BuilderMode mode = BuilderMode.fromId(data.builderMode()).orElse(null);
        boolean carriageFromOutside = subType == BuilderNewOptions.SubType.CARRIAGE_ROOM
                && mode == BuilderMode.TRAIN_OUTSIDE;

        BoundingBox box = volumes.get(0);
        BlockPos origin = BuilderBounds.originOf(box);
        // More than one parked carriage means the build IS the run: Train Outside authors a stretch of
        // train, and only its Whole Carriage arm parks more than one (BuilderWorldLayout.parkedCarriages
        // answers 1 for every other sub type). Saving that as one template per carriage would keep the
        // blocks and lose the composition — which carriage followed which, and that they were made to
        // be seen together.
        boolean group = volumes.size() > 1 && subType == BuilderNewOptions.SubType.WHOLE_CARRIAGE;
        Written written;
        try {
            if (portalRoom) {
                Vec3i roomSize = BuilderBounds.sizeOf(box);
                // The builder's own mirror flags, not the room's sidecar. The sidecar records how the
                // room was authored in the Train Editor — one master octant, the rest generated — and
                // applying that here would regenerate three quarters of the room over whatever was
                // placed by hand in the builder, where you stand in the whole thing and edit all of
                // it. Off by default (openPortalRoom sets DEFAULT), so this does nothing unless the
                // pause menu's mirror row was actually used.
                mirrorBeforeCapture(level, origin, roomSize,
                        new BlockVariantPlot.TrackPlot(TrackKind.PORTAL_ROOM, name, origin, roomSize));
                savePortalRoom(level, origin, roomSize, name);
                written = new Written(BuilderPhotoPaths.Kind.PORTAL_ROOM, name, "", origin, roomSize);
            } else if (group) {
                // Mirror every carriage first, then capture the run in one pass — the same order a
                // single-carriage save uses, applied to each volume because the mirror works on one
                // carriage plot at a time.
                for (BoundingBox volume : volumes) {
                    mirrorBeforeCapture(level, BuilderBounds.originOf(volume), dims);
                }
                written = saveCarriageGroup(level, origin, dims, name, volumes.size());
            } else {
                // Mirror first, capture second — the same order every editor save() uses. Without it
                // a build with mirroring on saves only the half that was actually placed by hand.
                mirrorBeforeCapture(level, origin, dims);
                written = switch (subType) {
                    case CARRIAGE_ROOM -> carriageFromOutside
                            ? saveWholeCarriage(level, origin, dims, name, data.builderStage())
                            : saveContents(level, origin, dims, name);
                    case PARTS -> savePart(level, origin, dims, name, data.builderPartKind());
                    case WHOLE_CARRIAGE -> saveWholeCarriage(level, origin, dims, name, data.builderStage());
                };
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
        LOGGER.info("[DungeonTrain] Builder save: wrote {} '{}' over {} volume(s)",
                portalRoom ? BuilderOpenRequest.PORTAL_ROOM_SUB_TYPE
                        : (group ? "carriage_group" : subType.id()),
                name, group ? volumes.size() : 1);
        return Result.ok(name, written);
    }

    /**
     * The build is a track tile, pillar section, tunnel or stairs adjunct.
     *
     * <p>Shaped on {@code TrackEditor.save} rather than on the carriage arms above, because that is
     * the code that already knows how to write one of these: mirror from the template's own
     * {@code .variants.json} sidecar, capture the kind's footprint, write the NBT, then register the
     * name. The editor's version resolves a plot from where the player is standing; here the plot is
     * known, which is the only difference.</p>
     *
     * <p>No {@code saveTarget} dance and no name-from-a-draft case. A track build always has exactly
     * one volume and always has a name, because there is no way into one except by opening a
     * template — the track modes have no New arm that could leave an unnamed draft on the plot.</p>
     */
    private static Result saveTrack(ServerLevel level, DungeonTrainWorldData data, CarriageDims dims,
                                    TrackKind kind) {
        String name = data.builderName();
        if (name == null || name.isEmpty()) {
            return Result.failed("this build has no name yet");
        }
        BlockPos origin = BuilderTrackPlot.origin(kind, dims);
        Vec3i footprint = BuilderTrackPlot.footprint(kind, dims);
        try {
            TrackVariantBlocks sidecar = TrackVariantBlocks.loadFor(kind, name, footprint);
            mirrorTrackBeforeCapture(level, kind, name, origin, footprint, sidecar);

            // Tunnels capture against STRUCTURE_VOID, everything else against AIR — the tunnel
            // templates use void to mean "leave whatever the world had here", and capturing one
            // against AIR would bake the builder-world sky into the arch's corner pockets. Same
            // split TunnelEditor.save makes, for the same reason.
            //
            // Through TemplateDecor, not a bare fillFromWorld: the raw call drops every entity, so a
            // tile saved here lost the pictures and mobs the same tile saved in the Train Editor
            // keeps. A builder world has natural spawning off (BuilderQuietRules), so what is
            // standing in the plot is what the builder put there.
            StructureTemplate template =
                TemplateDecor.capture(level, origin, footprint, ignoreBlockFor(kind));
            TrackVariantStore.save(kind, name, template);
            if (TrackVariantRegistry.register(kind, name)) {
                LOGGER.info("[DungeonTrain] Builder save: registered new {} '{}'", kind.id(), name);
            }
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Builder save failed for {} '{}'", kind.id(), name, t);
            return Result.failed(t.getMessage() == null ? t.toString() : t.getMessage());
        }

        BuilderSidecarCarry.carryToTemplate(level, BlockVariantPlot.trackKey(kind, name), dims,
                BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.TRACK, null, dims));
        EditorPlotSnapshots.capture(BuilderDirtyCheck.snapshotKey(kind, name), level, origin,
                footprint.getX(), footprint.getY(), footprint.getZ());
        LOGGER.info("[DungeonTrain] Builder save: wrote track {} '{}'", kind.id(), name);
        // A track template's id is only unique within its kind — `default` is a track tile, a pillar
        // section, a tunnel and a staircase — so the kind rides along as the sub kind.
        return Result.ok(name, new Written(BuilderPhotoPaths.Kind.TRACK, name, kind.id(), origin, footprint));
    }

    /** What a capture of this kind treats as "not part of the template". See {@link #saveTrack}. */
    private static Block ignoreBlockFor(TrackKind kind) {
        return kind == TrackKind.TUNNEL_SECTION || kind == TrackKind.TUNNEL_PORTAL
                ? Blocks.STRUCTURE_VOID
                : Blocks.AIR;
    }

    /**
     * The track equivalent of {@link #mirrorBeforeCapture}.
     *
     * <p>Two differences from the carriage version, both taken from the editors' track save. The
     * plot is a {@code BlockVariantPlot.TrackPlot} rather than a builder carriage plot, since that
     * is what the variant mirror knows how to walk for a track kind. And the structural pass is
     * given the sidecar's markers rather than an empty set — {@code BuilderCarriagePlot}'s call
     * passes {@code Set.of()}, but a track template's sidecar is exactly where its markers live, and
     * dropping them would mirror over the cells that were meant to be left alone.</p>
     */
    private static void mirrorTrackBeforeCapture(ServerLevel level, TrackKind kind, String name,
                                                 BlockPos origin, Vec3i footprint,
                                                 TrackVariantBlocks sidecar) {
        if (!sidecar.mirrorX() && !sidecar.mirrorY() && !sidecar.mirrorZ()) {
            return;
        }
        EditorVariantMirror.rebuildFromMaster(level,
                new BlockVariantPlot.TrackPlot(kind, name, origin, footprint));
        EditorMirror.rebuildFromMaster(level, origin, footprint,
                sidecar.mirrorX(), sidecar.mirrorY(), sidecar.mirrorZ(),
                EditorMirror.markersOf(sidecar.entries()));
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
    private static Written saveWholeCarriage(ServerLevel level, BlockPos origin, CarriageDims dims,
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
        // Pools before the mirror flags: both end up on the same CarriageVariantBlocks instance, and
        // the mirror carry is the write that persists it.
        BuilderSidecarCarry.carryToTemplate(level, BlockVariantPlot.carriageKey(variant.id()), dims,
                BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.CARRIAGE, null, dims));
        carryMirrorToTemplate(level, variant, dims);
        return new Written(BuilderPhotoPaths.Kind.CARRIAGE, name, "", origin,
                new Vec3i(dims.length(), dims.height(), dims.width()));
    }

    /**
     * The build is a run of carriages: capture the whole run as one template.
     *
     * <p>One write, unlike {@link #saveWholeCarriage}'s two. A whole carriage is written twice because
     * the spawn pool only knows about carriage <em>shells</em>, so a build has to exist as one to keep
     * appearing in trains. A group has no such second home — nothing places a group on a train yet —
     * and writing its whole run into the shell store would register a template three carriages long
     * where every reader expects one, which the shell store's own footprint gate would then refuse on
     * every read.</p>
     *
     * <p>The carriage count lives in the footprint rather than beside it: {@code carriages × length}
     * is the box, so a group cannot claim to be a size it isn't. See {@code CarriageGroupTemplateStore}.</p>
     */
    private static Written saveCarriageGroup(ServerLevel level, BlockPos origin, CarriageDims dims,
                                             String name, int carriages) throws IOException {
        CarriageGroup group = CarriageGroup.of(name);
        StructureTemplate template = CarriageGroupPlacer.captureTemplate(level, origin, dims, carriages);
        // File first, registry second: a reload landing between the two would otherwise leave a
        // registered id with nothing on disk.
        CarriageGroupTemplateStore.save(group, template);
        if (CarriageGroupRegistry.register(group)) {
            LOGGER.info("[DungeonTrain] Builder save: registered new carriage group '{}' ({} carriages)",
                    name, carriages);
        }
        return new Written(BuilderPhotoPaths.Kind.CARRIAGE_GROUP, name, "", origin,
                CarriageGroupPlacer.sizeOf(dims, carriages));
    }

    /**
     * The build is what's <em>inside</em> the carriage.
     *
     * <p>{@code CarriageContentsPlacer.captureTemplate} takes the carriage origin and offsets to the
     * interior itself, so the shell around it is never part of what gets written — a room saved here
     * drops into any carriage, which is the whole point of contents being separate.</p>
     */
    private static Written saveContents(ServerLevel level, BlockPos origin, CarriageDims dims,
                                        String name) throws IOException {
        CarriageContents contents = CarriageContentsRegistry.find(name).orElse(null);
        StructureTemplate template = CarriageContentsPlacer.captureTemplate(level, origin, dims);
        // The volume that capture just read: the interior, not the carriage — the same two calls
        // captureTemplate makes, so what is uploaded is what was written.
        Written written = new Written(BuilderPhotoPaths.Kind.CONTENTS, name, "",
                CarriageContentsPlacer.interiorOrigin(origin), CarriageContentsPlacer.interiorSize(dims));
        if (contents == null) {
            CarriageContents.Custom created = new CarriageContents.Custom(name);
            // The template has to exist before the registry points at it, or a reload in between
            // would leave a registered id with nothing on disk.
            CarriageContentsStore.save(created, template);
            if (!CarriageContentsRegistry.register(created)) {
                throw new IOException("'" + name + "' is a reserved contents name");
            }
            LOGGER.info("[DungeonTrain] Builder save: registered new contents '{}'", name);
            carryContentsSidecars(level, dims, name);
            return written;
        }
        CarriageContentsStore.save(contents, template);
        carryContentsSidecars(level, dims, name);
        return written;
    }

    /**
     * The build is one reusable part of the shell.
     *
     * <p>Captured from the kind's <em>first</em> placement — the unmirrored one, which is the master
     * copy; walls and doors are stamped twice and the second is a mirror of this region, so writing
     * that one back would save a reflection of the thing being authored.</p>
     */
    private static Written savePart(ServerLevel level, BlockPos origin, CarriageDims dims,
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
        Vec3i partSize = kind.dims(dims);
        // TemplateDecor rather than a bare fillFromWorld — see saveTrack above for why.
        StructureTemplate template = TemplateDecor.capture(level, partOrigin, partSize, Blocks.AIR);
        CarriagePartTemplateStore.save(kind, name, template);
        if (CarriagePartRegistry.register(kind, name)) {
            LOGGER.info("[DungeonTrain] Builder save: registered new {} part '{}'", kind.id(), name);
        }
        BuilderSidecarCarry.carryToTemplate(level, BlockVariantPlot.partKey(kind, name), dims,
                BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.PART, kind, dims));
        // The master copy's region, mirroring the capture above — a part id is only unique within its
        // kind ('standard' is both a floor and a door), so the kind is the sub kind.
        return new Written(BuilderPhotoPaths.Kind.PART, name, kind.id(), partOrigin, partSize);
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
    private static void savePortalRoom(
        ServerLevel level, BlockPos origin, Vec3i size, String name
    ) throws IOException {
        PortalRoomEditor.saveRoomFrom(level, origin, size, name);
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        BuilderSidecarCarry.carryToTemplate(level,
                BlockVariantPlot.trackKey(TrackKind.PORTAL_ROOM, name), dims,
                BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.PORTAL_ROOM, null, dims));
    }

    /**
     * A carriage room's pools and contents, onto the template the save just wrote.
     *
     * <p>Its own helper because {@link #saveContents} returns from two places — a new registration
     * and an overwrite — and the carry has to happen on both.</p>
     */
    private static void carryContentsSidecars(ServerLevel level, CarriageDims dims, String name) {
        BuilderSidecarCarry.carryToTemplate(level, BlockVariantPlot.contentsKey(name), dims,
                BuilderSidecarCarry.offsetFor(BuilderPhotoPaths.Kind.CONTENTS, null, dims));
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
        mirrorBeforeCapture(level, origin, new Vec3i(dims.length(), dims.height(), dims.width()),
                BuilderCarriagePlot.of(level, origin, dims));
    }

    /**
     * As above, over an arbitrary volume and variant plot.
     *
     * <p>The volume is a parameter because a portal room's is the author's rather than
     * {@link CarriageDims}, and the plot because the variant mirror has to name the store the cells
     * live in — a carriage plot for a carriage, a {@code PORTAL_ROOM} track plot for a room.</p>
     *
     * <p>Whichever it is, the axes come from the <b>builder's</b> flags, and none set means nothing
     * happens. That is the whole contract: mirroring is a thing the builder switches on, not
     * something a template's own history does to their work.</p>
     */
    private static void mirrorBeforeCapture(ServerLevel level, BlockPos origin, Vec3i size,
                                            BlockVariantPlot plot) {
        BuilderMirrorFlags flags = DungeonTrainWorldData.get(level).builderMirror();
        if (!flags.anyAxis()) {
            return;
        }
        if (plot != null) {
            EditorVariantMirror.rebuildFromMaster(level, plot);
        }
        EditorMirror.rebuildFromMaster(level, origin, size,
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
