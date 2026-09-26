package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.EditorDevMode;
import games.brennan.dungeontrain.editor.WholeCarriageTemplateStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageContentsRegistry;
import games.brennan.dungeontrain.train.CarriagePartKind;
import games.brennan.dungeontrain.world.DungeonTrainWorldData;
import net.minecraft.server.level.ServerLevel;

/**
 * Whether the build a Train Builder world is holding goes by a name the mod ships.
 *
 * <p>Opening a built-in keeps its name — that is what makes Save point at it — so without this a
 * player who tweaked {@code standard} saved straight over it and uploaded the result to My Builds as
 * {@code standard}, from where it could be submitted to the train. The Train Editor has refused that
 * upload all along ({@code EditorRelaySave}); this is the Builder's half of the same line, drawn by
 * the jar rather than by a registry flag so a shipped contents sub-variant counts too.</p>
 *
 * <p>The jar check is deliberately blind to local overrides: a built-in the player has already
 * saved over is still the mod's name, and still not theirs to submit.</p>
 */
public final class BuilderBuiltins {

    private BuilderBuiltins() {}

    /**
     * Whether Save should stop and ask for a name of the player's own — the world holds a shipped
     * template, and this is not a dev checkout authoring that template.
     */
    public static boolean isProtected(ServerLevel level) {
        DungeonTrainWorldData data = DungeonTrainWorldData.get(level);
        boolean shipped = BuilderTemplateIdentity.of(data.builderMode(), data.builderSubType(),
                        data.builderPartKind(), data.builderTrackKind(), data.builderName(),
                        BuilderWorldSetup.parkedCarriages(data))
                .map(BuilderBuiltins::isShipped)
                .orElse(false);
        return isProtected(shipped, EditorDevMode.isEnabled());
    }

    /**
     * The decision itself. Dev mode is the exemption because it is how shipped templates are
     * authored: a save there writes through to the source tree, and prompting for a new name would
     * make the mod's own content impossible to edit from the Builder.
     */
    public static boolean isProtected(boolean shipped, boolean devMode) {
        return shipped && !devMode;
    }

    /** Whether the mod jar carries a template of this kind under this name. */
    public static boolean isShipped(BuilderTemplateIdentity.Identity identity) {
        if (identity == null || identity.id().isEmpty()) return false;
        String id = identity.id();
        String subKind = identity.subKind();
        return switch (identity.kind()) {
            // A carriage save writes the whole carriage and its shell under one name, so either
            // tier shipping that name makes it the mod's.
            // Read from the jar by name: several shipped carriages (black, cracked, …) register as
            // custom variants, which the variant-typed bundled() check would wave through.
            case CARRIAGE -> WholeCarriageTemplateStore.bundled(id) || CarriageTemplateStore.shipsId(id);
            case CARRIAGE_GROUP -> CarriageGroupTemplateStore.bundled(id);
            case CONTENTS -> CarriageContentsRegistry.find(id).map(CarriageContentsStore::bundled).orElse(false);
            case PART -> {
                CarriagePartKind partKind = CarriagePartKind.fromId(subKind);
                yield partKind != null && CarriagePartTemplateStore.bundled(partKind, id);
            }
            case TRACK -> {
                TrackKind trackKind = TrackKind.fromId(subKind);
                yield trackKind != null && TrackVariantStore.bundled(trackKind, id);
            }
            case PORTAL_ROOM -> TrackVariantStore.bundled(TrackKind.PORTAL_ROOM, id);
            case CHUNK_FRAME -> games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore.isBundled(id);
        };
    }
}
