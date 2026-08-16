package games.brennan.dungeontrain.builder;

import games.brennan.dungeontrain.portal.PortalRoomMode;
import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.train.CarriageDims;
import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.ArrayList;
import java.util.List;

/**
 * What stands around the thing you are building, and where.
 *
 * <p>A template is never the whole scene. A carriage room is judged inside a carriage, in a train,
 * on a line; a track tile is judged against the line it joins; a portal room is judged against the
 * copies of itself it will repeat into. This is the one place that answers "so what goes around
 * <em>this</em>, and where does it go" — for every mode, in one shape.</p>
 *
 * <h2>A declaration, not a snapshot</h2>
 *
 * <p><b>This is the point of the class, and it is worth being explicit about.</b> What it returns
 * describes the surroundings — <em>a carriage of this variant, at this X</em> — rather than recording
 * blocks that were moved. Everything it needs is already known to both sides
 * ({@code DungeonTrainWorldData} on the server, {@code BuilderBoundsState} on the client), so it is
 * recomputed on demand and never stored.</p>
 *
 * <p>That property is load-bearing. The mechanism this replaced kept a captured copy of the blocks it
 * had lifted, and every bug it had came from that copy rather than from the lifting:</p>
 * <ul>
 *   <li>a copy taken in one mode was replayed in another, so making the scenery solid in Tracks &amp;
 *       Tunnels stood a <em>carriage</em> up in the middle of a track build;</li>
 *   <li>reading the world a second time found what the first read had taken and quietly replaced the
 *       copy with an empty one, so the carriage you were standing in could never get its walls
 *       back;</li>
 *   <li>and it was keyed on a carriage count, so it did nothing at all in the two modes that park no
 *       carriages.</li>
 * </ul>
 *
 * <p>None of the three is reachable here. A declaration for Tracks &amp; Tunnels contains no
 * carriage, so no amount of switching can produce one; recomputing is idempotent, so a second call
 * cannot destroy the answer; and each surround below states its own requirement rather than sharing
 * one gate.</p>
 *
 * <h2>Deliberately not the build</h2>
 *
 * <p>Nothing here is ever saved. {@link Source.CarriageSkin} is the closest call — it is the shell of
 * the carriage you stand in, which sits geometrically <em>inside</em> the build volume — and it is
 * still not the build: a carriage room is captured from the interior box one in from every face
 * ({@code CarriageContentsPlacer.interiorOrigin}), and {@link #onSkin} is that box's exact
 * complement. What surrounds you and what you save cannot overlap.</p>
 *
 * <p>Free of client-only imports and of any world access, so it stays unit-testable and so both
 * sides can call it and agree by construction.</p>
 */
public final class BuilderSurroundings {

    /** How much of a repeating copy is worth drawing — see {@link BuilderRoomGhosts.Detail}. */
    public enum Detail { FULL, SHELL }

    /**
     * Where a surround's blocks come from.
     *
     * <p>Sealed, so adding a kind of scenery is a compile error everywhere that has to show it or
     * stand it up, rather than a silent omission in one of them.</p>
     */
    public sealed interface Source {

        /** A whole empty carriage — one of the slots in the group this build sits in. */
        record Carriage(String variantId) implements Source {}

        /**
         * The shell of the carriage the build is inside: walls, roof and deck, but not the interior.
         *
         * <p>Separate from {@link Carriage} because it is the one surround that overlaps a build
         * volume, and so the one that must never be written or drawn past its skin.</p>
         */
        record CarriageSkin(String variantId) implements Source {}

        /** One half-flatbed pad capping the group. The side carries its mirroring. */
        record FlatbedPad(CarriagePlacer.HalfPadSide side) implements Source {}

        /** The bed and rails, from {@code fromX} to {@code toX} inclusive. */
        record TrackCorridor(int fromX, int toX) implements Source {}

