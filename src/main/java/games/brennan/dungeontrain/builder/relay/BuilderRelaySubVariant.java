package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.CarriageContentsGroupStore;
import games.brennan.dungeontrain.editor.PortalRoomEditor;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.editor.TrackVariantGroupStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantGroup;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsGroup;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageContentsWeights;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Locale;
import java.util.Optional;

/**
 * Files a freshly downloaded build under a variant parent, as one of its sub-variants.
 *
 * <p>The step after {@link BuilderRelayInstall}: the template is on disk and registered, and this
 * puts it into {@code <parent>.group.json} so it spawns through the parent rather than as a top-level
 * entry of its own. Somebody else's build arriving as a sibling of the shipped templates is the thing
 * this avoids — a reviewer loading a dozen player builds wants them gathered under one row, not a
 * dozen new rows.</p>
 *
 * <p>Only the two kinds with sub-variants: contents and portal rooms. The guards are the ones the
 * editor's own {@code group add} verbs apply (single-hop nesting, no cycles, no built-in parents), so
 * a build filed here is a build the editor could have filed by hand.</p>
 *
 * <p><b>A parent that does not exist is made.</b> The default parent is {@link #DEFAULT_PARENT_ID},
 * and on first use it is created with {@code selfWeight 0} — the bundled {@code traps} group is the
 * precedent: a parent with nothing of its own to stamp, existing only to hold its members. Contents
 * parents need no template file (the registry picks parents up from {@code .group.json} basenames).
 * A portal room does need one — rooms are discovered from {@code .nbt} files and the editor stamps
 * the parent's plot — so a missing room parent is written from the build being filed, and never
 * spawns as itself because of that same {@code selfWeight 0}.</p>
 */
public final class BuilderRelaySubVariant {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The parent every player build lands under until the reviewer picks another. */
    public static final String DEFAULT_PARENT_ID = "user_builds";
    /** Its display label — the id stays the file basename, see {@code TemplateMeta.name}. */
    public static final String DEFAULT_PARENT_LABEL = "User builds";
    /** A parent made here never stamps itself; only its members spawn. */
    static final int NEW_PARENT_SELF_WEIGHT = 0;

    private BuilderRelaySubVariant() {}

    /** Whether a build of {@code kind} can be filed as a sub-variant at all. */
    public static boolean supports(BuilderPhotoPaths.Kind kind) {
        return kind == BuilderPhotoPaths.Kind.CONTENTS || kind == BuilderPhotoPaths.Kind.PORTAL_ROOM;
    }

