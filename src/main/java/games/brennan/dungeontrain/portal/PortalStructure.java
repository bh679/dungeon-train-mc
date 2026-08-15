package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.train.CarriageDims;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.util.Objects;

/**
 * One portal pair's twin structure, as actually built:
 *
 * <pre>
 *   [plug] [entry twin] [room] [exit twin] [plug]
 * </pre>
 *
 * <p><b>Why this is a record rather than a {@code BlockPos}.</b> The exit twin's position is one
 * corridor plus one room along from the entry twin — and the room's length comes from whichever
 * {@code portal_room} template this pair rolled, so it is not a constant. Four separate places used
 * to re-derive that offset from {@code dims.length() + POCKET_LENGTH}: where the exit twin is
 * stamped, how far {@code eraseTwin} reaches, the occupancy box, and the origin the EXIT role's
 * {@code PortalFrames} maps into. If any of them disagreed with the others, a player walking back
 * out of the room would be mapped onto a corridor that is not there. So the structure carries what
 * it was built from, and everything reads it from here.</p>
 *
 * <p><b>The room identity is stable, the origin is not.</b> A pair's room name is a pure function of
 * its key and the world seed, so it never changes across the life of a pair. The origin moves
 * whenever the train drifts far enough to need a re-stamp — and because the old record is kept until
 * the new one replaces it, {@code eraseTwin} always clears exactly the box that was written, even if
 * the author saved a longer room in between.</p>
 *
 * <p><b>{@link #kind} is fixed for the life of the structure, and for the same reason as
 * {@link #mode}.</b> Which corridor shape a pair drew decides how long its twins are and how far
 * apart they stand, so a pair that changed its mind mid-visit would move the exit frame out from
 * under whoever was walking back through it. It is drawn once, when the pair is planned
 * ({@link PortalCarriageSelection#corridorKindFor}), and every reader takes it from here rather than
 * re-drawing.</p>
 *
 * <p><b>{@link #mode} is fixed for the life of the structure.</b> It is read once when the pair is
 * planned rather than looked up per tick, so an author saving a different mode while somebody is
 * standing in the room cannot change the walls around them mid-visit. The next pair to be planned
 * picks it up.</p>
 *
 * <p><b>{@link #tiling} grows and shrinks; {@link #spanX} never does.</b> The endless modes append
 * copies of the room around the base one on both horizontal axes, including straight through the row
 * the twins stand in, masked off the volume the corridors own so each twin is placed only once. So
 * {@link #exitOrigin} and {@link #spanX} stay exactly what the room's own length makes them, which is
 * what keeps the EXIT frame under a player's feet still. The tiled copies widen
 * {@link #tiledMinX}..{@link #tiledMaxZ} instead, and the occupancy box and the erase sweep read
 * those.</p>
 *
 * @param origin     world position of the entry twin's minimum corner — the structure's origin
 * @param roomName   the {@code portal_room} variant this pair rolled
 * @param roomSize   the room's full box, shell included, as stamped (length is the free axis)
 * @param settings   what this room does at its walls, and what its copies are, resolved when the
 *                   pair was planned
 * @param tiling     which copies of the room are currently standing
 * @param exitCopies which of the room's extra corridors are currently standing
 * @param kind       which of the two corridor shapes this pair's two corridors are
 */
