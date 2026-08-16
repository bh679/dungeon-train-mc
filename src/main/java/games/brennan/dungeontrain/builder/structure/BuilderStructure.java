package games.brennan.dungeontrain.builder.structure;

import games.brennan.dungeontrain.train.CarriagePlacer;
import net.minecraft.core.BlockPos;

/**
 * One piece of scenery around a Train Builder build: what it is, and where it goes.
 *
 * <p>Two halves, kept apart on purpose. A {@link Kind} says <em>what a thing is made of</em> and
 * carries only what decides that; a {@link Placement} says <em>where a thing goes</em>. The split is
 * what makes a group of three carriages one template read instead of three — same {@link Kind},
 * three {@link Placement}s — and it is why {@link Kind} is safe to use as a cache key.</p>
 *
 * <h2>Sealed, deliberately</h2>
 *
 * <p>Adding a piece of scenery has to be a compile error in every place that shows one or stands one
 * up, rather than a silent omission in one of them. A non-sealed marker interface, or a string tag,
 * would make "the renderer forgot about tunnels" a thing you find in a screenshot.</p>
 *
 * <h2>A declaration, not a snapshot</h2>
 *
 * <p>Nothing here records blocks. {@code Carriage("standard")} says <em>a standard carriage stands
 * here</em>, and the blocks behind that are looked up when something needs them. That distinction is
 * the point rather than a tidiness argument — the mechanisms this replaces kept a captured copy of
 * what they had lifted out of the world, and every bug they had came from the copy: one taken in one
 * mode replayed in another, a second read finding what the first had taken and replacing the only
 * copy with an empty one.</p>
 *
 * <p>Free of client-only imports and of any world access, so both sides can compute the same answer
 * and the arithmetic stays testable.</p>
 */
public final class BuilderStructure {

    private BuilderStructure() {}

    /**
     * Which control governs a piece of scenery.
     *
     * <p>One three-state control covers all of them today. This enum is the seam the per-category
     * controls hang off when they arrive: the declaration already groups its output, so that change
     * is a new panel reading {@link Kind#category()}, not a rewrite of what the panel would read.</p>
     */
    public enum Category {
        /** Whole empty carriages filling the rest of the group. */
        CARRIAGES,
        /**
         * The shell of the carriage a build sits <em>inside</em>.
         *
         * <p>Its own category rather than part of {@link #CARRIAGES} because it is the one surround
         * you stand in: it is the difference between having walls to build against and floating in
         * the middle of your own contents, which is a different decision from whether the neighbours
         * down the line are drawn.</p>
         */
        CARRIAGE_SHELL,
        /** The half-flatbed pads capping a group. */
        FLATBED_ENDS,
        /** The line itself — bed and rails. */
        TRACKS,
        /** Columns holding an elevated line up, and the arch between them. */
        PILLARS,
        /** Tunnel sections and the portals capping a run. */
        TUNNELS,
        /** A staircase climbing to the deck. */
        STAIRS,
        /** The copies a portal room repeats into. */
        ROOM_TILING,
        /** The boundary a Bedrock Lock room repeats against. */
        ROOM_BEDROCK
    }

    /** How much of a repeating room copy is worth drawing — a cost answer, not a stylistic one. */
    public enum Detail {
        /**
         * Every cell of the room. What actually sells "this space repeats", and affordable only for
         * the ring you can reach out and touch.
         */
        FULL,
        /**
         * The room's outer box only. A full window is over a hundred tiles, so copying every cell
         * everywhere is an order of magnitude past what the renderer will take — and past the first
         * ring the silhouette is all that reads at distance anyway.
         */
        SHELL,
        /**
         * The floor and ceiling rows, and nothing between them — for the room mode whose whole claim
         * is that there are no walls. A {@link #SHELL} of a wall-less room would draw the walls it
         * says it doesn't have.
         */
        PLANES
    }

    /**
     * What a piece of scenery is made of.
     *
     * <p>Every implementation is a record, so value equality comes for free and two identical
     * surrounds are one cache entry. Each one's cells are <b>local to its own low corner</b>; the
     * {@link Placement} says where that corner lands.</p>
     */
    public sealed interface Kind {

        /** Which control governs this piece. */
        Category category();

        /** A whole empty carriage — one of the slots in the group the build sits in. */
        record Carriage(String variantId) implements Kind {
            @Override
            public Category category() {
                return Category.CARRIAGES;
            }
        }

        /**
         * The shell of the carriage the build is inside: walls, roof and deck, but not the interior.
         *
         * <p>Separate from {@link Carriage} because it is the one surround that overlaps a build
         * volume, and so the one that must never be drawn or written past its skin — see
         * {@link BuilderStructure#onSkin}.</p>
         */
        record CarriageShell(String variantId) implements Kind {
            @Override
            public Category category() {
                return Category.CARRIAGE_SHELL;
            }
        }

