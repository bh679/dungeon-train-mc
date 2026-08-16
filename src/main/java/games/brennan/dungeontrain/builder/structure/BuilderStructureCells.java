package games.brennan.dungeontrain.builder.structure;

import games.brennan.dungeontrain.builder.BuilderTrackScene;
import games.brennan.dungeontrain.editor.CarriageTemplateStore;
import games.brennan.dungeontrain.editor.TemplateCells;
import games.brennan.dungeontrain.track.PillarAdjunct;
import games.brennan.dungeontrain.track.PillarSection;
import games.brennan.dungeontrain.track.TrackPlacer;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantRegistry;
import games.brennan.dungeontrain.track.variant.TrackVariantStore;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import games.brennan.dungeontrain.tunnel.TunnelPlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a {@link BuilderStructure.Kind} is made of: its blocks, local to its own origin.
 *
 * <p>The other half of {@link BuilderStructureNeeds}. The declaration says <em>a carriage of this
 * variant</em>; this says what a carriage of that variant is made of, and neither is allowed an
 * opinion about the other's question. Kept apart because the two have different shapes — a
 * declaration is a handful of records recomputed every tick, and the blocks behind it are thousands
 * of cells read from a template, worth reading once and keeping.</p>
 *
 * <h2>One resolver, both sides</h2>
 *
 * <p>The client draws these and the server stands them up, and they must be the same blocks or Solid
 * would change the scene rather than solidify it. So this takes its inputs explicitly — a block
 * registry, the dimensions, the seed — rather than reaching for a level, and both callers hand it
 * their own. It rests on a builder world being single-player by construction, which is what puts the
 * template stores in the same process as the client; the same thing {@code EditorTemplateLists}
 * already relies on for the Open grid.</p>
 *
 * <h2>Cached by kind, not by placement</h2>
 *
 * <p>A carriage two slots down and a carriage one slot down are the same blocks at different
 * origins, so one entry serves both and a group of three costs one read. {@link BuilderStructure.Kind}
 * implementations are records, so that comes for free.</p>
 *
 * <p><b>The room kinds are deliberately not cached</b> — they are read from the room you are building
 * rather than from disk, so their blocks change as you place them. That is the point: the copies have
 * to show the room you have now, not the room you last saved.</p>
 */
public final class BuilderStructureCells {

    /**
     * Session-scoped. Dropped on logout and on a resource reload — see {@link #clear}.
     *
     * <p>Keyed on the world's seed and dimensions as well as the kind, because neither is part of a
     * {@link BuilderStructure.Kind} and both change what it resolves to: the seed decides which
     * variant each tile rolls, and the dimensions decide how wide a slice is. Without them, opening
     * a second builder world in the same session would serve it the first one's line.</p>
     */
    private record CacheKey(BuilderStructure.Kind kind, long worldSeed, CarriageDims dims) {}

    private static final Map<CacheKey, Map<BlockPos, BlockState>> CACHE = new ConcurrentHashMap<>();

    private BuilderStructureCells() {}

    /**
     * Everything resolving a kind needs, gathered so both sides can supply it from what they have.
     *
     * @param blocks    a block registry — {@link StructureTemplate#load} wants one of these, not a
     *                  level, which is why this works on either side
     * @param worldSeed the world's own seed. The generator keys its variant picks off it, so a
     *                  preview using a different one would show a line this world would never build
     * @param openBuild the live blocks of the open build volume, local to its origin. Only the room
     *                  kinds read it, and only they need to: a room's copies are copies of the room
     *                  you are building right now
     * @param openSize  that volume's extent
     */
    public record Sources(HolderGetter<Block> blocks, CarriageDims dims, long worldSeed,
                          Map<BlockPos, BlockState> openBuild, Vec3i openSize) {

        public Sources(HolderGetter<Block> blocks, CarriageDims dims, long worldSeed) {
            this(blocks, dims, worldSeed, Map.of(), Vec3i.ZERO);
        }
    }

