package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageContentsVariantBlocks;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.CarriageVariantBlocks;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.TemplateSidecars;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.portal.PortalRoomSizes;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageContents;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriageGroup;
import games.brennan.dungeontrain.train.CarriageGroupRegistry;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.train.CarriageVariant;
import games.brennan.dungeontrain.train.CarriageVariantRegistry;
import games.brennan.dungeontrain.train.CarriageWeights;
import games.brennan.dungeontrain.train.WholeCarriage;
import games.brennan.dungeontrain.train.WholeCarriageRegistry;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;

/**
 * Writes a template downloaded from the relay into this install's own library, so the editors can
 * open it like anything else.
 *
 * <p>The mirror of {@code BuilderSave}'s save arms with the world capture taken out. Each arm here
 * does what its counterpart there does once the blocks are in hand — write the file, then register
 * the id — and deliberately in that order, so a reload landing between the two never leaves a
 * registered id with nothing on disk. Keeping the two shapes side by side is the point: a downloaded
 * build must land in exactly the store a locally-saved one would, or it is filed where nothing
 * looks.</p>
 *
 * <p><b>Volumes are already right.</b> Each kind uploads the volume its own store keeps — a room
 * uploads its interior, a part its kind's footprint (see {@code EditorRelayWrite.capturedOrigin}) —
 * so there is no re-cropping to do here, only the choice of store. That choice is the whole risk in
 * this class, which is why {@link BuilderRelayKinds#kindOf} states it once.</p>
 *
 * <p><b>The sidecars come back too.</b> A build's {@code .variants.json}, part assignments,
 * contents-allow list, copies variant, container links and {@code weights.json} entry ride along in
 * their own relay field and are written by {@link TemplateSidecars#apply} once the template itself is
 * on disk — that ordering deliberately, so an interruption never leaves sidecars beside a template
 * that is not there. Loot prefabs and contents-pool definitions are the exception and stay behind:
 * they are library objects shared by every template, and installing one build must not overwrite an
 * unrelated local prefab. An empty document — an older build, or an older relay — changes nothing,
 * which is what leaves this install's own sidecars alone rather than resetting them.</p>
 */
public final class BuilderRelayInstall {

    private static final Logger LOGGER = LogUtils.getLogger();

    private BuilderRelayInstall() {}

    /**
     * What became of an install.
     *
     * <p>{@link #ALREADY_HERE} is not a failure and not a silent success: this install already has a
     * template of that kind under that name, and overwriting it is the one outcome here that can
     * destroy work — the local copy may be newer than the relay's, or a different build that merely
     * shares a name. The player is told instead. The case the download exists for (a world that has
     * never seen the build) never reaches it.</p>
     */
    public enum Outcome { INSTALLED, ALREADY_HERE, NAME_TAKEN, UNSUPPORTED, FAILED }

    /**
     * How to deal with a name this install already uses.
     *
     * <p>{@link #AS_IS} is what a first press means: stop and say so, because overwriting is the one
     * outcome here that can destroy work. The other three are the player's answers to that, and they
     * differ in more than which file moves — see {@code BuilderRelayDownload} on what each does to
     * the build's link back to its relay row.</p>
     */
    public enum Resolution {
        /** Refuse on a collision. */
        AS_IS,
        /** Overwrite the local template with the downloaded one. */
        REPLACE,
        /** Move the local template out of the way, then install under the original name. */
        RENAME_EXISTING,
        /** Install the downloaded build under a different name, leaving the local one alone. */
        LOAD_AS_NEW
    }

    /**
     * Write {@code template} into the store {@code kind} names, under {@code id}, and register it.
     *
     * @param subKind the id-space {@code id} lives in — a {@link CarriagePartKind} or a
     *                {@link TrackKind} id — and ignored by the kinds with one flat namespace. A part
     *                or track with no sub kind names nothing openable ({@code standard} is both a
     *                floor and a door), so those arms refuse rather than guess.
     * @param stageId the stage the build was authored in, linked for a whole carriage exactly as a
     *                save links it; ignored when empty or unknown to this install
     */
    public static Outcome install(BuilderPhotoPaths.Kind kind, String id, String subKind,
                                  String stageId, StructureTemplate template) {
        return install(kind, id, subKind, stageId, template, Resolution.AS_IS, "", "");
    }