        /** One half-flatbed pad capping the group. The side carries its own mirroring. */
        record FlatbedPad(CarriagePlacer.HalfPadSide side) implements Kind {
            @Override
            public Category category() {
                return Category.FLATBED_ENDS;
            }
        }

        /**
         * A run of line from {@code fromX} to {@code toX} inclusive, in world X.
         *
         * <p>The X range is content rather than placement: each {@code TILE_LENGTH} tile rolls its
         * own variant against the world seed the way the generator rolls it, so a run starting
         * somewhere else is made of different blocks. Cells come out local to {@code (fromX, bed,
         * trackZMin)}, which is what lets the same run serve the ground corridor and the elevated
         * line — only the {@link Placement}'s Y differs.</p>
         */
        record TrackRun(int fromX, int toX) implements Kind {
            @Override
            public Category category() {
                return Category.TRACKS;
            }
        }

        /**
         * One pillar column and the arch hanging off it, {@code height} blocks tall.
         *
         * <p>The arch is part of the column rather than its own kind because it is built from the
         * column's <em>own</em> section templates read downward — that is what carries an authored
         * pillar's look into the arch instead of the arch being a separate thing that can drift from
         * it. Splitting them would mean resolving the same two templates twice to no end.</p>
         *
         * <p>{@code centreX} is content, not placement: the generator keys each section's variant
         * pick on the column's world X, so two adjacent columns can legitimately differ.</p>
         */
        record PillarColumn(int centreX, int height) implements Kind {
            @Override
            public Category category() {
                return Category.PILLARS;
            }
        }

        /** One tunnel section, {@code mirrorX} when it faces the other way down the run. */
        record TunnelSection(String name, boolean mirrorX) implements Kind {
            @Override
            public Category category() {
                return Category.TUNNELS;
            }
        }

        /** One tunnel portal — the mouth capping a run. */
        record TunnelPortal(String name, boolean mirrorX) implements Kind {
            @Override
            public Category category() {
                return Category.TUNNELS;
            }
        }

        /**
         * A staircase flight {@code height} blocks tall, climbing beside the column at
         * {@code centreX}.
         *
         * <p>The height is content because a flight is one 8-tall template stamped over and over
         * until it reaches the floor — that repeat is how one template becomes a staircase of any
         * height, and a preview that drew a single stamp would show a flight ending in mid-air.</p>
         *
         * <p>{@code mirrorZ} is which way the steps face. The generator puts a staircase on either
         * side of the line and mirrors the {@code +Z} one across its own footprint
         * ({@code TrackGenerator.stampStairsDescendingWorldgen} sets {@code Mirror.LEFT_RIGHT} for
         * exactly that side), so a flight drawn without it climbs into the wall instead of out of
         * it — the same blocks, facing backwards.</p>
         */
        record StairsFlight(int centreX, int height, boolean mirrorZ) implements Kind {
            @Override
            public Category category() {
                return Category.STAIRS;
            }
        }

        /** One repeat of an open portal room, at the given level of {@link Detail}. */
        record RoomTile(String name, Detail detail) implements Kind {
            @Override
            public Category category() {
                return Category.ROOM_TILING;
            }
        }

        /** The one-block bedrock skin hugging a Bedrock Lock room — its boundary, which does not tile. */
        record RoomBedrock(String name) implements Kind {
            @Override
            public Category category() {
                return Category.ROOM_BEDROCK;
            }
        }
    }

    /**
     * One piece of scenery, at one place.
     *
     * @param origin where the kind's own local {@code (0,0,0)} lands in the world
     * @param fade   multiplier on drawn opacity, for scenery that recedes. 1 for everything that does
     *               not — a carriage two slots away is as real as one slot away
     */
    public record Placement(Kind kind, BlockPos origin, float fade) {

        public Placement(Kind kind, BlockPos origin) {
            this(kind, origin, 1.0F);
        }

        /** The same piece, {@code dx} blocks along the train — how one carriage's worth is repeated. */
        public Placement offsetBy(int dx) {
            return dx == 0 ? this : new Placement(kind, origin.offset(dx, 0, 0), fade);
        }
    }

    /**
     * Whether a cell of a carriage is shell rather than contents.
     *
     * <p>The exact complement of {@code CarriageContentsPlacer}'s interior box — one in from each
     * perimeter face — so what {@link Kind.CarriageShell} covers and what a carriage room saves can
     * never overlap. Pinned by {@code BuilderStructureNeedsTest} against the capture box itself,
     * because the failure would be silent and bad in both directions: drawing the skin would start
     * drawing blocks somebody had just placed in their room, and standing it up would start
     * overwriting them.</p>
     */
    public static boolean onSkin(int x, int y, int z, int length, int height, int width) {
        return x == 0 || x == length - 1
                || y == 0 || y == height - 1
                || z == 0 || z == width - 1;
    }
}
