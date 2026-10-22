package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.editor.WholeHalfTemplateStore;
import games.brennan.dungeontrain.editor.pool.PoolScratch;
import games.brennan.dungeontrain.editor.pool.PoolTier;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import games.brennan.dungeontrain.train.WholeHalfRegistry;
import games.brennan.dungeontrain.train.WholeKind;
import games.brennan.dungeontrain.train.WholeWeights;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
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

    /** The {@code parentId} that asks for the Whole room pool. Never a valid template id. */
    public static final String WHOLE_ROOM_PARENT = "whole:room";

    /** The {@code parentId} that asks for the Whole half pool — the corridor-length half box. */
    public static final String WHOLE_HALF_PARENT = "whole:half";

    /** The weight a room arrives with — in the pool, but not dominating it. */
    public static final int ARRIVAL_WEIGHT = 1;


    private BuilderRelayWholeRoom() {}

    public static boolean requested(String parentId) {
        return kindOf(parentId) != null;
    }

    /** The whole pool {@code parentId} asks for, or null when it is not one of the two sentinels. */
    public static WholeKind kindOf(String parentId) {
        if (WHOLE_ROOM_PARENT.equals(parentId)) return WholeKind.ROOM;
        if (WHOLE_HALF_PARENT.equals(parentId)) return WholeKind.HALF;
        return null;
    }

    public static boolean supports(BuilderPhotoPaths.Kind kind) {
        return kind == BuilderPhotoPaths.Kind.CARRIAGE || kind == BuilderPhotoPaths.Kind.CONTENTS;
    }

    /**
     * Install {@code template} as a whole room, resolving a name collision the way
     * {@link BuilderRelayInstall#install} does.
     *
     * @param whole    which whole pool it lands in — {@link WholeKind#ROOM} or {@link WholeKind#HALF}
     * @param sidecars the build's sidecar document, translated onto the room by {@link WholeRoomSidecars}
     */
    public static BuilderRelayInstall.Outcome install(ServerLevel level, BuilderPhotoPaths.Kind kind, WholeKind whole,
                                                      String id, String stageId, StructureTemplate template,
                                                      BuilderRelayInstall.Resolution resolution, String newName,
                                                      String sidecars, boolean mine) {
        if (!supports(kind) || id == null || id.isEmpty() || template == null
                || (whole != WholeKind.ROOM && whole != WholeKind.HALF)) {
            return BuilderRelayInstall.Outcome.UNSUPPORTED;
        }
        BuilderRelayInstall.Resolution how = resolution == null ? BuilderRelayInstall.Resolution.AS_IS : resolution;
        String chosen = newName == null ? "" : newName.trim().toLowerCase(Locale.ROOT);
        String target = id.toLowerCase(Locale.ROOT);
        try {
            switch (how) {
                case LOAD_AS_NEW -> {
                    if (chosen.isEmpty()) return BuilderRelayInstall.Outcome.UNSUPPORTED;
                    if (taken(whole, chosen, mine)) return BuilderRelayInstall.Outcome.NAME_TAKEN;
                    target = chosen;
                }
                case RENAME_EXISTING -> {
                    if (chosen.isEmpty()) return BuilderRelayInstall.Outcome.UNSUPPORTED;
                    if (taken(whole, chosen, mine)) return BuilderRelayInstall.Outcome.NAME_TAKEN;
                    boolean renamed = whole == WholeKind.HALF
                        ? WholeHalfTemplateStore.rename(target, chosen)
                        : WholeCarriageTemplateStore.rename(target, chosen);
                    if (!renamed) return BuilderRelayInstall.Outcome.FAILED;
                    WholeWeights.rename(whole, target, chosen);
                    register(whole, WholeCarriage.of(chosen));
                }
                case AS_IS -> {
                    if (taken(whole, target, mine)) return BuilderRelayInstall.Outcome.ALREADY_HERE;
                }
                case REPLACE -> { }
            }
            if (!WholeCarriage.isValidName(target)) return BuilderRelayInstall.Outcome.UNSUPPORTED;
            BuilderRelayInstall.Outcome outcome = write(level, kind, whole, target, stageId, template);
            // Only once the room is actually on disk, and against the name it landed under — the same
            // point and the same rule as BuilderRelayInstall.write's own sidecar call.
            if (outcome == BuilderRelayInstall.Outcome.INSTALLED) {
                WholeRoomSidecars.apply(kind, whole, target, sidecars);
            }
            return outcome;
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Builder relay download: could not install whole {} '{}'", whole.id(), id, t);
            return BuilderRelayInstall.Outcome.FAILED;
        }
    }

    private static boolean taken(WholeKind whole, String id, boolean mine) {
        WholeCarriage room = new WholeCarriage(id);
        return whole == WholeKind.HALF
            ? WholeHalfTemplateStore.exists(room) || (!mine && WholeHalfRegistry.isBundled(id))
            : WholeCarriageTemplateStore.exists(room) || (!mine && WholeCarriageRegistry.isBundled(id));
    }

    private static void register(WholeKind whole, WholeCarriage wholeCarriage) {
        if (whole == WholeKind.HALF) {
            WholeHalfRegistry.register(wholeCarriage);
        } else {
            WholeCarriageRegistry.register(wholeCarriage);
        }
    }

    /** The box a build must be to land in {@code whole}: the carriage box, or the half box. */
    static CarriageDims boxFor(WholeKind whole, CarriageDims dims) {
        return whole == WholeKind.HALF ? WholeHalfTemplateStore.halfDims(dims) : dims;
    }

    private static BuilderRelayInstall.Outcome write(ServerLevel level, BuilderPhotoPaths.Kind kind, WholeKind whole,
                                                     String id, String stageId, StructureTemplate template)
            throws IOException {
        CarriageDims box = boxFor(whole, DungeonTrainWorldData.get(level.getServer().overworld()).dims());
        StructureTemplate room = kind == BuilderPhotoPaths.Kind.CONTENTS
            ? bakeOntoDefaultShell(level, whole, template, box)
            : template;
        if (room == null) return BuilderRelayInstall.Outcome.UNSUPPORTED;
        if (!CarriagePlacer.sizeMatches(room.getSize(), box)) {
            Vec3i size = room.getSize();
            LOGGER.warn("[DungeonTrain] Builder relay download: whole {} '{}' is {}x{}x{}, this world's box is {}x{}x{} — refused",
                whole.id(), id, size.getX(), size.getY(), size.getZ(), box.length(), box.height(), box.width());
            return BuilderRelayInstall.Outcome.UNSUPPORTED;
        }
        WholeCarriage wholeCarriage = WholeCarriage.of(id);
        if (whole == WholeKind.HALF) {
            WholeHalfTemplateStore.save(wholeCarriage, room);
            if (EditorDevMode.isEnabled() && WholeHalfTemplateStore.sourceTreeAvailable()) {
                WholeHalfTemplateStore.saveToSource(wholeCarriage, room);
            }
        } else {
            WholeCarriageTemplateStore.save(wholeCarriage, room);
            if (EditorDevMode.isEnabled() && WholeCarriageTemplateStore.sourceTreeAvailable()) {
                WholeCarriageTemplateStore.saveToSource(wholeCarriage, room);
            }
        }
        register(whole, wholeCarriage);
        WholeWeights.set(whole, id, ARRIVAL_WEIGHT);
        if (stageId != null && !stageId.isEmpty() && StageStore.exists(stageId)) {
            WholeWeights.setStage(whole, id, stageId);
        }
        LOGGER.info("[DungeonTrain] Builder relay download: installed whole {} '{}' from a {} build", whole.id(), id, kind.id());
        return BuilderRelayInstall.Outcome.INSTALLED;
    }

    /**
     * Stamp the pool's bundled default shell ({@code standard}, or {@code standard_half} for the half
     * pool) and then {@code contents} inside it into a scratch box, capture the box, erase it. Null
     * when the contents are not interior-sized for that box, or this install lacks the shell.
     *
     * <p>In-world rather than an offline NBT merge: two palette-indexed templates would have to be
     * remapped and de-duplicated, which is new and untested surgery, whereas a stamp-then-capture is
     * how every other capture in the mod already works. No stage scope on purpose — placeholders
     * stay placeholders so the room resolves its stage when it is stamped into a train.</p>
     */
    static StructureTemplate bakeOntoDefaultShell(ServerLevel level, WholeKind whole, StructureTemplate contents,
                                                  CarriageDims box) {
        PoolTier tier = whole == WholeKind.HALF ? PoolTier.HALF : PoolTier.ROOM;
        Optional<StructureTemplate> shell = PoolScratch.defaultShell(level, tier, box);
        if (shell.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Builder relay download: no {} shell to bake contents onto",
                PoolScratch.defaultShellId(tier));
            return null;
        }
        return PoolScratch.bake(level, shell.get(), contents, box);
    }
}