    /**
     * As {@link #install(BuilderPhotoPaths.Kind, String, String, String, StructureTemplate)}, with the
     * player's answer to a name collision.
     *
     * @param resolution what to do about a name already in use here
     * @param newName    the name the player chose — the local template's new name for
     *                   {@link Resolution#RENAME_EXISTING}, the downloaded build's for
     *                   {@link Resolution#LOAD_AS_NEW}, and ignored otherwise
     */
    public static Outcome install(BuilderPhotoPaths.Kind kind, String id, String subKind,
                                  String stageId, StructureTemplate template,
                                  Resolution resolution, String newName) {
        return install(kind, id, subKind, stageId, template, resolution, newName, "");
    }

    /**
     * As above, with the build's sidecar document — see {@link TemplateSidecars}.
     *
     * @param sidecars the document the relay handed back; blank leaves this install's own sidecars
     *                 for that template untouched
     */
    public static Outcome install(BuilderPhotoPaths.Kind kind, String id, String subKind,
                                  String stageId, StructureTemplate template,
                                  Resolution resolution, String newName, String sidecars) {
        if (kind == null || id == null || id.isEmpty() || template == null) return Outcome.UNSUPPORTED;
        Resolution how = resolution == null ? Resolution.AS_IS : resolution;
        String chosen = newName == null ? "" : newName.trim();
        try {
            if (how == Resolution.LOAD_AS_NEW) {
                // The downloaded build takes the new name outright. Nothing local moves, so the only
                // question is whether the name the player picked is free.
                if (chosen.isEmpty()) return Outcome.UNSUPPORTED;
                if (occupied(kind, chosen, subKind)) return Outcome.NAME_TAKEN;
                return write(kind, chosen, subKind, stageId, template, sidecars);
            }
            if (how == Resolution.RENAME_EXISTING) {
                if (chosen.isEmpty()) return Outcome.UNSUPPORTED;
                if (occupied(kind, chosen, subKind)) return Outcome.NAME_TAKEN;
                // Move the local one aside FIRST. If that fails the download is abandoned, which is
                // the safe direction: the player still has exactly what they had.
                if (!renameLocal(kind, id, subKind, chosen)) return Outcome.FAILED;
                return write(kind, id, subKind, stageId, template, sidecars);
            }
            if (how == Resolution.AS_IS && occupied(kind, id, subKind)) return Outcome.ALREADY_HERE;
            // REPLACE falls through with no check at all — overwriting is what was asked for.
            return write(kind, id, subKind, stageId, template, sidecars);
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Builder relay download: could not install {} '{}'", kind.id(), id, t);
            return Outcome.FAILED;
        }
    }

    /**
     * Write the template into its store, register the id, then lay its sidecars down beside it — the
     * collision question already settled.
     *
     * <p>Sidecars last, and only once the template landed: a {@code .variants.json} next to no
     * {@code .nbt} is a file nothing reads and nothing cleans up, whereas a template that briefly has
     * no sidecars is just a template at its defaults. Written under {@code id} — the name the build
     * actually landed under, which for {@code LOAD_AS_NEW} is not the name it was uploaded as.</p>
     */
    private static Outcome write(BuilderPhotoPaths.Kind kind, String id, String subKind,
                                 String stageId, StructureTemplate template,
                                 String sidecars) throws IOException {
        Outcome outcome = switch (kind) {
            case CARRIAGE -> installCarriage(id, stageId, template);
            case CARRIAGE_GROUP -> installGroup(id, template);
            case CONTENTS -> installContents(id, template);
            case PART -> installPart(id, subKind, template);
            case TRACK -> installTrack(id, subKind, template);
            case PORTAL_ROOM -> installPortalRoom(id, template);
        };
        if (outcome == Outcome.INSTALLED) TemplateSidecars.apply(kind, subKind, id, sidecars);
        return outcome;
    }