    /** Forget every cached template. Cheap to call; the next resolve re-reads. */
    public static void clear() {
        CACHE.clear();
    }

    /** The blocks of one kind, local to its own origin. Empty is an ordinary answer. */
    public static Map<BlockPos, BlockState> of(BuilderStructure.Kind kind, Sources src) {
        if (kind == null || src == null || src.blocks() == null) {
            return Map.of();
        }
        // Read live rather than cached: these change under you as you build the room they copy.
        if (kind instanceof BuilderStructure.Kind.RoomTile tile) {
            return roomTile(tile.detail(), src);
        }
        if (kind instanceof BuilderStructure.Kind.RoomBedrock) {
            return bedrockSkin(src.openSize());
        }
        return CACHE.computeIfAbsent(new CacheKey(kind, src.worldSeed(), src.dims()),
                k -> resolve(k.kind(), src));
    }

    private static Map<BlockPos, BlockState> resolve(BuilderStructure.Kind kind, Sources src) {
        return switch (kind) {
            case BuilderStructure.Kind.Carriage c -> carriage(c.variantId(), src);
            case BuilderStructure.Kind.CarriageShell s -> skinOf(carriage(s.variantId(), src), src.dims());
            case BuilderStructure.Kind.FlatbedPad p -> pad(p.side(), src);
            case BuilderStructure.Kind.TrackRun t -> trackRun(t.fromX(), t.toX(), src);
            case BuilderStructure.Kind.PillarColumn p -> column(p.centreX(), p.height(), src);
            case BuilderStructure.Kind.TunnelSection t ->
                    mirroredX(track(TrackKind.TUNNEL_SECTION, t.name(), src), t.mirrorX(),
                            TunnelPlacer.LENGTH);
            case BuilderStructure.Kind.TunnelPortal t ->
                    mirroredX(track(TrackKind.TUNNEL_PORTAL, t.name(), src), t.mirrorX(),
                            TunnelPlacer.LENGTH);
            case BuilderStructure.Kind.StairsFlight s ->
                    stairs(s.centreX(), s.height(), s.mirrorZ(), src);
            // Handled live above; unreachable, and stated rather than defaulted so adding a kind
            // stays a compile error here.
            case BuilderStructure.Kind.RoomTile ignored -> Map.of();
            case BuilderStructure.Kind.RoomBedrock ignored -> Map.of();
        };
    }

    // ---- carriages ----

    private static Map<BlockPos, BlockState> carriage(String variantId, Sources src) {
        return variantId == null || variantId.isEmpty()
                ? Map.of()
                : load(CarriageTemplateStore.rawTag(variantId), src);
    }

    /**
     * The perimeter skin of a carriage — walls, roof and deck, without the interior.
     *
     * <p>Filtered out of the whole carriage rather than read separately, so the shell you stand in
     * and the carriage next door can never disagree about what a carriage looks like.</p>
     */
    private static Map<BlockPos, BlockState> skinOf(Map<BlockPos, BlockState> whole,
                                                    CarriageDims dims) {
        Map<BlockPos, BlockState> skin = new LinkedHashMap<>();
        whole.forEach((p, state) -> {
            if (BuilderStructure.onSkin(p.getX(), p.getY(), p.getZ(),
                    dims.length(), dims.height(), dims.width())) {
                skin.put(p, state);
            }
        });
        return skin;
    }

