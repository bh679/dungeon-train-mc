package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.editor.CarriageContentsStore;
import games.brennan.dungeontrain.editor.CarriagePartTemplateStore;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriagePartKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Map;
import java.util.Optional;

/**
 * The real blocks behind a tile in the Open grid, read on the client.
 *
 * <p>The lookup is keyed the same way the tile's photo is — {@link BuilderPhotoPaths.Kind} plus the
 * bare id, plus the part or track kind that some of those stores need — and the switch here
 * deliberately mirrors {@link BuilderPhotoPaths#photoFor}, so a preview can never end up showing a
 * different template than the picture it replaced.</p>
 *
 * <p><b>Read ungated.</b> Each store's public getter filters what it loads against the world's
 * {@code CarriageDims}, which is right for stamping and wrong for looking: it would blank out
 * exactly the odd-sized templates a builder is most likely to be hunting through. These go via the
 * stores' {@code rawTag} helpers instead.</p>
 *
 * <p><b>No integrated server needed.</b> {@link StructureTemplate#load} wants a block registry, not
 * a level, and the client has one of its own — so unlike {@link BuilderGhostTemplates} this answers
 * on a multiplayer client too. It is still only reachable from the builder's Open screen.</p>
 *
 * <p><b>Never call this from a render pass unbudgeted.</b> A miss is file I/O plus NBT
 * decompression. {@link BuilderTileMeshCache} does at most one resolve per frame for that
 * reason.</p>
 */
@OnlyIn(Dist.CLIENT)
final class BuilderTileTemplates {

    private BuilderTileTemplates() {}

    /**
     * Every non-air block of the named template, local to its own origin, or empty when there is
     * nothing to show.
     *
     * <p>Empty is an ordinary answer — a category tile with no template of its own, a name that
     * resolves to no file, a client with no level yet. The caller falls back to the flat art.</p>
     */
    static Map<BlockPos, BlockState> cells(BuilderPhotoPaths.Kind kind, String id,
                                           CarriagePartKind partKind, TrackKind trackKind) {
        Optional<CompoundTag> tag = rawTag(kind, id, partKind, trackKind);
        if (tag.isEmpty()) {
            return Map.of();
        }
        HolderGetter<Block> blocks = blockRegistry();
        if (blocks == null) {
            return Map.of();
        }
        StructureTemplate template = new StructureTemplate();
        try {
            template.load(blocks, tag.get());
        } catch (RuntimeException e) {
            // A malformed or future-version template is a tile that shows its photo, not a crash
            // in the middle of a screen render.
            return Map.of();
        }
        return TemplateCells.of(template);
    }

    /** The same store-per-kind switch {@link BuilderPhotoPaths#photoFor} makes, for the NBT. */
    private static Optional<CompoundTag> rawTag(BuilderPhotoPaths.Kind kind, String id,
                                                CarriagePartKind partKind, TrackKind trackKind) {
        if (kind == null || id == null || id.isEmpty()) {
            return Optional.empty();
        }
        return switch (kind) {
            case CARRIAGE -> CarriageTemplateStore.rawTag(id);
            case CONTENTS -> CarriageContentsStore.rawTag(id);
            case PART -> partKind == null
                    ? Optional.empty()
                    : CarriagePartTemplateStore.rawTag(partKind, id);
            case TRACK -> trackKind == null
                    ? Optional.empty()
                    : TrackVariantStore.rawTag(trackKind, id);
            case PORTAL_ROOM -> TrackVariantStore.rawTag(TrackKind.PORTAL_ROOM, id);
        };
    }

    /**
     * The client's own block registry.
     *
     * <p>Null before a level is loaded. The Open screen is only reachable from inside a builder
     * world, so in practice this is always present; the check is here so a resolve triggered while
     * a world is tearing down answers "nothing" instead of throwing.</p>
     */
    private static HolderGetter<Block> blockRegistry() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.registryAccess().lookupOrThrow(Registries.BLOCK);
    }
}