    /**
     * Whether this install already has a template of {@code kind} under {@code id} — a saved copy or
     * a bundled one.
     *
     * <p>Bundled counts. A downloaded build written under a built-in's name would not overwrite the
     * jar's copy but WOULD shadow it for this whole install, which is a surprising thing to have
     * happened by pressing a button on somebody else's build.</p>
     */
    public static boolean occupied(BuilderPhotoPaths.Kind kind, String id, String subKind) {
        if (kind == null || id == null || id.isEmpty()) return false;
        return switch (kind) {
            case CARRIAGE -> {
                CarriageVariant variant = CarriageVariantRegistry.find(id).orElse(null);
                yield WholeCarriageTemplateStore.exists(WholeCarriage.of(id))
                        || (variant != null
                            && (CarriageTemplateStore.exists(variant) || CarriageTemplateStore.bundled(variant)));
            }
            case CARRIAGE_GROUP -> CarriageGroupTemplateStore.exists(CarriageGroup.of(id));
            case CONTENTS -> {
                CarriageContents existing = CarriageContentsRegistry.find(id).orElse(null);
                yield existing != null
                        && (CarriageContentsStore.exists(existing) || CarriageContentsStore.bundled(existing));
            }
            case PART -> {
                CarriagePartKind partKind = CarriagePartKind.fromId(subKind);
                yield partKind != null
                        && (CarriagePartTemplateStore.exists(partKind, id)
                            || CarriagePartTemplateStore.bundled(partKind, id));
            }
            case TRACK -> {
                TrackKind trackKind = TrackKind.fromId(subKind);
                yield trackKind != null
                        && (TrackVariantStore.exists(trackKind, id) || TrackVariantStore.bundled(trackKind, id));
            }
            case PORTAL_ROOM -> PortalRoomTemplateStore.exists(id)
                    || TrackVariantStore.bundled(TrackKind.PORTAL_ROOM, id);
        };
    }

    /**
     * Move the local template of {@code kind} from {@code id} to {@code newId}, registry and all.
     *
     * <p>File first, then the registry, then unregister the old name — the same ordering the writes
     * use and for the same reason: an interruption must never leave a registered name with no file
     * behind it. Only a saved copy can move; a bundled built-in has nothing in the config dir to
     * rename, and this answers false rather than pretending otherwise.</p>
     */
    private static boolean renameLocal(BuilderPhotoPaths.Kind kind, String id, String subKind,
                                       String newId) throws IOException {
        switch (kind) {
            case CARRIAGE -> {
                boolean whole = WholeCarriageTemplateStore.rename(id, newId);
                boolean shell = CarriageTemplateStore.rename(id, newId);
                if (!whole && !shell) return false;
                if (whole) WholeCarriageRegistry.register(WholeCarriage.of(newId));
                if (shell) {
                    CarriageVariantRegistry.register(new CarriageVariant.Custom(newId));
                    CarriageVariantBlocks.rename(id, newId);
                    CarriageVariantRegistry.unregister(id);
                }
                return true;
            }
            case CARRIAGE_GROUP -> {
                if (!CarriageGroupTemplateStore.rename(id, newId)) return false;
                CarriageGroupRegistry.register(CarriageGroup.of(newId));
                return true;
            }
            case CONTENTS -> {
                if (!CarriageContentsStore.rename(id, newId)) return false;
                CarriageContentsVariantBlocks.rename(id, newId);
                CarriageContentsRegistry.register(new CarriageContents.Custom(newId));
                return true;
            }
            case PART -> {
                CarriagePartKind partKind = CarriagePartKind.fromId(subKind);
                if (partKind == null || !CarriagePartTemplateStore.rename(partKind, id, newId)) return false;
                CarriagePartRegistry.register(partKind, newId);
                CarriagePartRegistry.unregister(partKind, id);
                return true;
            }
            case TRACK -> {
                TrackKind trackKind = TrackKind.fromId(subKind);
                if (trackKind == null || !TrackVariantStore.rename(trackKind, id, newId)) return false;
                TrackVariantRegistry.register(trackKind, newId);
                TrackVariantRegistry.unregister(trackKind, id);
                return true;
            }
            case PORTAL_ROOM -> {
                if (!TrackVariantStore.rename(TrackKind.PORTAL_ROOM, id, newId)) return false;
                // The room's remembered size is filed under its name; drop the stale entry so the
                // next read measures the template rather than trusting the old name's number.
                PortalRoomSizes.forget(id);
                return true;
            }
        }
        return false;
    }

