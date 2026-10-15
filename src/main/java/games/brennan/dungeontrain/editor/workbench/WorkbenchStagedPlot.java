package games.brennan.dungeontrain.editor.workbench;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.relay.BuilderRelayKinds;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import games.brennan.dungeontrain.editor.WorkbenchEditor;
import games.brennan.dungeontrain.portal.PortalRoomSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One staged build where it stands — the answer every per-type editor aid asks the Workbench for:
 * what kind the build is, where its plot is, and how big.
 *
 * <p>Each aid in a type's own editor mode knows its kind from the category it runs in. In the
 * Workbench the category says nothing about the kind — builds of every kind stand side by side — so
 * the kind comes from the build itself, the relay's own kind string, and this is the one place that
 * reads it. Door ghosts, the block-variant plot and the room settings all go through here rather
 * than each re-deriving it.</p>
 *
 * <p>A staged contents build stands interior-only at its origin (the Workbench stamps no shell), so
 * its variant cells — interior-relative in the contents sidecar — line up with the origin as they
 * are. Only the doors, which a contents plot draws on the shell around the interior, need the box
 * grown ({@link #shellOrigin}).</p>
 *
 * @param stagedId the staged build
 * @param kind     the kind the relay said it is, or null for a relay kind this install cannot place
 * @param subKind  the relay's sub kind ({@code TrackKind} id, part kind), or blank
 * @param origin   the plot's recorded origin
 * @param size     {@code (length, height, width)} of the build
 */
public record WorkbenchStagedPlot(String stagedId, @Nullable BuilderPhotoPaths.Kind kind, String subKind,
                                  BlockPos origin, Vec3i size) {

    /** {@code stagedId}'s plot, or empty when it is not staged or has no recorded slot. */
    public static Optional<WorkbenchStagedPlot> of(ServerLevel level, String stagedId) {
        Optional<WorkbenchStagedBuild> build = WorkbenchStagingStore.find(stagedId);
        if (build.isEmpty()) return Optional.empty();
        BlockPos origin = WorkbenchEditor.plotOrigin(level, stagedId);
        if (origin == null) return Optional.empty();
        return Optional.of(of(build.get(), origin));
    }

    /** The plot of {@code build} standing at {@code origin}. */
    public static WorkbenchStagedPlot of(WorkbenchStagedBuild build, BlockPos origin) {
        return new WorkbenchStagedPlot(build.stagedId(), BuilderRelayKinds.kindOf(build.relayKind()),
                build.subKind(), origin, build.size());
    }

    /** Every staged build that has a recorded slot, oldest first. */
    public static List<WorkbenchStagedPlot> all(ServerLevel level) {
        List<WorkbenchStagedPlot> out = new ArrayList<>();
        for (WorkbenchStagedBuild build : WorkbenchStagingStore.list()) {
            BlockPos origin = WorkbenchEditor.plotOrigin(level, build.stagedId());
            if (origin != null) out.add(of(build, origin));
        }
        return out;
    }

    /** Whether this is a dimensional carriage room — the one staged kind that repeats and has room settings. */
    public boolean isRoom() {
        return kind == BuilderPhotoPaths.Kind.PORTAL_ROOM;
    }

    /** The origin of the carriage shell around a contents build's interior; the plain origin otherwise. */
    public BlockPos shellOrigin() {
        return kind == BuilderPhotoPaths.Kind.CONTENTS ? origin.offset(-1, -1, -1) : origin;
    }

    /** The size of that shell — two blocks bigger on every axis for contents. */
    public Vec3i shellSize() {
        return kind == BuilderPhotoPaths.Kind.CONTENTS ? size.offset(2, 2, 2) : size;
    }

    /**
     * A staged room's settings: the {@code mode} tag its sidecar document's weights entry carries,
     * which is where a room's door offsets, walls, sky and the rest travel on the relay. A build
     * uploaded before rooms carried one reads as the defaults.
     */
    public static PortalRoomSettings roomSettings(String stagedId) {
        return WorkbenchStagingStore.find(stagedId)
                .flatMap(b -> TemplateSidecars.weightsModeOf(b.sidecars()))
                .map(PortalRoomSettings::parse)
                .orElse(PortalRoomSettings.DEFAULT);
    }
}