public record PortalStructure(BlockPos origin, String roomName, Vec3i roomSize,
                              PortalRoomSettings settings, PortalRoomTiling tiling,
                              PortalExitCopies exitCopies, PortalRoomTiling.Tile exitTile,
                              PortalCorridorKind kind) {

    public PortalStructure {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(roomName, "roomName");
        Objects.requireNonNull(roomSize, "roomSize");
        if (settings == null) settings = PortalRoomSettings.DEFAULT;
        if (tiling == null) tiling = PortalRoomTiling.base();
        if (exitCopies == null) exitCopies = PortalExitCopies.NONE;
        if (exitTile == null) exitTile = PortalRoomTiling.Tile.BASE;
        if (kind == null) kind = PortalCorridorKind.DEFAULT;
    }

    /** Back-compat 3-arg form — a default-mode structure with only its base room standing. */
    public PortalStructure(BlockPos origin, String roomName, Vec3i roomSize) {
        this(origin, roomName, roomSize, PortalRoomSettings.DEFAULT, PortalRoomTiling.base(),
            PortalExitCopies.NONE, PortalRoomTiling.Tile.BASE, PortalCorridorKind.DEFAULT);
    }

    /** The five-part form this record was before extra corridors existed. */
    public PortalStructure(BlockPos origin, String roomName, Vec3i roomSize,
                           PortalRoomSettings settings, PortalRoomTiling tiling) {
        this(origin, roomName, roomSize, settings, tiling, PortalExitCopies.NONE,
            PortalRoomTiling.Tile.BASE, PortalCorridorKind.DEFAULT);
    }

    /** The six-part form, before an exit could stand anywhere but directly opposite. */
    public PortalStructure(BlockPos origin, String roomName, Vec3i roomSize,
                           PortalRoomSettings settings, PortalRoomTiling tiling,
                           PortalExitCopies exitCopies) {
        this(origin, roomName, roomSize, settings, tiling, exitCopies,
            PortalRoomTiling.Tile.BASE, PortalCorridorKind.DEFAULT);
    }

    /** The seven-part form, before a pair could draw a short corridor. */
    public PortalStructure(BlockPos origin, String roomName, Vec3i roomSize,
                           PortalRoomSettings settings, PortalRoomTiling tiling,
                           PortalExitCopies exitCopies, PortalRoomTiling.Tile exitTile) {
        this(origin, roomName, roomSize, settings, tiling, exitCopies, exitTile,
            PortalCorridorKind.DEFAULT);
    }

    /**
     * A structure with only its wall mode set, which is all most callers care about.
     *
     * <p>A static factory rather than a second constructor: an overload differing from the canonical
     * one only in that parameter's type is ambiguous the moment somebody passes {@code null} for
     * both, which is exactly what a test asserting the null-handling does.</p>
     */
    public static PortalStructure withMode(BlockPos origin, String roomName, Vec3i roomSize,
                                           PortalRoomMode mode, PortalRoomTiling tiling) {
        return new PortalStructure(origin, roomName, roomSize,
            PortalRoomSettings.DEFAULT.withMode(mode), tiling, PortalExitCopies.NONE,
            PortalRoomTiling.Tile.BASE);
    }

    /** What this room does at its walls. */
    public PortalRoomMode mode() {
        return settings.mode();
    }

    /** How many extra corridors back to the train this room lays, and how far apart. */
    public PortalRoomExits exits() {
        return settings.effectiveExits();
    }

    /**
     * The seed index the copy at {@code tile} rolls its block variants and container contents from.
     *
     * <p><b>{@code pairKey} is in the mix, and has to be.</b> This used to be {@code roomName}'s hash
     * and nothing else — so every pair on the train that rolled the same room name got byte-identical
     * variants, chests and furnishing, in every world ever generated. Walking into the tenth portal
     * showed the first one again. The pair's key is the entry corridor's real carriage index, which
     * is what makes one portal's room differ from the next's; the name stays in so two different rooms
     * at the same key still separate.</p>
     *
     * <p>Under {@link PortalRoomCopies#EXACT} every copy shares the base room's index, so the hall is
     * the same room repeated block for block. Under {@link PortalRoomCopies#DYNAMIC} the index mixes
     * in the copy's position, so copies differ from one another.</p>
     *
     * <p><b>Still not a loot machine.</b> Both inputs are fixed for the life of the pair — the key is
     * its position on the train and the tile is a grid cell — so the index is a pure function of
     * <i>where</i>, never of <i>when</i>. A copy that retires as the tiling window slides, or a whole
     * structure re-stamped after the train drifts, reproduces the room and the chests the player left
     * rather than a fresh roll.</p>
     */
    public int variantIndexFor(PortalRoomTiling.Tile tile, int pairKey) {
        int base = Objects.hash(roomName.hashCode(), pairKey);
        if (settings.copies() != PortalRoomCopies.DYNAMIC) return base;
        return Objects.hash(base, tile.x(), tile.z());
    }

    /** Length of this pair's room along the direction of travel. */
    public int roomLength() {
        return roomSize.getX();
    }

    /** Width of this pair's room across the direction of travel — the tile stride in Z. */
    public int roomWidth() {
        return roomSize.getZ();
    }

    /**
     * X offset from the entry twin's origin to the exit twin's — one corridor plus one room.
     *
     * <p>A <i>corridor</i>, not a carriage: under {@link PortalCorridorKind#LONG}
     * {@link PortalCorridorSize#corridorLength} is longer than {@code dims.length()}. The twins have
     * to stand exactly one corridor apart across the room or the frame a player is mapped into would
     * not line up with the blocks around them — which is why this reads {@link #kind} rather than
     * assuming either shape.</p>
     */
    public int exitTwinOffsetX(CarriageDims dims) {
        return PortalCorridorSize.corridorLength(dims, kind) + roomLength();
    }

    /**
     * Minimum corner of the exit twin corridor — <b>wherever this pair stands it</b>.
     *
     * <p>Ordinarily that is directly opposite the room a player arrives in, one corridor plus one
     * room along. A pair whose {@link PortalRoomExits} said so stands it beside another tile
     * entirely ({@link #exitTile}), so the far side of the arrival room is just more room and the way
     * onward is a corridor to go and find.</p>
     *
     * <p><b>Decided once, when the pair is planned.</b> {@link PortalStructure}'s own warning is that
     * this position feeds {@code PortalFrames} and must not shift under a player standing in it —
     * that is about shifting it per tick, as the tiling grows. This offset is fixed for the life of
     * the structure and survives a relocation ({@link #movedTo}), so every reader still agrees with
     * every other, which is the property that warning is really protecting.</p>
     */
    public BlockPos exitOrigin(CarriageDims dims) {
        return origin.offset(
            exitTwinOffsetX(dims) + exitTile.x() * roomLength(), 0, exitTile.z() * roomWidth());
    }

    /**
     * The coordinate frame the exit corridor is stamped and masked from — this structure moved so
     * that its <i>base</i> arrangement lands on {@link #exitTile}.
     *
     * <p>What makes a moved exit cost nothing downstream: {@code stampCorridorHalf} and
     * {@link PortalCorridorMask#forCorridor} both read a structure's own {@code exitOrigin} and
     * {@code roomOrigin}, and handing them this shadow gets the corridor <i>and its seal ring against
     * the right room</i> without either learning that exits can move. The seal ring is the part that
     * would otherwise be silently wrong: it fills the room's cross-section at the mouth, and the room
     * at the mouth is no longer the base one.</p>
     */
    public PortalStructure exitShadow() {
        return shadowAt(exitTile);
    }

    /** Minimum corner of the room box, centred on the corridor's doorway line. */
    public BlockPos roomOrigin(CarriageDims dims, PortalCarriageLayout layout) {
        // Centred on this pair's OWN room width, not the world minimum — a wider authored room
        // still has to line its interior centre up with the corridor's doorway.
        return PortalRoomLayout.roomOrigin(origin, dims, layout, roomSize.getZ());
    }

    /**
     * Total X span from the entry twin's origin to the far end of the exit twin.
     *
     * <p>Deliberately blind to {@link #tiling}. Copies of the room are laid around the corridors
     * rather than through them, so however far the room repeats, the twins neither move nor change —
     * which is what keeps every reader of this figure, {@code PortalFrames} included, agreeing.</p>
     */
    public int spanX(CarriageDims dims) {
        return exitTwinOffsetX(dims) + PortalCorridorSize.corridorLength(dims, kind);
    }

    /** Minimum corner of the room copy standing at {@code tile}. */
    public BlockPos tileOrigin(CarriageDims dims, PortalCarriageLayout layout,
                               PortalRoomTiling.Tile tile) {
        return roomOrigin(dims, layout)
            .offset(tile.x() * roomLength(), 0, tile.z() * roomWidth());
    }

    /**
     * How far an endless room lets a player see, in blocks: five rooms out, measured on whichever of
     * the room's two horizontal axes is narrower.
     *
     * <p>Scaled by the room rather than fixed, so "five rooms out" means the same thing in a small
     * room and a large one. The narrower axis is the honest one: fog is a single distance in every
     * direction, so a long thin room has to be fogged at the distance that hides its short side.</p>
     */
    public float fogRadius() {
        // Bedrockless repeats nothing, so "five rooms out" has nothing to count. What it has instead
        // is the clearance it swept, and fogging at exactly that distance is what puts the fog where
        // the emptiness ends rather than somewhere inside it.
        if (mode() == PortalRoomMode.BEDROCKLESS) return PortalRoomLayout.VOID_CLEARANCE;
        return (float) PortalRoomTiling.MAX_RADIUS * Math.min(roomLength(), roomWidth());
    }

    /**
     * How far a player can see at the far edge of the fogged region, in blocks — the other end of
     * {@link #fogRadius}, which is what they see at the room's own walls.
     *
     * <p>Equal to {@link #fogRadius} for every mode but {@link PortalRoomMode#BEDROCKLESS}, which is
     * to say: no ramp, the fog is one distance wherever you stand. That is right for the tiling
     * modes, whose room repeats — there is no "outside the room" for a ramp to measure from, and a
     * player at the edge of the stamped copies is looking at more of the same room, not at the void.
     * Bedrockless is the mode with an outside, and the walk into it is the thing worth dramatising.</p>
     */
    public float fogMinRadius() {
        return mode() == PortalRoomMode.BEDROCKLESS ? PortalRoomLayout.VOID_FOG_MIN : fogRadius();
    }

    /**
     * How far past the room's own extent the fogged region reaches, in blocks.
     *
     * <p>Zero for every mode whose fog stops at the copies it has stamped. {@link
     * PortalRoomMode#BEDROCKLESS} is the one that owns space it did not build in: the region has to
     * cover the swept clearance, or a player who mines out through the room's shell leaves the fog
     * while still standing in the void it was hiding.</p>
     */
    public int fogPad() {
        return mode() == PortalRoomMode.BEDROCKLESS ? PortalRoomLayout.VOID_CLEARANCE : 0;
    }

    /** How many blocks one copy of this room occupies — what the tiling budget is measured in. */
    public int blocksPerTile() {
        return roomSize.getX() * roomSize.getY() * roomSize.getZ();
    }

    /** How many copies of this room may stand at once. */
    public int tileBudget() {
        return PortalRoomTiling.budgetTiles(blocksPerTile());
    }

    /** Lowest world X any standing copy of the room reaches. */
    public int tiledMinX(CarriageDims dims, PortalCarriageLayout layout) {
        return roomOrigin(dims, layout).getX() + tiling.minTileX() * roomLength();
    }

    /** Highest world X (inclusive) any standing copy of the room reaches. */
    public int tiledMaxX(CarriageDims dims, PortalCarriageLayout layout) {
        return roomOrigin(dims, layout).getX() + (tiling.maxTileX() + 1) * roomLength() - 1;
    }

    /** Lowest world Z any standing copy of the room reaches. */
    public int tiledMinZ(CarriageDims dims, PortalCarriageLayout layout) {
        return roomOrigin(dims, layout).getZ() + tiling.minTileZ() * roomWidth();
    }

    /** Highest world Z (inclusive) any standing copy of the room reaches. */
    public int tiledMaxZ(CarriageDims dims, PortalCarriageLayout layout) {
        return roomOrigin(dims, layout).getZ() + (tiling.maxTileZ() + 1) * roomWidth() - 1;
    }

    /** Which tile a world position falls in — the grid cell, whether or not a copy stands there. */
    public PortalRoomTiling.Tile tileAt(CarriageDims dims, PortalCarriageLayout layout,
                                        double worldX, double worldZ) {
        BlockPos room = roomOrigin(dims, layout);
        return new PortalRoomTiling.Tile(
            Math.floorDiv((int) Math.floor(worldX) - room.getX(), roomLength()),
            Math.floorDiv((int) Math.floor(worldZ) - room.getZ(), roomWidth()));
    }

    /**
     * The same structure relocated — same room, same settings, back to just its base tile.
     *
     * <p>{@link #exitTile} comes along. Where a pair stands its exit is part of what it <i>is</i>,
     * decided when it was planned, like the room it rolled — not part of what is currently standing.
     * A pair re-stamped further down the track has to be the same portal a player walked out of.</p>
     */
    public PortalStructure movedTo(BlockPos newOrigin) {
        return new PortalStructure(newOrigin, roomName, roomSize, settings, PortalRoomTiling.base(),
            PortalExitCopies.NONE, exitTile, kind);
    }

    /** The same structure with a different set of room copies standing. */
    public PortalStructure withTiling(PortalRoomTiling newTiling) {
        return new PortalStructure(origin, roomName, roomSize, settings, newTiling, exitCopies,
            exitTile, kind);
    }

    /** The same structure with a different set of extra corridors standing. */
    public PortalStructure withExitCopies(PortalExitCopies newCopies) {
        return new PortalStructure(origin, roomName, roomSize, settings, tiling, newCopies,
            exitTile, kind);
    }

    /** The same structure standing its exit beside a different tile. */
    public PortalStructure withExitTile(PortalRoomTiling.Tile newExitTile) {
        return new PortalStructure(origin, roomName, roomSize, settings, tiling, exitCopies,
            newExitTile, kind);
    }

    /**
     * This structure translated so that its <b>room slot lands on {@code tile}</b> — the geometry an
     * extra corridor at that tile is stamped from.
     *
     * <p>The whole of {@link PortalExitSites}' placement rule rests on one identity:
     * {@link PortalRoomLayout#roomOrigin} derives the room from the entry origin by translation
     * alone, so a structure moved by a whole number of tiles has its room exactly on that tile, and
     * its two corridors exactly where a copy's corridors belong. {@link #origin} is then where an
     * {@link PortalCarriageRole#ENTRY} copy goes and {@link #exitOrigin} where an
     * {@link PortalCarriageRole#EXIT} copy goes — which is why nothing about a copy's position, seal
     * ring, plug or mask has to be re-derived: every routine that lays the base pair works on the
     * shadow unchanged.</p>
     *
     * <p>The shadow carries no tiling and no copies of its own. It is a coordinate frame, not a
     * second structure — never store one.</p>
     *
     * <p><b>And it carries no exit offset.</b> A shadow's job is to say "here is the base arrangement,
     * moved onto this tile", so it has to start from the base arrangement. Letting {@link #exitTile}
     * through would offset it a second time — every extra corridor would land a tile's worth away
     * from where the mask says it is, in a pair that had moved its exit and nowhere else, which is
     * exactly the kind of bug that only shows up in one setting.</p>
     */
    public PortalStructure shadowAt(PortalRoomTiling.Tile tile) {
        PortalStructure unmoved = PortalRoomTiling.Tile.BASE.equals(exitTile)
            ? this
            : withExitTile(PortalRoomTiling.Tile.BASE);
        if (PortalRoomTiling.Tile.BASE.equals(tile)) return unmoved;
        return new PortalStructure(
            origin.offset(tile.x() * roomLength(), 0, tile.z() * roomWidth()),
            roomName, roomSize, settings, PortalRoomTiling.base(), PortalExitCopies.NONE,
            PortalRoomTiling.Tile.BASE, kind);
    }

    /** Minimum corner of the corridor an extra {@code role} copy anchored at {@code tile} occupies. */
    public BlockPos exitCopyOrigin(CarriageDims dims, PortalExitSites.Site site) {
        PortalStructure shadow = shadowAt(site.tile());
        return site.role() == PortalCarriageRole.ENTRY ? shadow.origin() : shadow.exitOrigin(dims);
    }
}
