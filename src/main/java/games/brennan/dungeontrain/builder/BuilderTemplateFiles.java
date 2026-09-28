package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriageGroupTemplateStore;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.nbt.CompoundTag;

import java.util.Optional;

/**
 * The saved NBT of one builder template, found by its kind — the store-per-kind switch
 * {@link BuilderPhotoPaths#photoFor} makes for photos, made once for the template itself so the
 * client's tiles and the server's submit checks read the same file the same way.
 */
public final class BuilderTemplateFiles {

    private BuilderTemplateFiles() {}

    /** The template's NBT; empty when the kind needs a sub kind it was not given, or there is no file. */
    public static Optional<CompoundTag> rawTag(BuilderPhotoPaths.Kind kind, String id,
                                               CarriagePartKind partKind, TrackKind trackKind) {
        if (kind == null || id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return switch (kind) {
            case CARRIAGE -> CarriageTemplateStore.rawTag(id);
            case CARRIAGE_GROUP -> CarriageGroupTemplateStore.rawTag(id);
            case CONTENTS -> CarriageContentsStore.rawTag(id);
            case PART -> partKind == null
                    ? Optional.empty()
                    : CarriagePartTemplateStore.rawTag(partKind, id);
            case TRACK -> trackKind == null
                    ? Optional.empty()
                    : TrackVariantStore.rawTag(trackKind, id);
            case PORTAL_ROOM -> TrackVariantStore.rawTag(TrackKind.PORTAL_ROOM, id);
            case CHUNK_FRAME -> games.brennan.dungeontrain.portal.chunkframe.ChunkFrameStore.readTag(id);
        };
    }

    /** As above, with the sub kind still in its relay spelling — a part or track kind id, or blank. */
    public static Optional<CompoundTag> rawTag(BuilderPhotoPaths.Kind kind, String subKind, String id) {
        CarriagePartKind partKind = kind == BuilderPhotoPaths.Kind.PART ? CarriagePartKind.fromId(subKind) : null;
        TrackKind trackKind = kind == BuilderPhotoPaths.Kind.TRACK ? TrackKind.fromId(subKind) : null;
        return rawTag(kind, id, partKind, trackKind);
    }
}
