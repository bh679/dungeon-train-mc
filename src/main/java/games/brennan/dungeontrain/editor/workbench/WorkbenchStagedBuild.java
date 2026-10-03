package games.brennan.dungeontrain.editor.workbench;

import net.minecraft.core.Vec3i;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One relay build parked in the Workbench: what the relay said it was, who made it, and the raw
 * sidecar and loot-prefab documents it arrived with — everything a later commit needs to install
 * it as whatever kind the author decides on.
 *
 * <p>Immutable. The blocks themselves are not here: they are a snapshot tag on disk beside this
 * record ({@link WorkbenchStagingStore#readBlocks}), because a build can be a megabyte and this
 * record is read every time the category's models are listed.</p>
 *
 * @param stagedId   the id the Workbench knows the build by — unique among staged builds, never a
 *                   template id in any kind's store
 * @param relayId    the relay row it came from
 * @param relayKind  the relay's kind string ({@code BuilderRelayKinds}), the default a commit offers
 * @param subKind    the relay's sub kind ({@code TrackKind} id, part kind, …), or blank
 * @param buildName  what the relay called it — the default name a commit offers
 * @param stage      the stage link it carried, or blank
 * @param ownerUuid  whose build it is
 * @param ownerName  that player's display name when the build was staged, or blank
 * @param mine       whether the stager is the owner
 * @param size       {@code (length, height, width)} of the snapshot — the plot footprint
 * @param sidecars   the relay's sidecar document verbatim, or blank
 * @param lootPrefabs the loot prefabs it carried, id → document
 * @param stagedAt   epoch millis of the staging
 */
public record WorkbenchStagedBuild(String stagedId, int relayId, String relayKind, String subKind,
                                   String buildName, String stage, String ownerUuid, String ownerName,
                                   boolean mine, Vec3i size, String sidecars,
                                   Map<String, String> lootPrefabs, long stagedAt) {

    /** The longest a staged id may be — bounded so it fits every packet field it travels in. */
    public static final int MAX_ID_LENGTH = 48;

    public WorkbenchStagedBuild {
        Objects.requireNonNull(stagedId, "stagedId");
        Objects.requireNonNull(size, "size");
        relayKind = relayKind == null ? "" : relayKind;
        subKind = subKind == null ? "" : subKind;
        buildName = buildName == null ? "" : buildName;
        stage = stage == null ? "" : stage;
        ownerUuid = ownerUuid == null ? "" : ownerUuid;
        ownerName = ownerName == null ? "" : ownerName;
        sidecars = sidecars == null ? "" : sidecars;
        lootPrefabs = lootPrefabs == null ? Map.of() : Map.copyOf(lootPrefabs);
    }

    /** This build with a different footprint — after a resize nothing else about it changes. */
    public WorkbenchStagedBuild withSize(Vec3i newSize) {
        return new WorkbenchStagedBuild(stagedId, relayId, relayKind, subKind, buildName, stage,
                ownerUuid, ownerName, mine, newSize, sidecars, lootPrefabs, stagedAt);
    }

    /**
     * A staged id for {@code buildName} from relay row {@code relayId}: the name lower-cased and
     * reduced to {@code [a-z0-9_-]}, with the relay id appended so two builds of the same name from
     * different rows never collide. Never blank — a build with no usable characters is {@code build-<id>}.
     */
    public static String idFor(String buildName, int relayId) {
        String base = buildName == null ? "" : buildName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        if (base.isEmpty()) base = "build";
        String suffix = "-" + relayId;
        int room = MAX_ID_LENGTH - suffix.length();
        if (base.length() > room) base = base.substring(0, room);
        return base + suffix;
    }
}
