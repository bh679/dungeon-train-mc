package games.brennan.dungeontrain.client.builder;

import games.brennan.dungeontrain.builder.BuilderPhotoPaths;
import games.brennan.dungeontrain.builder.BuilderTemplateFiles;
import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.editor.TemplateLoot;
import games.brennan.dungeontrain.track.variant.TrackKind;
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
        return load(kind, id, partKind, trackKind).cells();
    }

    /** A template's blocks and the numbers its data sheet shows, read together from one file. */
    record Loaded(Map<BlockPos, BlockState> cells, TemplateSummary summary) {
        static final Loaded EMPTY = new Loaded(Map.of(), TemplateSummary.NONE);
    }

    /** As {@link #cells}, also tallying the template for its data sheet. */
    static Loaded load(BuilderPhotoPaths.Kind kind, String id,
                       CarriagePartKind partKind, TrackKind trackKind) {
        Optional<CompoundTag> tag = rawTag(kind, id, partKind, trackKind);
        if (tag.isEmpty()) {
            return Loaded.EMPTY;
        }
        HolderGetter<Block> blocks = blockRegistry();
        if (blocks == null) {
            return Loaded.EMPTY;
        }
        StructureTemplate template = new StructureTemplate();
        try {
            template.load(blocks, tag.get());
        } catch (RuntimeException e) {
            // A malformed or future-version template is a tile that shows its photo, not a crash
            // in the middle of a screen render.
            return Loaded.EMPTY;
        }
        Map<BlockPos, BlockState> cells = TemplateCells.of(template);
        TemplateCells.NbtTally tally = TemplateCells.tallyBlockEntities(template);
        TemplateSummary summary = new TemplateSummary(cells.size(), template.getSize(),
                tally.blockEntities(), tally.containers(), TemplateCells.entityCount(tag.get()),
                TemplateCells.lights(cells), TemplateLoot.of(template, kind, subKindOf(partKind, trackKind), id));
        return new Loaded(cells, summary);
    }

    /** The sub kind a part or track is keyed by in its sidecars, or null for every other kind. */
    private static String subKindOf(CarriagePartKind partKind, TrackKind trackKind) {
        if (partKind != null) return partKind.id();
        return trackKind == null ? null : trackKind.id();
    }

    /** The template file — see {@link BuilderTemplateFiles}. */
    private static Optional<CompoundTag> rawTag(BuilderPhotoPaths.Kind kind, String id,
                                                CarriagePartKind partKind, TrackKind trackKind) {
        return BuilderTemplateFiles.rawTag(kind, id, partKind, trackKind);
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
