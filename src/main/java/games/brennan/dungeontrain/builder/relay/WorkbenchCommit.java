package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.TemplateLootPrefabs;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import games.brennan.dungeontrain.editor.WorkbenchEditor;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagedBuild;
import games.brennan.dungeontrain.editor.workbench.WorkbenchStagingStore;
import games.brennan.dungeontrain.net.PrefabRegistrySyncPacket;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageSnapshotTemplate;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Turn a Workbench build into a template: the author's chosen kind, sub kind, name and variant parent,
 * through the same install path a relay download takes ({@link BuilderRelayInstall}, the sidecars, the
 * loot prefabs, the credit, {@link BuilderRelaySubVariant}) — then off the shelf and out of the sky.
 *
 * <p>This is the step the Workbench exists for. A download decides a build's kind from the relay row
 * and installs on the spot; here the author has stood in the build first and says what it is. When
 * the kind they pick is not the one the relay called it, the sidecar document is retargeted
 * ({@link TemplateSidecars#retargeted}) so only the roles the new kind can hold come along.</p>
 *
 * <p>Two questions can come back before anything is written, exactly as on a download: a name this
 * install already uses ({@code ALREADY_HERE} / {@code NAME_TAKEN}, with the taken names attached),
 * and loot prefabs that collide with this install's own ({@code PREFAB_CONFLICT}). The client answers
 * and presses again with the answer attached.</p>
 *
 * <p>What is deliberately <em>not</em> done: the committed template is not linked back to its relay
 * row. The shelf never kept the owner secret, and a build the author has edited and possibly re-kinded
 * is a new thing as far as the relay is concerned; a later save of it uploads as a new build.</p>
 */
public final class WorkbenchCommit {

    private static final Logger LOGGER = LogUtils.getLogger();

    private WorkbenchCommit() {}

    /**
     * What the author asked for.
     *
     * @param name       the template id the build lands under
     * @param parentId   the variant parent to file it under, the whole-room sentinel, or blank
     * @param resolution {@code AS_IS} refuses a taken name; {@code REPLACE} writes over it
     */
    public record Request(String stagedId, BuilderPhotoPaths.Kind kind, String subKind, String name,
                          String parentId, BuilderRelayInstall.Resolution resolution,
                          BuilderRelayDownload.PrefabAnswer prefabs) {
        public Request {
            subKind = subKind == null ? "" : subKind;
            name = name == null ? "" : name.trim();
            parentId = parentId == null ? "" : parentId;
            resolution = resolution == null ? BuilderRelayInstall.Resolution.AS_IS : resolution;
            prefabs = prefabs == null ? BuilderRelayDownload.PrefabAnswer.UNASKED : prefabs;
        }
    }

    /** The kinds a staged build may be committed as. */
    public static boolean commitsAs(BuilderPhotoPaths.Kind kind) {
        return kind != null && kind != BuilderPhotoPaths.Kind.CHUNK_FRAME && kind != BuilderPhotoPaths.Kind.LOST_CITY
                && kind != BuilderPhotoPaths.Kind.CARRIAGE_GROUP;
    }

    /** Whether {@code subKind} names a real id-space for {@code kind} — the same refusal the installer makes. */
    public static boolean subKindValid(BuilderPhotoPaths.Kind kind, String subKind) {
        return switch (kind) {
            case PART -> CarriagePartKind.fromId(subKind) != null;
            case TRACK -> {
                TrackKind track = TrackKind.fromId(subKind);
                yield track != null && track != TrackKind.PORTAL_ROOM;
            }
            default -> true;
        };
    }

    /** Commit, on the server thread. */
    public static BuilderRelayDownload.Result commit(ServerLevel level, Request request) {
        ServerLevel overworld = level.getServer().overworld();
        Optional<WorkbenchStagedBuild> stagedOpt = WorkbenchStagingStore.find(request.stagedId());
        if (stagedOpt.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Workbench commit: no staged build '{}'", request.stagedId());
            return BuilderRelayDownload.Result.of(BuilderRelayDownload.Outcome.GONE);
        }
        WorkbenchStagedBuild staged = stagedOpt.get();
        BuilderPhotoPaths.Kind kind = request.kind();
        if (!commitsAs(kind) || !subKindValid(kind, request.subKind()) || request.name().isEmpty()) {
            return BuilderRelayDownload.Result.of(BuilderRelayDownload.Outcome.UNSUPPORTED);
        }
        Optional<CompoundTag> blocks = WorkbenchStagingStore.readBlocks(staged.stagedId());
        if (blocks.isEmpty()) {
            LOGGER.warn("[DungeonTrain] Workbench commit: '{}' has no blocks file", staged.stagedId());
            return BuilderRelayDownload.Result.of(BuilderRelayDownload.Outcome.FAILED);
        }

        // The name, asked about before anything is written — the download's two-press protocol.
        BuilderRelayInstall.Outcome refusal = BuilderRelayInstall.refusal(kind, request.name(), request.subKind(),
                request.resolution(), "", staged.mine());
        if (refusal != null) return refused(refusal, kind, request, staged);
        if (!request.prefabs().resolved()) {
            List<TemplateLootPrefabs.Conflict> conflicts = TemplateLootPrefabs.conflicts(staged.lootPrefabs());
            if (!conflicts.isEmpty()) {
                return BuilderRelayDownload.Result.askingAbout(kind, request.name(), request.subKind(), conflicts);
            }
        }
        // Which of the build's prefabs this install already holds differently and will keep its own of —
        // reported to the author, since the command path has no conflict screen to ask them with.
        List<TemplateLootPrefabs.Conflict> keptLocal = request.prefabs().resolved()
                ? TemplateLootPrefabs.conflicts(staged.lootPrefabs()).stream()
                    .filter(c -> !request.prefabs().overwrite().contains(c.id()) && !request.prefabs().renames().containsKey(c.id()))
                    .toList()
                : List.of();

        StructureTemplate template;
        try {
            HolderGetter<Block> lookup = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            template = CarriageSnapshotTemplate.toTemplate(blocks.get(), lookup);
        } catch (Throwable t) {
            LOGGER.warn("[DungeonTrain] Workbench commit: '{}' would not convert: {}", staged.stagedId(), t.toString());
            return BuilderRelayDownload.Result.of(BuilderRelayDownload.Outcome.FAILED);
        }

        BuilderPhotoPaths.Kind fromKind = BuilderRelayKinds.kindOf(staged.relayKind());
        String sidecars = TemplateSidecars.retargeted(staged.sidecars(), fromKind, staged.subKind(), kind, request.subKind());
        boolean wholeRoom = BuilderRelayWholeRoom.requested(request.parentId()) && BuilderRelayWholeRoom.supports(kind);
        BuilderRelayInstall.Outcome installed = wholeRoom
                ? BuilderRelayWholeRoom.install(level, kind, request.name(), staged.stage(), template,
                        request.resolution(), "", sidecars, staged.mine())
                : BuilderRelayInstall.install(kind, request.name(), request.subKind(), staged.stage(), template,
                        request.resolution(), "", sidecars, staged.mine());
        if (installed != BuilderRelayInstall.Outcome.INSTALLED) return refused(installed, kind, request, staged);

        BuilderRelayDownload.credit(kind, request.subKind(), request.name(),
                new BuildCredits.Credit(staged.ownerUuid(), staged.ownerName(), System.currentTimeMillis()),
                staged.mine(), TemplateSidecars.hasCredit(sidecars));
        List<String> prefabsWritten = TemplateLootPrefabs.install(staged.lootPrefabs(),
                request.prefabs().overwrite(), request.prefabs().renames());
        if (wholeRoom) {
            TemplateLootPrefabs.relinkPlot(TemplateSidecars.wholeRoomPlotKey(request.name()),
                    request.prefabs().renames(), prefabsWritten);
        } else {
            TemplateLootPrefabs.relink(kind, request.subKind(), request.name(), request.prefabs().renames(), prefabsWritten);
        }
        if (!prefabsWritten.isEmpty()) {
            PacketDistributor.sendToAllPlayers(PrefabRegistrySyncPacket.fromRegistries());
        }
        if (!wholeRoom && !request.parentId().isBlank() && BuilderRelaySubVariant.supports(kind)) {
            BuilderRelaySubVariant.join(level, kind, request.name(), request.parentId(), template);
        }

        // Off the shelf and out of the sky: it is a template now, with a plot of its own in its category.
        try {
            WorkbenchEditor.remove(overworld, staged.stagedId());
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Workbench commit: '{}' installed but could not be cleared off the shelf: {}",
                    staged.stagedId(), e.toString());
        }
        LOGGER.info("[DungeonTrain] Workbench commit: '{}' installed as {} '{}'{}", staged.stagedId(), kind.id(),
                request.name(), request.subKind().isEmpty() ? "" : " (" + request.subKind() + ")");
        return new BuilderRelayDownload.Result(BuilderRelayDownload.Outcome.INSTALLED, kind, request.name(), request.subKind(),
                List.of(), keptLocal);
    }

    private static BuilderRelayDownload.Result refused(BuilderRelayInstall.Outcome why, BuilderPhotoPaths.Kind kind,
                                                       Request request, WorkbenchStagedBuild staged) {
        BuilderRelayDownload.Outcome outcome = switch (why) {
            case ALREADY_HERE -> BuilderRelayDownload.Outcome.ALREADY_HERE;
            case NAME_TAKEN -> BuilderRelayDownload.Outcome.NAME_TAKEN;
            case UNSUPPORTED -> BuilderRelayDownload.Outcome.UNSUPPORTED;
            default -> BuilderRelayDownload.Outcome.FAILED;
        };
        BuilderRelayDownload.Result result = new BuilderRelayDownload.Result(outcome, kind, request.name(), request.subKind());
        return outcome == BuilderRelayDownload.Outcome.ALREADY_HERE || outcome == BuilderRelayDownload.Outcome.NAME_TAKEN
                ? result.withTakenNames(BuilderRelayInstall.takenNames(kind, request.subKind(), staged.mine()))
                : result;
    }
}