    /**
     * A whole carriage, written twice — once as the whole carriage the builder made, once as the
     * carriage shell the spawn pool picks from. Both, for the same reason {@code BuilderSave} writes
     * both: the train generator has no idea whole carriages exist, so a build that existed only in
     * the first store would never appear in a train.
     */
    private static Outcome installCarriage(String id, String stageId, StructureTemplate template)
            throws IOException {
        WholeCarriage wholeCarriage = WholeCarriage.of(id);
        CarriageVariant variant = variantFor(id).orElse(null);
        if (variant == null) return Outcome.FAILED;
        WholeCarriageTemplateStore.save(wholeCarriage, template);
        WholeCarriageRegistry.register(wholeCarriage);
        CarriageTemplateStore.save(variant, template);
        linkStage(variant.id(), stageId);
        LOGGER.info("[DungeonTrain] Builder relay download: installed carriage '{}'", id);
        return Outcome.INSTALLED;
    }

    private static Outcome installGroup(String id, StructureTemplate template) throws IOException {
        CarriageGroup group = CarriageGroup.of(id);
        CarriageGroupTemplateStore.save(group, template);
        CarriageGroupRegistry.register(group);
        LOGGER.info("[DungeonTrain] Builder relay download: installed carriage group '{}'", id);
        return Outcome.INSTALLED;
    }

    private static Outcome installContents(String id, StructureTemplate template) throws IOException {
        CarriageContents existing = CarriageContentsRegistry.find(id).orElse(null);
        if (existing != null) {
            CarriageContentsStore.save(existing, template);
        } else {
            CarriageContents.Custom created = new CarriageContents.Custom(id);
            CarriageContentsStore.save(created, template);
            if (!CarriageContentsRegistry.register(created)) {
                // A reserved name — the same refusal BuilderSave raises rather than shadowing a
                // built-in room with a downloaded one.
                LOGGER.warn("[DungeonTrain] Builder relay download: '{}' is a reserved contents name", id);
                return Outcome.FAILED;
            }
        }
        LOGGER.info("[DungeonTrain] Builder relay download: installed contents '{}'", id);
        return Outcome.INSTALLED;
    }

    private static Outcome installPart(String id, String subKind, StructureTemplate template) throws IOException {
        CarriagePartKind partKind = CarriagePartKind.fromId(subKind);
        if (partKind == null) return Outcome.UNSUPPORTED;
        CarriagePartTemplateStore.save(partKind, id, template);
        CarriagePartRegistry.register(partKind, id);
        LOGGER.info("[DungeonTrain] Builder relay download: installed {} part '{}'", partKind.id(), id);
        return Outcome.INSTALLED;
    }

    private static Outcome installTrack(String id, String subKind, StructureTemplate template) throws IOException {
        TrackKind trackKind = TrackKind.fromId(subKind);
        if (trackKind == null) return Outcome.UNSUPPORTED;
        TrackVariantStore.save(trackKind, id, template);
        TrackVariantRegistry.register(trackKind, id);
        LOGGER.info("[DungeonTrain] Builder relay download: installed track {} '{}'", trackKind.id(), id);
        return Outcome.INSTALLED;
    }

    /**
     * A portal room. One write and no registry call: rooms are discovered from their files, and
     * {@code PortalRoomTemplateStore.save} records the room's size for that discovery itself.
     */
    private static Outcome installPortalRoom(String id, StructureTemplate template) throws IOException {
        PortalRoomTemplateStore.save(id, template);
        LOGGER.info("[DungeonTrain] Builder relay download: installed portal room '{}'", id);
        return Outcome.INSTALLED;
    }

    /** The variant to write a carriage under — the registered one, or a new custom. See {@code BuilderSave.variantFor}. */
    private static Optional<CarriageVariant> variantFor(String id) {
        Optional<CarriageVariant> existing = CarriageVariantRegistry.find(id);
        if (existing.isPresent()) return existing;
        CarriageVariant.Custom created = new CarriageVariant.Custom(id);
        if (!CarriageVariantRegistry.register(created)) {
            LOGGER.warn("[DungeonTrain] Builder relay download: could not register '{}'", id);
            return CarriageVariantRegistry.find(id);
        }
        return Optional.of(created);
    }

    /**
     * Link the downloaded carriage to the stage it was authored in, when this install has that stage.
     * Silently skipped otherwise — a stage the relay names and this world has never heard of is not
     * an error, it is a build made against content this install does not have.
     */
    private static void linkStage(String variantId, String stageId) throws IOException {
        if (stageId == null || stageId.isEmpty() || !StageStore.exists(stageId)) return;
        CarriageWeights.setStage(variantId, stageId);
        LOGGER.info("[DungeonTrain] Builder relay download: linked carriage {} to stage {}", variantId, stageId);
    }
}