    /**
     * One half-flatbed pad, sliced out of the FLATBED carriage the way the real one is.
     *
     * <p>{@code CarriagePlacer} builds a pad by trimming that template to {@code halfPadLen} on X and
     * mirroring the FRONT one across it — the same two steps here, against the same template, so a
     * drawn pad and a stamped pad are the same blocks. Derived rather than copied out of the world,
     * which is what lets a one-carriage build show the pads it never stamped.</p>
     */
    private static Map<BlockPos, BlockState> pad(CarriagePlacer.HalfPadSide side, Sources src) {
        int padLength = CarriagePlacer.halfPadLen(src.dims());
        Map<BlockPos, BlockState> flatbed =
                carriage(CarriagePlacer.CarriageType.FLATBED.id(), src);
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        flatbed.forEach((p, state) -> {
            if (p.getX() >= padLength) {
                return;
            }
            int x = side == CarriagePlacer.HalfPadSide.FRONT ? padLength - 1 - p.getX() : p.getX();
            out.put(new BlockPos(x, p.getY(), p.getZ()), state);
        });
        return out;
    }

    // ---- the line ----

    /**
     * The bed and rails from {@code fromX} to {@code toX}, tile by tile, local to {@code fromX}.
     *
     * <p>Each {@link TrackPlacer#TILE_LENGTH}-wide tile rolls its own variant against the world seed
     * exactly as the generator rolls it, so this is the line that would be there rather than a
     * stand-in for it.</p>
     */
    private static Map<BlockPos, BlockState> trackRun(int fromX, int toX, Sources src) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        long first = Math.floorDiv(fromX, TrackPlacer.TILE_LENGTH);
        long last = Math.floorDiv(toX, TrackPlacer.TILE_LENGTH);