        /**
         * The line around an open track template — its tunnels, pillars and stairs.
         *
         * <p>Richer than a corridor run and resolved by {@code BuilderTrackSceneGhosts}, which
         * already composes it from the same picks the world would make.</p>
         */
        record TrackScene(TrackKind kind, String name) implements Source {}

        /** One repeat of an open portal room, on the tile grid. */
        record RoomTile(String name, Detail detail) implements Source {}

        /** The one-block skin hugging a Bedrock Lock room — its boundary, which does not tile. */
        record RoomShell(String name) implements Source {}
    }

    /**
     * One piece of scenery, at one place.
     *
     * @param origin where its own local (0,0,0) lands in the world
     * @param fade   multiplier on the drawn opacity, for scenery that recedes with distance. 1 for
     *               everything that does not — a carriage two slots away is as real as one slot away
     */
    public record Surround(Source source, BlockPos origin, float fade) {

        public Surround(Source source, BlockPos origin) {
            this(source, origin, 1.0F);
        }
    }

    /**
     * Everything the answer depends on, gathered so {@link #around} can stay pure.
     *
     * <p>Both sides can fill this in: the server from {@code DungeonTrainWorldData}, the client from
     * {@code BuilderBoundsState} plus the volumes it was sent. {@code roomMode} and {@code roomSize}
     * are resolved by the caller rather than looked up here, because the lookup reads a template
     * sidecar and this class does no I/O.</p>
     *
     * @param parked how many carriages are actually standing on the track
     */
    public record Context(BuilderMode mode, BuilderNewOptions.SubType subType, String buildName,
                          CarriageDims dims, int parked,
                          PortalRoomMode roomMode, Vec3i roomSize,
                          TrackKind trackKind, String variantId) {}

    private BuilderSurroundings() {}

    /** What surrounds this build. Empty when the mode has nothing to put around it. */
    public static List<Surround> around(Context ctx) {
        if (ctx == null || ctx.mode() == null || ctx.dims() == null) {
            return List.of();
        }
        List<Surround> out = new ArrayList<>();
        addCarriageGroup(out, ctx);
        addTrack(out, ctx);
        addRoom(out, ctx);
        return List.copyOf(out);
    }

    /**
     * The rest of the train: the shell you are inside, the slots either side, and the pads capping
     * the group.
     *
     * <p>Only for a mode that has a train to imply one around — {@link BuilderMode#carriageCount()}
     * is zero for the track and portal modes, and a group of nothing has no slots and no pads.</p>
     */
    private static void addCarriageGroup(List<Surround> out, Context ctx) {
        int full = BuilderWorldLayout.ghostGroupCarriages(ctx.mode());
        if (full <= 0) {
            return;
        }
        CarriageDims dims = ctx.dims();
        int padLength = CarriagePlacer.halfPadLen(dims);
        int y = BuilderWorldLayout.TRAIN_Y;

        // The pads go whatever is parked: a full group stamps them solid today and a one-carriage
        // world never had them, and neither is what a pad is — it frames the silhouette and is not
        // part of the build either way.
        List<Integer> pads = BuilderGhostSlots.padMinXFor(full, dims.length(), padLength);
        if (pads.size() == 2) {
            out.add(new Surround(new Source.FlatbedPad(CarriagePlacer.HalfPadSide.BACK),
                    new BlockPos(pads.get(0), y, 0)));
            out.add(new Surround(new Source.FlatbedPad(CarriagePlacer.HalfPadSide.FRONT),
                    new BlockPos(pads.get(1), y, 0)));
        }

        if (ctx.parked() != 1) {
            return;   // a full group is missing no slot, and has no single carriage to be inside
        }
        BlockPos parkedMin = new BlockPos(
                BuilderBounds.buildVolumes(1, dims).get(0).minX(), y, 0);

        for (int slotX : BuilderGhostSlots.of(parkedMin.getX(), 1, full, dims.length(), padLength)
                .carriageMinX()) {
            out.add(new Surround(new Source.Carriage(ctx.variantId()), new BlockPos(slotX, y, 0)));
        }

        // The carriage you are standing in, when what you are authoring is what fills one. Not for
        // Parts — a part *is* a piece of the shell, so there the shell is the build.
        if (ctx.mode() == BuilderMode.INSIDE_CARRIAGE
                && ctx.subType() == BuilderNewOptions.SubType.CARRIAGE_ROOM) {
            out.add(new Surround(new Source.CarriageSkin(ctx.variantId()), parkedMin));
        }
    }

