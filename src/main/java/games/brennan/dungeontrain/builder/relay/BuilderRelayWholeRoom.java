package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.EditorLayout;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.train.CarriageContentsPlacer;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.train.CarriagePlacer.CarriageType;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.train.WholeWeights;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * The "Whole carriage room" destination for a relay download — a carriage or contents build
 * installed into the Whole section's room pool instead of the shell or contents pool.
 *
 * <p>Requested by the {@link #WHOLE_ROOM_PARENT} sentinel in the download's {@code parentId}: the
 * colon can never match a real parent id ({@code ^[a-z0-9_]{1,32}$}), so no packet change is
 * needed. A carriage build is already the whole box — shell and interior — and is saved as-is. A
 * contents build is interior-only, so it is baked onto the bundled {@code standard} shell first:
 * both are stamped into a scratch box, the box is captured, and the box is erased again.</p>
 *
 * <p>Deliberately writes nothing to the shell or contents stores — that is the point of choosing
 * this destination: the room joins the Whole pool and nothing else. Its sidecars come across through
 * {@link WholeRoomSidecars}, which translates them out of the source kind's frame; a whole room is not a
 * {@code BuilderPhotoPaths.Kind}, so {@code TemplateSidecars} cannot answer for it.</p>
 */
public final class BuilderRelayWholeRoom {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The {@code parentId} that asks for this destination. Never a valid template id. */
    public static final String WHOLE_ROOM_PARENT = "whole:room";

    /** The weight a room arrives with — in the pool, but not dominating it. */
    public static final int ARRIVAL_WEIGHT = 1;

    /**
     * Scratch box for the contents bake — well off the editor rows, which grow from {@code Z=0}
     * along {@code +Z}, and under the build ceiling.
     */
    private static final int SCRATCH_Z = -(EditorLayout.GAP * 8);

    private BuilderRelayWholeRoom() {}

    public static boolean requested(String parentId) {
        return WHOLE_ROOM_PARENT.equals(parentId);
    }

    public static boolean supports(BuilderPhotoPaths.Kind kind) {
        return kind == BuilderPhotoPaths.Kind.CARRIAGE || kind == BuilderPhotoPaths.Kind.CONTENTS;
    }

    /**
     * Install {@code template} as a whole room, resolving a name collision the way
     * {@link BuilderRelayInstall#install} does.
     *
     * @param sidecars the build's sidecar document, translated onto the room by {@link WholeRoomSidecars}
     */
    public static BuilderRelayInstall.Outcome install(ServerLevel level, BuilderPhotoPaths.Kind kind, String id,
                                                      String stageId, StructureTemplate template,
                                                      BuilderRelayInstall.Resolution resolution, String newName,
                                                      String sidecars, boolean mine) {
        if (!supports(kind) || id == null || id.isEmpty() || template == null) {
            return BuilderRelayInstall.Outcome.UNSUPPORTED;
        }
        BuilderRelayInstall.Resolution how = resolution == null ? BuilderRelayInstall.Resolution.AS_IS : resolution;
        String chosen = newName == null ? "" : newName.trim().toLowerCase(Locale.ROOT);
        String target = id.toLowerCase(Locale.ROOT);
        try {
            switch (how) {
                case LOAD_AS_NEW -> {
                    if (chosen.isEmpty()) return BuilderRelayInstall.Outcome.UNSUPPORTED;
                    if (taken(chosen, mine)) return BuilderRelayInstall.Outcome.NAME_TAKEN;
                    target = chosen;
                }
                case RENAME_EXISTING -> {
                    if (chosen.isEmpty()) return BuilderRelayInstall.Outcome.UNSUPPORTED;
                    if (taken(chosen, mine)) return BuilderRelayInstall.Outcome.NAME_TAKEN;
                    if (!WholeCarriageTemplateStore.rename(target, chosen)) return BuilderRelayInstall.Outcome.FAILED;
                    WholeWeights.rename(WholeKind.ROOM, target, chosen);
                    WholeCarriageRegistry.register(WholeCarriage.of(chosen));
                }
                case AS_IS -> {
                    if (taken(target, mine)) return BuilderRelayInstall.Outcome.ALREADY_HERE;
                }
                case REPLACE -> { }
            }
            if (!WholeCarriage.isValidName(target)) return BuilderRelayInstall.Outcome.UNSUPPORTED;
            BuilderRelayInstall.Outcome outcome = write(level, kind, target, stageId, template);
            // Only once the room is actually on disk, and against the name it landed under — the same
            // point and the same rule as BuilderRelayInstall.write's own sidecar call.
            if (outcome == BuilderRelayInstall.Outcome.INSTALLED) {
                WholeRoomSidecars.apply(kind, target, sidecars);
            }
            return outcome;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Builder relay download: could not install whole room '{}'", id, t);
            return BuilderRelayInstall.Outcome.FAILED;
        }
    }

    private static boolean taken(String id, boolean mine) {
        WholeCarriage room = new WholeCarriage(id);
        return WholeCarriageTemplateStore.exists(room) || (!mine && WholeCarriageRegistry.isBundled(id));
    }

    private static BuilderRelayInstall.Outcome write(ServerLevel level, BuilderPhotoPaths.Kind kind, String id,
                                                     String stageId, StructureTemplate template) throws IOException {
        CarriageDims dims = DungeonTrainWorldData.get(level.getServer().overworld()).dims();
        StructureTemplate room = kind == BuilderPhotoPaths.Kind.CONTENTS
            ? bakeOntoDefaultShell(level, template, dims)
            : template;
        if (room == null) return BuilderRelayInstall.Outcome.UNSUPPORTED;
        if (!CarriagePlacer.sizeMatches(room.getSize(), dims)) {
            Vec3i size = room.getSize();
            LOGGER.warn("[DungeonTrain] Builder relay download: whole room '{}' is {}x{}x{}, this world is {}x{}x{} — refused",
                id, size.getX(), size.getY(), size.getZ(), dims.length(), dims.height(), dims.width());
            return BuilderRelayInstall.Outcome.UNSUPPORTED;
        }
        WholeCarriage wholeCarriage = WholeCarriage.of(id);
        WholeCarriageTemplateStore.save(wholeCarriage, room);
        if (EditorDevMode.isEnabled() && WholeCarriageTemplateStore.sourceTreeAvailable()) {
            WholeCarriageTemplateStore.saveToSource(wholeCarriage, room);
        }
        WholeCarriageRegistry.register(wholeCarriage);
        WholeWeights.set(WholeKind.ROOM, id, ARRIVAL_WEIGHT);
        if (stageId != null && !stageId.isEmpty() && StageStore.exists(stageId)) {
            WholeWeights.setStage(WholeKind.ROOM, id, stageId);
        }
        LOGGER.info("[DungeonTrain] Builder relay download: installed whole room '{}' from a {} build", id, kind.id());
        return BuilderRelayInstall.Outcome.INSTALLED;
    }

    /**
     * Stamp the bundled {@code standard} shell and then {@code contents} inside it into a scratch
     * box, capture the box, erase it. Null when the contents are not interior-sized for this world.
     *
     * <p>In-world rather than an offline NBT merge: two palette-indexed templates would have to be
     * remapped and de-duplicated, which is new and untested surgery, whereas a stamp-then-capture is
     * how every other capture in the mod already works. No stage scope on purpose — placeholders
     * stay placeholders so the room resolves its stage when it is stamped into a train.</p>
     */
    static StructureTemplate bakeOntoDefaultShell(ServerLevel level, StructureTemplate contents, CarriageDims dims) {
        Vec3i interior = CarriageContentsPlacer.interiorSize(dims);
        if (!contents.getSize().equals(interior)) {
            Vec3i size = contents.getSize();
            LOGGER.warn("[DungeonTrain] Builder relay download: contents are {}x{}x{}, interior is {}x{}x{} — cannot bake a whole room",
                size.getX(), size.getY(), size.getZ(), interior.getX(), interior.getY(), interior.getZ());
            return null;
        }
        Optional<StructureTemplate> shell = CarriageTemplateStore.get(level, CarriageVariant.of(CarriageType.STANDARD), dims);
        if (shell.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Builder relay download: no standard shell to bake contents onto");
            return null;
        }
        BlockPos scratch = new BlockPos(0, level.getMaxBuildHeight() - dims.height() - 2, SCRATCH_Z);
        try {
            return CarriageStampGuard.call(() -> {
                CarriagePlacer.eraseAt(level, scratch, dims);
                StructurePlaceSettings settings = new StructurePlaceSettings().setIgnoreEntities(true);
                shell.get().placeInWorld(level, scratch, scratch, settings, level.getRandom(), CarriageStampGuard.STAMP_FLAGS);
                BlockPos inside = CarriageContentsPlacer.interiorOrigin(scratch);
                contents.placeInWorld(level, inside, inside, settings, level.getRandom(), CarriageStampGuard.STAMP_FLAGS);
                return WholeCarriagePlacer.captureTemplate(level, scratch, dims);
            });
        } finally {
            CarriagePlacer.eraseAt(level, scratch, dims);
        }
    }
}
