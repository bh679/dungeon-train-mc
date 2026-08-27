package games.brennan.dungeontrain.builder.relay;

import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartRegistry;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.PortalRoomTemplateStore;
import games.brennan.dungeontrain.editor.StageStore;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
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
 * <p><b>What does not come back:</b> the {@code .variants.json}, contents-allow and loot sidecars
 * are not part of what gets uploaded, so a downloaded template arrives with the defaults for all of
 * them. The blocks are the build; the rest is configuration the author sets again.</p>
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
    public enum Outcome { INSTALLED, ALREADY_HERE, UNSUPPORTED, FAILED }

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
        if (kind == null || id == null || id.isEmpty() || template == null) return Outcome.UNSUPPORTED;
        try {
            return switch (kind) {
                case CARRIAGE -> installCarriage(id, stageId, template);
                case CARRIAGE_GROUP -> installGroup(id, template);
                case CONTENTS -> installContents(id, template);
                case PART -> installPart(id, subKind, template);
                case TRACK -> installTrack(id, subKind, template);
                case PORTAL_ROOM -> installPortalRoom(id, template);
            };
        } catch (Throwable t) {
            LOGGER.error("[DungeonTrain] Builder relay download: could not install {} '{}'", kind.id(), id, t);
            return Outcome.FAILED;
        }
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
        if (WholeCarriageTemplateStore.exists(wholeCarriage) || CarriageTemplateStore.exists(variant)
                || CarriageTemplateStore.bundled(variant)) {
            return Outcome.ALREADY_HERE;
        }

        WholeCarriageTemplateStore.save(wholeCarriage, template);
        WholeCarriageRegistry.register(wholeCarriage);
        CarriageTemplateStore.save(variant, template);
        linkStage(variant.id(), stageId);
        LOGGER.info("[DungeonTrain] Builder relay download: installed carriage '{}'", id);
        return Outcome.INSTALLED;
    }

    private static Outcome installGroup(String id, StructureTemplate template) throws IOException {
        CarriageGroup group = CarriageGroup.of(id);
        if (CarriageGroupTemplateStore.exists(group)) return Outcome.ALREADY_HERE;
        CarriageGroupTemplateStore.save(group, template);
        CarriageGroupRegistry.register(group);
        LOGGER.info("[DungeonTrain] Builder relay download: installed carriage group '{}'", id);
        return Outcome.INSTALLED;
    }

    private static Outcome installContents(String id, StructureTemplate template) throws IOException {
        CarriageContents existing = CarriageContentsRegistry.find(id).orElse(null);
        if (existing != null && (CarriageContentsStore.exists(existing) || CarriageContentsStore.bundled(existing))) {
            return Outcome.ALREADY_HERE;
        }
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
        if (CarriagePartTemplateStore.exists(partKind, id) || CarriagePartTemplateStore.bundled(partKind, id)) {
            return Outcome.ALREADY_HERE;
        }
        CarriagePartTemplateStore.save(partKind, id, template);
        CarriagePartRegistry.register(partKind, id);
        LOGGER.info("[DungeonTrain] Builder relay download: installed {} part '{}'", partKind.id(), id);
        return Outcome.INSTALLED;
    }

    private static Outcome installTrack(String id, String subKind, StructureTemplate template) throws IOException {
        TrackKind trackKind = TrackKind.fromId(subKind);
        if (trackKind == null) return Outcome.UNSUPPORTED;
        if (TrackVariantStore.exists(trackKind, id) || TrackVariantStore.bundled(trackKind, id)) {
            return Outcome.ALREADY_HERE;
        }
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
        if (PortalRoomTemplateStore.exists(id)) return Outcome.ALREADY_HERE;
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
