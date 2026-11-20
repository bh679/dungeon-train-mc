package games.brennan.dungeontrain.editor.workbench;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import games.brennan.dungeontrain.editor.WorkbenchEditor;
import games.brennan.dungeontrain.portal.PortalClear;
import games.brennan.dungeontrain.portal.PortalCorridorMask;
import games.brennan.dungeontrain.portal.PortalRoomLayout;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageBlockSnapshot;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriageStampGuard;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Dimensional carriage rooms staged in the Workbench, addressed the way a Dimensions room is — so
 * the room settings, the Copies palettes, the steppers and the door pointer, all written against a
 * room <i>name</i>, reach a staged room too.
 *
 * <h2>The room name</h2>
 * <p>Every one of those paths takes a room name and reads the room's state from stores keyed by it.
 * A staged room has no name in any of them, so it is given one here: {@code workbench:<stagedId>}.
 * The colon cannot occur in a real room name (the command argument is a bare word, and every name
 * becomes a file name), so the two can never collide, and the stores that a staged room has state in
 * — {@link PortalRoomSettings#of}, {@code PortalRoomCopiesVariant}, {@code PortalRoomSizes} — check
 * {@link #stagedIdOf} first and read the staged build instead.</p>
 *
 * <h2>Where the state lives</h2>
 * <p>In the build's own sidecar document, in exactly the roles the relay carried it in: the settings
 * are the weights entry's {@code mode} tag and the palettes are the {@code copies} file. So a room
 * staged, re-set and committed installs with the settings it was left with, and nothing a staged room
 * does ever reaches a store or a source file of its own.</p>
 */
public final class WorkbenchRooms {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The room-name prefix that marks a staged room. */
    public static final String NAME_PREFIX = "workbench:";

    /** The sidecar role a room's Copies palettes travel under. */
    public static final String ROLE_COPIES = "copies";

    private WorkbenchRooms() {}

    // ---- names ----

    /** The room name {@code stagedId} answers to. */
    public static String nameFor(String stagedId) {
        return NAME_PREFIX + stagedId;
    }

    /** The staged id {@code roomName} names, or null for an ordinary room name. */
    public static @Nullable String stagedIdOf(@Nullable String roomName) {
        if (roomName == null || !roomName.startsWith(NAME_PREFIX)) return null;
        String id = roomName.substring(NAME_PREFIX.length());
        return id.isEmpty() ? null : id;
    }

    /** What to call {@code roomName} to an author — a staged room's build name, otherwise the name itself. */
    public static String displayName(String roomName) {
        String id = stagedIdOf(roomName);
        if (id == null) return roomName;
        return WorkbenchStagingStore.find(id).map(WorkbenchStagedBuild::buildName)
                .filter(n -> !n.isBlank()).orElse(id);
    }

    /** The staged room {@code player} stands in, as its room name — empty off a staged room. */
    public static Optional<String> roomUnder(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        return WorkbenchEditor.plotContaining(level, player.blockPosition())
                .flatMap(id -> WorkbenchStagedPlot.of(level, id))
                .filter(WorkbenchStagedPlot::isRoom)
                .map(p -> nameFor(p.stagedId()));
    }

    /** Every staged room with a slot, oldest first. */
    public static List<WorkbenchStagedPlot> rooms(ServerLevel level) {
        return WorkbenchStagedPlot.all(level).stream().filter(WorkbenchStagedPlot::isRoom).toList();
    }

    // ---- settings ----

    /**
     * Persist {@code settings} for {@code roomName} — into a staged room's document, or the ordinary
     * weights entry for any other room. The one writer the setting commands and the door pointer share.
     */
    public static void writeSettings(String roomName, PortalRoomSettings settings) throws IOException {
        String id = stagedIdOf(roomName);
        if (id == null) {
            TrackVariantWeights.setMode(TrackKind.PORTAL_ROOM, roomName, settings.toTag());
            return;
        }
        WorkbenchStagedBuild build = requireStaged(id);
        WorkbenchStagingStore.replaceSidecars(id, TemplateSidecars.withWeightsMode(build.sidecars(), settings.toTag()));
    }

    // ---- copies palettes ----

    /** A staged room's Copies palettes as file text, or empty when it carries none. */
    public static Optional<String> copiesText(String stagedId) {
        return WorkbenchStagingStore.find(stagedId)
                .flatMap(b -> TemplateSidecars.fileTextOf(b.sidecars(), ROLE_COPIES));
    }

    /** Replace a staged room's Copies palettes with {@code text}. */
    public static void writeCopies(String stagedId, String text) throws IOException {
        WorkbenchStagedBuild build = requireStaged(stagedId);
        WorkbenchStagingStore.replaceSidecars(stagedId, TemplateSidecars.withFileText(build.sidecars(), ROLE_COPIES, text));
    }

    // ---- size ----

    /** A staged room's box, or null when {@code stagedId} is not staged. */
    public static @Nullable Vec3i size(String stagedId) {
        return WorkbenchStagingStore.find(stagedId).map(WorkbenchStagedBuild::size).orElse(null);
    }

    /**
     * Resize staged room {@code stagedId} to {@code wanted}, clamped exactly as a Dimensions room is
     * ({@link PortalRoomLayout#clampSize}, {@link PortalRoomLayout#heldForAuthoring}) and to the build
     * height above its plot.
     *
     * <p>The room as it stands is carried across — unsaved edits included — growing at the far faces
     * and cropping there, and the result is written to the shelf straight away: a staged build has no
     * other copy of its blocks to restamp the new box from. If the bigger box would run into the next
     * staged build, the room moves to the first slot further along that fits.</p>
     *
     * @return the size applied, or null when {@code stagedId} is not a staged build with a slot
     */
    public static @Nullable Vec3i resize(ServerLevel overworld, String stagedId, Vec3i wanted, CarriageDims dims)
            throws IOException {
        Optional<WorkbenchStagedBuild> staged = WorkbenchStagingStore.find(stagedId);
        BlockPos origin = WorkbenchEditor.plotOrigin(overworld, stagedId);
        if (staged.isEmpty() || origin == null) return null;
        Vec3i current = staged.get().size();
        Vec3i clamped = heldUnderTheSky(overworld, origin, dims,
                PortalRoomLayout.clampSize(dims, PortalRoomLayout.heldForAuthoring(current, wanted)));
        if (clamped.equals(current)) return current;

        // What stands now, then the new box cut from it in place: placed back, re-read at the new size
        // (air where it grew, the far rows gone where it shrank), and the old footprint cleared.
        CompoundTag live = CarriageBlockSnapshot.captureLevel(overworld, origin, current,
                overworld.registryAccess(), 0).tag();
        Vec3i union = new Vec3i(Math.max(current.getX(), clamped.getX()),
                Math.max(current.getY(), clamped.getY()), Math.max(current.getZ(), clamped.getZ()));
        CompoundTag[] resized = new CompoundTag[1];
        CarriageStampGuard.run(() -> {
            PortalClear.clearBoxRelit(overworld, WorkbenchPlacement.boxAt(origin, union), PortalCorridorMask.NONE);
            CarriageBlockSnapshot.place(overworld, origin, live);
            resized[0] = CarriageBlockSnapshot.captureLevel(overworld, origin, clamped,
                    overworld.registryAccess(), 0).tag();
            PortalClear.clearBoxRelit(overworld, WorkbenchPlacement.boxAt(origin, union), PortalCorridorMask.NONE);
        });
        WorkbenchStagingStore.replaceBlocks(stagedId, resized[0], clamped);
        WorkbenchVariantStore.invalidate(stagedId);

        WorkbenchStagedBuild grown = staged.get().withSize(clamped);
        BlockPos landing = collides(overworld, stagedId, WorkbenchPlacement.boxAt(origin, clamped))
                ? WorkbenchEditor.allocate(overworld, grown, origin.getX())
                : origin;
        WorkbenchEditor.stampAt(overworld, grown, landing);
        LOGGER.info("[DungeonTrain] Workbench room '{}' resized {} -> {}{}", stagedId, current, clamped,
                landing.equals(origin) ? "" : " and moved to " + landing.toShortString());
        return clamped;
    }

    /** Whether {@code box} overlaps any recorded plot but {@code stagedId}'s own. */
    private static boolean collides(ServerLevel overworld, String stagedId, BoundingBox box) {
        for (var e : DungeonTrainWorldData.get(overworld).workbenchPlotBoxesView().entrySet()) {
            if (e.getKey().equalsIgnoreCase(stagedId)) continue;
            BoundingBox other = WorkbenchPlacement.boxOf(e.getValue());
            if (other != null && other.intersects(box)) return true;
        }
        return false;
    }

    /** {@code size} with its height held to what fits between the plot's floor and the build ceiling. */
    private static Vec3i heldUnderTheSky(ServerLevel overworld, BlockPos origin, CarriageDims dims, Vec3i size) {
        int ceiling = Math.max(PortalRoomLayout.minHeight(dims), overworld.getMaxBuildHeight() - origin.getY());
        return size.getY() <= ceiling ? size : new Vec3i(size.getX(), ceiling, size.getZ());
    }

    private static WorkbenchStagedBuild requireStaged(String stagedId) throws IOException {
        return WorkbenchStagingStore.find(stagedId).orElseThrow(
                () -> new IOException("No staged build '" + stagedId.toLowerCase(Locale.ROOT) + "'"));
    }
}