        for (long tile = first; tile <= last; tile++) {
            Map<BlockPos, BlockState> cells = picked(TrackKind.TILE, tile, src);
            int tileX = (int) (tile * TrackPlacer.TILE_LENGTH);
            for (Map.Entry<BlockPos, BlockState> e : cells.entrySet()) {
                BlockPos p = e.getKey();
                int worldX = tileX + p.getX();
                if (worldX < fromX || worldX > toX) {
                    continue;   // the end tiles run past what was asked for
                }
                out.put(new BlockPos(worldX - fromX, p.getY(), p.getZ()), e.getValue());
            }
        }
        return out;
    }

    /**
     * One column and the arch hanging off it, local to the column's low corner.
     *
     * <p>Stacked the way {@code placePillarSlice} stacks it: bottom from the ground up, top hanging
     * from the cap, middles filling everything between. Each section's own variant is picked on the
     * column's world X, which is the key the generator uses — so two adjacent columns can
     * legitimately differ, and drawing them identical would be the preview lying about the line.</p>
     *
     * <p><b>The arch reaches outside the column's own footprint</b>, so its cells have negative local
     * X on the low side. That is why the local frame is the kind's origin rather than its minimum
     * corner: an arch is part of the column it hangs from, built from that column's own templates
     * read downward, which is what carries an authored pillar's look into it.</p>
     */
    private static Map<BlockPos, BlockState> column(int centreX, int height, Sources src) {
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        Map<BlockPos, BlockState> bottom = picked(TrackKind.PILLAR_BOTTOM, centreX, src);
        Map<BlockPos, BlockState> middle = picked(TrackKind.PILLAR_MIDDLE, centreX, src);
        Map<BlockPos, BlockState> top = picked(TrackKind.PILLAR_TOP, centreX, src);

        int botH = PillarSection.BOTTOM.height();
        int topH = PillarSection.TOP.height();
        int width = src.dims().width();
        int thickness = BuilderTrackScene.thickness();
        int topFrom = height - topH + 1;

        for (int x = 0; x < thickness; x++) {
            for (int y = 0; y <= height; y++) {
                // Bottom on the ground, top hanging from the cap, middle repeating between them.
                Map<BlockPos, BlockState> source;
                int row;
                if (y < botH) {
                    source = bottom;
                    row = y;
                } else if (y >= topFrom) {
                    source = top;
                    row = y - topFrom;
                } else {
                    source = middle;
                    row = 0;
                }
                putSlice(out, source, x, y, row, width);
            }
        }
        appendArch(out, middle, top, height, thickness, topH, width);
        return out;
    }

    /**
     * The stepped corbel hanging from the cap, stepping out from both sides of the column.
     *
     * <p>Its blocks are a top-anchored copy of the pillar's own face — the top section's rows read
     * downward, then the middle's row repeating — which is why an authored pillar carries its own
     * look into the arch instead of the arch being a separate template that can drift from it.</p>
     */
    private static void appendArch(Map<BlockPos, BlockState> out,
                                   Map<BlockPos, BlockState> middle,
                                   Map<BlockPos, BlockState> top,
                                   int height, int thickness, int topH, int width) {
        int[] profile = BuilderTrackScene.archProfile();
        for (int step = 0; step < profile.length; step++) {
            for (int i = 0; i < profile[step]; i++) {
                Map<BlockPos, BlockState> source = i < topH ? top : middle;
                int row = i < topH ? topH - 1 - i : 0;
                putSlice(out, source, thickness + step, height - i, row, width);
                putSlice(out, source, -1 - step, height - i, row, width);
            }
        }
    }

    /** One row of a 1×H×W pillar section, spread across the corridor's width. */
    private static void putSlice(Map<BlockPos, BlockState> out, Map<BlockPos, BlockState> source,
                                 int x, int y, int row, int width) {
        for (int z = 0; z < width; z++) {
            BlockState state = source.get(new BlockPos(0, row, z));
            if (state != null) {
                out.put(new BlockPos(x, y, z), state);
            }
        }
    }

    /**
     * A staircase flight, local to its own bottom corner.
     *
     * <p>The generator stamps the 8-tall template over and over from the top down until it reaches
     * the floor, which is how one template becomes a staircase of any height — so a flight drawn as a
     * single stamp would end in mid-air. The last repeat runs past the bottom and is clipped, exactly
     * as the generator clips it.</p>
     *
     * <p>{@code mirrorZ} negates local Z within the footprint, which is what
     * {@code Mirror.LEFT_RIGHT} does to this template where the generator applies it. It is not
     * decoration: a staircase has a front and a back, and the unmirrored copy on the {@code +Z} side
     * climbs into the wall rather than out of it.</p>
     *
     * <p><b>The shaft is a hole, and the hole is part of the flight.</b> Every cell of the footprint
     * the stair template does not fill comes back as explicit air, because the generator does not
     * stamp a staircase into a tunnel — it <em>carves</em> the shaft out first, walls and ceiling
     * included, and stamps the stairs into the carve. That carve is what opens the doorway from the
     * corridor into the stairwell. Declaring only the stair blocks would leave the tunnel wall
     * standing in every cell between them, which is a staircase sealed inside a wall.</p>
     *
     * <p>Air is not a no-op here: it claims its cell, so a tunnel declared earlier loses it. That is
     * the same "later wins" rule everything else in the scene is ordered by, used to take something
     * away instead of to put something down.</p>
     */
    private static Map<BlockPos, BlockState> stairs(int centreX, int height, boolean mirrorZ,
                                                    Sources src) {
        Map<BlockPos, BlockState> template = picked(TrackKind.ADJUNCT_STAIRS, centreX, src);
        if (template.isEmpty() || height <= 0) {
            return Map.of();
        }
        int stamp = BuilderStructureNeeds.stairsStampHeight();
        int width = PillarAdjunct.STAIRS.xSize();
        int depth = PillarAdjunct.STAIRS.zSize();

        // The carve first, so a stair block laid over it simply replaces the air.
        BlockState air = Blocks.AIR.defaultBlockState();
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    out.put(new BlockPos(x, y, z), air);
                }
            }
        }

        for (int top = height - 1; top >= 0; top -= stamp) {
            int base = top - stamp + 1;
            for (Map.Entry<BlockPos, BlockState> e : template.entrySet()) {
                BlockPos p = e.getKey();
                int y = base + p.getY();
                if (y < 0 || y > top) {
                    continue;
                }
                int z = mirrorZ ? depth - 1 - p.getZ() : p.getZ();
                out.put(new BlockPos(p.getX(), y, z), e.getValue());
            }
        }
        return out;
    }

    // ---- rooms ----

    /**
     * One copy of the open room, filtered to the detail this copy is drawn at.
     *
     * <p>Read from the room you are building rather than from the last save, which is the one thing a
     * preview must never get wrong: quietly disagreeing with the work in front of you.</p>
     */
    private static Map<BlockPos, BlockState> roomTile(BuilderStructure.Detail detail, Sources src) {
        Vec3i size = src.openSize();
        if (detail == BuilderStructure.Detail.FULL || size.getY() <= 0) {
            return src.openBuild();
        }
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        src.openBuild().forEach((p, state) -> {
            boolean plane = p.getY() == 0 || p.getY() == size.getY() - 1;
            boolean keep = detail == BuilderStructure.Detail.PLANES
                    ? plane
                    : plane || p.getX() == 0 || p.getX() == size.getX() - 1
                            || p.getZ() == 0 || p.getZ() == size.getZ() - 1;
            if (keep) {
                out.put(p, state);
            }
        });
        return out;
    }

    /**
     * A one-block bedrock skin around the room — what Bedrock Lock actually writes outside the box.
     *
     * <p>Real bedrock rather than an outlined box, so the tile that says "Bedrock" and the thing
     * standing around the room are visibly the same claim. Local to the room's own origin, so the
     * skin's own cells sit at −1 and at the size on each axis.</p>
     */
    private static Map<BlockPos, BlockState> bedrockSkin(Vec3i size) {
        if (size == null || size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            return Map.of();
        }
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (int x = -1; x <= size.getX(); x++) {
            for (int y = -1; y <= size.getY(); y++) {
                for (int z = -1; z <= size.getZ(); z++) {
                    if (x == -1 || x == size.getX() || y == -1 || y == size.getY()
                            || z == -1 || z == size.getZ()) {
                        out.put(new BlockPos(x, y, z), bedrock);
                    }
                }
            }
        }
        return out;
    }

    // ---- template reads ----

    /** The blocks of whichever variant the world would roll for this kind at this index. */
    private static Map<BlockPos, BlockState> picked(TrackKind kind, long index, Sources src) {
        return track(kind, TrackVariantRegistry.pickName(kind, src.worldSeed(), index), src);
    }

    private static Map<BlockPos, BlockState> track(TrackKind kind, String name, Sources src) {
        String resolved = name == null || name.isEmpty() ? TrackKind.DEFAULT_NAME : name;
        return load(TrackVariantStore.rawTag(kind, resolved), src);
    }

    /**
     * Mirror a template across its own X span, for the copy that faces the other way.
     *
     * <p>{@code length} rather than the template's own extent, because that is what
     * {@code TunnelPlacer} mirrors across when it stamps the far mouth of a run.</p>
     */
    private static Map<BlockPos, BlockState> mirroredX(Map<BlockPos, BlockState> cells,
                                                       boolean mirror, int length) {
        if (!mirror || cells.isEmpty()) {
            return cells;
        }
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        cells.forEach((p, state) ->
                out.put(new BlockPos(length - 1 - p.getX(), p.getY(), p.getZ()), state));
        return out;
    }

    /**
     * Unpack a stored template, or nothing.
     *
     * <p>Read through {@code rawTag} rather than each store's dims-gated getter: the gate is right
     * for stamping a train and wrong for looking at one, since it blanks out exactly the odd-sized
     * templates somebody is most likely to be previewing.</p>
     */
    private static Map<BlockPos, BlockState> load(Optional<CompoundTag> tag, Sources src) {
        if (tag.isEmpty()) {
            return Map.of();
        }
        StructureTemplate template = new StructureTemplate();
        try {
            template.load(src.blocks(), tag.get());
        } catch (RuntimeException e) {
            // A malformed or future-version template is a structure that doesn't show, not a crash
            // in the middle of a render pass or a world stamp.
            return Map.of();
        }
        return TemplateCells.of(template);
    }
}