    /**
     * Put {@code childId} — already installed as {@code kind} — under {@code parentId}.
     *
     * @param level    the overworld, for the plot relayout a room-group change needs; contents groups
     *                 do not touch the world and accept null
     * @param template the build's own blocks, used only to give a portal-room parent a file when it
     *                 has to be created
     * @return true when the build is now a member of the parent's group; false when the join was
     *         refused or failed, in which case the build stays where it installed — top level
     */
    public static boolean join(ServerLevel level, BuilderPhotoPaths.Kind kind, String childId,
                               String parentId, StructureTemplate template) {
        if (kind == null || !supports(kind)) return false;
        if (childId == null || childId.isBlank() || parentId == null || parentId.isBlank()) return false;
        String child = childId.trim().toLowerCase(Locale.ROOT);
        String parent = parentId.trim().toLowerCase(Locale.ROOT);
        if (child.equals(parent)) {
            return refused(kind, child, parent, "a build cannot be a sub-variant of itself");
        }
        try {
            return switch (kind) {
                case CONTENTS -> joinContents(child, parent);
                case PORTAL_ROOM -> joinPortalRoom(level, child, parent, template);
                default -> false;
            };
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Builder relay download: could not file {} '{}' under '{}'",
                kind.id(), child, parent, t);
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Contents
    // ------------------------------------------------------------------

    private static boolean joinContents(String child, String parent) throws IOException {
        if (CarriageContentsGroupStore.exists(child)) {
            return refused(BuilderPhotoPaths.Kind.CONTENTS, child, parent,
                "the build is itself a contents group — nesting is single-hop");
        }
        if (CarriageContentsGroupStore.allChildIds().contains(parent)) {
            return refused(BuilderPhotoPaths.Kind.CONTENTS, child, parent,
                "the parent is already a member of another group");
        }
        Optional<String> current = CarriageContentsGroupStore.findParentOf(child);
        if (current.isPresent() && !current.get().equals(parent)) {
            return refused(BuilderPhotoPaths.Kind.CONTENTS, child, parent,
                "the build is already a sub-variant of '" + current.get() + "'");
        }

        Optional<CarriageContents> existing = CarriageContentsRegistry.find(parent);
        if (existing.isPresent() && existing.get().isBuiltin()) {
            return refused(BuilderPhotoPaths.Kind.CONTENTS, child, parent,
                "built-in contents cannot be a group parent");
        }
        boolean created = false;
        if (existing.isEmpty()) {
            // No template needed: the registry lists group parents from their .group.json basename,
            // and the group written below is what makes this name one of those on the next reload.
            CarriageContents.Custom made = new CarriageContents.Custom(parent);
            if (!CarriageContentsRegistry.register(made)) {
                return refused(BuilderPhotoPaths.Kind.CONTENTS, child, parent, "the parent name is reserved");
            }
            created = true;
        }

        CarriageContentsGroup group = CarriageContentsGroupStore.get(parent)
            .orElse(CarriageContentsGroup.EMPTY.withSelfWeight(NEW_PARENT_SELF_WEIGHT));
        CarriageContentsGroup updated = group.withMember(
            new CarriageContentsGroup.Member(child, CarriageContentsGroup.DEFAULT_WEIGHT));
        CarriageContentsGroupStore.save(parent, updated);
        if (created) labelNewContentsParent(parent);
        LOGGER.info("[DungeonTrain] Builder relay download: filed contents '{}' under '{}' ({} member{})",
            child, parent, updated.members().size(), updated.members().size() == 1 ? "" : "s");
        return true;
    }

    /** The default parent wears its two words; any other freshly made parent keeps its id. */
    private static void labelNewContentsParent(String parent) {
        if (!DEFAULT_PARENT_ID.equals(parent)) return;
        try {
            CarriageContentsWeights.setName(parent, DEFAULT_PARENT_LABEL);
        } catch (IOException e) {
            // A label is a nicety; the group is the thing that matters and it is already written.
            LOGGER.warn("[DungeonTrain] Builder relay download: could not label '{}'", parent, e);
        }
    }

    // ------------------------------------------------------------------
    // Portal rooms
    // ------------------------------------------------------------------

    private static boolean joinPortalRoom(ServerLevel level, String child, String parent,
                                          StructureTemplate template) throws IOException {
        TrackKind kind = TrackKind.PORTAL_ROOM;
        if (level == null) {
            return refused(BuilderPhotoPaths.Kind.PORTAL_ROOM, child, parent, "no world to relay the room row out in");
        }
        if (TrackKind.DEFAULT_NAME.equals(parent) || TrackKind.DEFAULT_NAME.equals(child)) {
            return refused(BuilderPhotoPaths.Kind.PORTAL_ROOM, child, parent,
                "'default' is the fallback room and is neither a parent nor a sub-variant");
        }
        if (TrackVariantGroupStore.exists(kind, child)) {
            return refused(BuilderPhotoPaths.Kind.PORTAL_ROOM, child, parent,
                "the build has sub-variants of its own — nesting is single-hop");
        }
        if (TrackVariantGroupStore.allChildIds(kind).contains(parent)) {
            return refused(BuilderPhotoPaths.Kind.PORTAL_ROOM, child, parent,
                "the parent is itself a sub-variant");
        }
        Optional<String> current = TrackVariantGroupStore.findParentOf(kind, child);
        if (current.isPresent() && !current.get().equals(parent)) {
            return refused(BuilderPhotoPaths.Kind.PORTAL_ROOM, child, parent,
                "the build is already a sub-variant of '" + current.get() + "'");
        }

        boolean created = false;
        if (TrackVariantRegistry.find(kind, parent).isEmpty()) {
            if (template == null) {
                return refused(BuilderPhotoPaths.Kind.PORTAL_ROOM, child, parent,
                    "the parent room does not exist and there is no template to make it from");
            }
            // A room parent has to be a room: discovery is a .nbt scan and the editor stamps the
            // parent's plot. The build's own blocks stand in, and selfWeight 0 keeps them from ever
            // being picked as the parent — only the members spawn.
            PortalRoomTemplateStore.save(parent, template);
            TrackVariantRegistry.register(kind, parent);
            created = true;
        }

        TrackVariantGroup group = TrackVariantGroupStore.get(kind, parent)
            .orElse(TrackVariantGroup.EMPTY.withSelfWeight(NEW_PARENT_SELF_WEIGHT));
        TrackVariantGroup updated = group.withMember(
            new TrackVariantGroup.Member(child, TrackVariantGroup.DEFAULT_WEIGHT));

        // Membership decides where a plot sits, so the write goes through the same relayout the
        // editor's own group verbs use — otherwise the row fills with rooms nothing will clear.
        CarriageDims dims = DungeonTrainWorldData.get(level).dims();
        IOException[] failure = new IOException[1];
        PortalRoomEditor.relayout(level, dims, () -> {
            try {
                TrackVariantGroupStore.save(kind, parent, updated);
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        if (created && DEFAULT_PARENT_ID.equals(parent)) {
            try {
                TrackVariantWeights.setName(kind, parent, DEFAULT_PARENT_LABEL);
            } catch (IOException e) {
                LOGGER.warn("[DungeonTrain] Builder relay download: could not label room '{}'", parent, e);
            }
        }
        LOGGER.info("[DungeonTrain] Builder relay download: filed portal room '{}' under '{}' ({} sub-variant{})",
            child, parent, updated.members().size(), updated.members().size() == 1 ? "" : "s");
        return true;
    }

    private static boolean refused(BuilderPhotoPaths.Kind kind, String child, String parent, String why) {
        LOGGER.warn("[DungeonTrain] Builder relay download: {} '{}' installed at top level, not under '{}' — {}",
            kind.id(), child, parent, why);
        return false;
    }
}