    /**
     * The line underneath.
     *
     * <p>Whole-corridor rather than the stretch under the train: a run that stopped where the
     * carriages end would draw a hard edge across the platform at a point that means nothing, and
     * that moves every time the parked count changes.</p>
     *
     * <p>A track build gets {@link Source.TrackScene} instead — there the line is the thing being
     * judged, with its tunnels and pillars, rather than a floor for something else.</p>
     */
    private static void addTrack(List<Surround> out, Context ctx) {
        if (!BuilderWorldSetup.hasScenery(ctx.mode())) {
            return;   // Train Dimensions builds in open void: no platform, no line
        }
        if (ctx.trackKind() != null) {
            out.add(new Surround(new Source.TrackScene(ctx.trackKind(), ctx.buildName()),
                    BlockPos.ZERO));
            return;
        }
        out.add(new Surround(new Source.TrackCorridor(BuilderWorldLayout.MIN_XZ,
                BuilderWorldLayout.MAX_XZ), BlockPos.ZERO));
    }

    /**
     * The copies an open portal room repeats into, and the boundary it repeats against.
     *
     * <p>Straight from {@link BuilderRoomGhosts}, which already knows both the window the real thing
     * would tile and how it should fade out at the edge of it. Translated into surrounds rather than
     * re-derived, so the ghosts cannot claim the space goes further than it does.</p>
     */
    private static void addRoom(List<Surround> out, Context ctx) {
        if (ctx.roomMode() == null || ctx.roomSize() == null || ctx.buildName() == null
                || ctx.buildName().isEmpty()) {
            return;
        }
        BuilderRoomGhosts.Ghosts ghosts = BuilderRoomGhosts.of(ctx.roomMode(), ctx.roomSize());
        if (ghosts.isEmpty()) {
            return;
        }
        BlockPos origin = BuilderWorldLayout.portalRoomOrigin(ctx.roomSize());
        if (ghosts.shell()) {
            out.add(new Surround(new Source.RoomShell(ctx.buildName()), origin));
        }
        Vec3i size = ctx.roomSize();
        for (BuilderRoomGhosts.Tile tile : ghosts.tiles()) {
            out.add(new Surround(
                    new Source.RoomTile(ctx.buildName(),
                            tile.detail() == BuilderRoomGhosts.Detail.FULL ? Detail.FULL : Detail.SHELL),
                    origin.offset(tile.tileX() * size.getX(), 0, tile.tileZ() * size.getZ()),
                    BuilderRoomGhosts.fadeFor(tile.ring(), ghosts.maxRing())));
        }
    }

    /**
     * Whether a cell of a carriage is shell rather than contents.
     *
     * <p>The exact complement of {@code CarriageContentsPlacer}'s interior box — one in from each
     * perimeter face — so what {@link Source.CarriageSkin} covers and what a carriage room saves can
     * never overlap. Pinned by {@code BuilderSurroundingsTest} against the capture box itself,
     * because the failure would be silent and terrible: showing the skin would start showing, and
     * standing it up would start overwriting, blocks somebody had just placed in their room.</p>
     */
    public static boolean onSkin(int x, int y, int z, CarriageDims dims) {
        return x == 0 || x == dims.length() - 1
                || y == 0 || y == dims.height() - 1
                || z == 0 || z == dims.width() - 1;
    }
}
