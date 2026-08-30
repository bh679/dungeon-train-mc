package games.brennan.dungeontrain.portal;

import games.brennan.dungeontrain.track.variant.TrackKind;
import games.brennan.dungeontrain.track.variant.TrackVariantWeights;

/**
 * Everything a {@code portal_room} variant says about its own boundary: what it does at its walls,
 * — for either endless mode — whether the tiles it appends are identical or rolled afresh, whether
 * the room is furnished from the ordinary contents pool, how many extra ways back to the train it
 * scatters through its copies, whether every book inside it is by one author, and whether it is lit
 * as though it stood outdoors.
 *
 * <h2>All six live in the one {@code mode} tag</h2>
 * <p>On disk that reads {@code "mode": "endless_repetition/dynamic/fit/random:12/signature/day"}, or
 * just {@code "mode": "endless_repetition"} when the five trailing settings are at their defaults.
 * {@code TemplateMeta.mode} is documented as an <i>opaque per-kind tag</i> — what it contains is the
 * owning kind's business — so encoding several settings in it is exactly what that field is for, and
 * it keeps a record shared by carriages and contents from growing fields only portal rooms will ever
 * read.</p>
 *
 * <p>They are still separate controls in the editor: a Walls row, a Contents row, a Books row, and —
 * when the walls repeat — a Copies row and an Exits row. Only the storage is shared.</p>
 *
 * <p><b>Trailing segments are optional on the way in.</b> Every tag written before Contents, Exits,
 * Books or Sky existed has one to five segments and still parses, to a room that behaves exactly as
 * it did.</p>
 *
 * <h2>A room's two doorways are stored as one door and a difference</h2>
 * <p>{@code doorOffset} / {@code doorHeightOffset} place the room's box; the exit pair says where
 * the <b>exit</b> doorway sits on that same box. An absent exit segment does not mean "centred", it
 * means "wherever the entry door is" — which is what makes every tag written before this parse to
 * the mirrored behaviour it has always had, and what keeps {@link #toTag} writing the shorter form
 * for the rooms (nearly all of them) whose two doors agree.</p>
 *
 * <p><b>That inheritance is a rule about reading a tag, not about editing one.</b> It resolves once,
 * on the way in. Every wither then moves the one door it names and leaves the other alone, so an
 * author who places a door on one mouth moves that mouth and nothing else — see
 * {@link #withDoorOffset}.</p>
 *
 * <h2>Exits is the one setting whose default depends on another</h2>
 * <p>An absent Exits segment does not mean "off", it means "whatever this mode wants" — see
 * {@link PortalRoomMode#defaultExits}. That is what lets the shipped {@code labrynth} room, tagged
 * {@code endless_repetition/dynamic} long before any of this existed, pick up its extra corridors
 * without anybody editing the weights file. It is also why {@link #withMode} has to re-derive: a
 * value that was only ever the old mode's default is not a choice the author made.</p>
 *
 * @param mode     what the room does at its walls
 * @param copies   what its copies are, when it makes any
 * @param contents whether it is furnished from the contents pool, and how
 * @param exits    how many extra corridors back to the train it lays, and how far apart
 * @param books    whether every book found inside is by one author, and how that author is picked
 * @param sky      whether it is lit as though it stood outdoors, and under which sky
 * @param doorWall whether the copies standing against the portal carriages carry their own end wall
 *                 through the corridor mouth's plane, or leave it to the mouth's seal ring
 * @param doorOffset how far the shared walkway line sits from dead centre of the room's own width
 * @param doorHeightOffset how far the corridor's floor line sits above the room's own bottom edge
 * @param exitDoorOffset the same, for the room's <b>exit</b> doorway — null means "not said", which
 *                       resolves to {@code doorOffset} so the two doors mirror as they always did
 * @param exitDoorHeightOffset the vertical twin of {@code exitDoorOffset}, null meaning the same
 */
public record PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                                 PortalRoomContents contents, PortalRoomExits exits,
                                 PortalRoomBooks books, PortalRoomSky sky,
                                 PortalRoomDoorWall doorWall, PortalRoomDoorOffset doorOffset,
                                 PortalRoomDoorHeightOffset doorHeightOffset,
                                 PortalRoomDoorOffset exitDoorOffset,
                                 PortalRoomDoorHeightOffset exitDoorHeightOffset) {

    /** Separates the mode from the settings that follow it in the stored tag. */
    private static final String SEPARATOR = "/";

    public static final PortalRoomSettings DEFAULT = new PortalRoomSettings(
        PortalRoomMode.DEFAULT, PortalRoomCopies.DEFAULT, PortalRoomContents.DEFAULT, null,
        PortalRoomBooks.DEFAULT, PortalRoomSky.NONE, PortalRoomDoorWall.DEFAULT,
        PortalRoomDoorOffset.DEFAULT, PortalRoomDoorHeightOffset.DEFAULT, null, null);

    public PortalRoomSettings {
        if (mode == null) mode = PortalRoomMode.DEFAULT;
        if (copies == null) copies = PortalRoomCopies.DEFAULT;
        if (contents == null) contents = PortalRoomContents.DEFAULT;
        // After the mode has been settled, never before: a null here means "this room said nothing
        // about its exits", and what that resolves to is the mode's business.
        if (exits == null) exits = mode.defaultExits();
        if (books == null) books = PortalRoomBooks.DEFAULT;
        if (sky == null) sky = PortalRoomSky.NONE;
        if (doorWall == null) doorWall = PortalRoomDoorWall.DEFAULT;
        if (doorOffset == null) doorOffset = PortalRoomDoorOffset.DEFAULT;
        if (doorHeightOffset == null) doorHeightOffset = PortalRoomDoorHeightOffset.DEFAULT;
        // After the two entry offsets have been settled, never before — on exactly the reasoning the
        // `exits` line above uses. A null here means "this room said nothing about its exit door",
        // and what that resolves to is the entry door, which is what mirroring is.
        if (exitDoorOffset == null) exitDoorOffset = doorOffset;
        if (exitDoorHeightOffset == null) exitDoorHeightOffset = doorHeightOffset;
    }

    /** The nine settings this record carried before the two doors could differ — both mirrored. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                              PortalRoomContents contents, PortalRoomExits exits,
                              PortalRoomBooks books, PortalRoomSky sky, PortalRoomDoorWall doorWall,
                              PortalRoomDoorOffset doorOffset,
                              PortalRoomDoorHeightOffset doorHeightOffset) {
        this(mode, copies, contents, exits, books, sky, doorWall, doorOffset, doorHeightOffset,
            null, null);
    }

    /** The eight settings this record carried before Door Height Offset existed, corridor at the floor. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                              PortalRoomContents contents, PortalRoomExits exits,
                              PortalRoomBooks books, PortalRoomSky sky, PortalRoomDoorWall doorWall,
                              PortalRoomDoorOffset doorOffset) {
        this(mode, copies, contents, exits, books, sky, doorWall, doorOffset,
            PortalRoomDoorHeightOffset.DEFAULT);
    }

    /** The seven settings this record carried before Door Offset existed, with the door kept centred. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                              PortalRoomContents contents, PortalRoomExits exits,
                              PortalRoomBooks books, PortalRoomSky sky, PortalRoomDoorWall doorWall) {
        this(mode, copies, contents, exits, books, sky, doorWall, PortalRoomDoorOffset.DEFAULT,
            PortalRoomDoorHeightOffset.DEFAULT);
    }

    /** The six settings this record carried before Door Wall existed, with the mouth's seal kept. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                              PortalRoomContents contents, PortalRoomExits exits,
                              PortalRoomBooks books, PortalRoomSky sky) {
        this(mode, copies, contents, exits, books, sky, PortalRoomDoorWall.DEFAULT,
            PortalRoomDoorOffset.DEFAULT);
    }

    /** The boundary settings alone, unfurnished — the pair this record was before Contents existed. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies) {
        this(mode, copies, PortalRoomContents.DEFAULT, null, PortalRoomBooks.DEFAULT,
            PortalRoomSky.NONE, PortalRoomDoorWall.DEFAULT, PortalRoomDoorOffset.DEFAULT);
    }

    /** The three settings this record carried before Exits existed, at the mode's own default. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                              PortalRoomContents contents) {
        this(mode, copies, contents, null, PortalRoomBooks.DEFAULT, PortalRoomSky.NONE,
            PortalRoomDoorWall.DEFAULT, PortalRoomDoorOffset.DEFAULT);
    }

    /** The four settings this record carried before Books existed, with no author lock. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                              PortalRoomContents contents, PortalRoomExits exits) {
        this(mode, copies, contents, exits, PortalRoomBooks.DEFAULT, PortalRoomSky.NONE,
            PortalRoomDoorWall.DEFAULT, PortalRoomDoorOffset.DEFAULT);
    }

    /** The five settings this record carried before Sky existed, lit only by its own lamps. */
    public PortalRoomSettings(PortalRoomMode mode, PortalRoomCopies copies,
                              PortalRoomContents contents, PortalRoomExits exits,
                              PortalRoomBooks books) {
        this(mode, copies, contents, exits, books, PortalRoomSky.NONE,
            PortalRoomDoorWall.DEFAULT, PortalRoomDoorOffset.DEFAULT);
    }

    /**
     * Read a stored tag. Total: anything unrecognised in any segment falls back to that segment's
     * default, so a hand-edited typo stamps a normal room rather than failing a pair's stamp. A tag
     * with fewer segments than there are settings — every tag written before a setting was added —
     * takes the default for the ones it does not name.
     */
    public static PortalRoomSettings parse(String tag) {
        if (tag == null) return DEFAULT;
        String[] parts = tag.split(SEPARATOR, -1);
        String exitsSegment = segment(parts, 3);
        return new PortalRoomSettings(
            PortalRoomMode.parse(segment(parts, 0)),
            PortalRoomCopies.parse(segment(parts, 1)),
            PortalRoomContents.parse(segment(parts, 2)),
            // Null rather than PortalRoomExits.parse(null): an absent segment must reach the
            // constructor as "unsaid" so the mode's default applies, not as a value of its own.
            exitsSegment == null ? null : PortalRoomExits.parse(exitsSegment),
            PortalRoomBooks.parse(segment(parts, 4)),
            PortalRoomSky.parse(segment(parts, 5)),
            PortalRoomDoorWall.parse(segment(parts, 6)),
            PortalRoomDoorOffset.parse(segment(parts, 7)),
            PortalRoomDoorHeightOffset.parse(segment(parts, 8)),
            // Null rather than the centred default when absent: an unsaid exit door mirrors the
            // entry door, and only the constructor knows what that is.
            segment(parts, 9) == null ? null : PortalRoomDoorOffset.parse(segment(parts, 9)),
            segment(parts, 10) == null ? null : PortalRoomDoorHeightOffset.parse(segment(parts, 10)));
    }

    /** Segment {@code index} of a split tag, or null when the tag is shorter than that. */
    private static String segment(String[] parts, int index) {
        return index < parts.length ? parts[index] : null;
    }

    /** The settings a named room variant is authored with. */
    public static PortalRoomSettings of(String roomName) {
        return parse(TrackVariantWeights.modeFor(TrackKind.PORTAL_ROOM, roomName));
    }

    /**
     * The tag to store.
     *
     * <p>Trailing settings are only written when they would change something — so a room that never
     * repeats and is not furnished round-trips as the bare mode id it always was, and a tag written
     * before Contents or Exits existed is re-written unchanged.</p>
     *
     * <p>The segments are positional, so a later one cannot be written without the earlier ones in
     * front of it. Where an earlier setting means nothing here its default id is written as the
     * placeholder, which that setting's own {@code parse} reads back as the same default.</p>
     *
     * <p>"Would change something" for Exits means <i>differs from this mode's default</i>, not
     * differs from a fixed value — an Endless Repetition room that lays corridors is saying what its
     * mode already says, and writing it out would put a segment in every such tag for nothing.</p>
     */
    public String toTag() {
        PortalRoomCopies effectiveCopies = effectiveCopies();
        PortalRoomExits effectiveExits = effectiveExits();
        PortalRoomDoorWall effectiveDoorWall = effectiveDoorWall();
        if (doorsDiffer()) {
            // The longest tag this class writes, and the only shape that names the exit door at all.
            // "Would change something" here means differs from the ENTRY door rather than from a
            // fixed value, on the same reasoning Exits uses against its mode's default: a room whose
            // two doors agree is saying what the entry segment already says.
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id() + SEPARATOR + books.id() + SEPARATOR + sky.id()
                + SEPARATOR + effectiveDoorWall.id() + SEPARATOR + doorOffset.id()
                + SEPARATOR + doorHeightOffset.id()
                + SEPARATOR + exitDoorOffset.id() + SEPARATOR + exitDoorHeightOffset.id();
        }
        if (!PortalRoomDoorHeightOffset.DEFAULT.equals(doorHeightOffset)) {
            // Every earlier segment goes out at whatever it
            // effectively is — a later segment cannot be written without the ones in front of it,
            // and each parse reads its own placeholder back as the same value.
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id() + SEPARATOR + books.id() + SEPARATOR + sky.id()
                + SEPARATOR + effectiveDoorWall.id() + SEPARATOR + doorOffset.id()
                + SEPARATOR + doorHeightOffset.id();
        }
        if (!PortalRoomDoorOffset.DEFAULT.equals(doorOffset)) {
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id() + SEPARATOR + books.id() + SEPARATOR + sky.id()
                + SEPARATOR + effectiveDoorWall.id() + SEPARATOR + doorOffset.id();
        }
        if (effectiveDoorWall != PortalRoomDoorWall.DEFAULT) {
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id() + SEPARATOR + books.id() + SEPARATOR + sky.id()
                + SEPARATOR + effectiveDoorWall.id();
        }
        if (sky.lights()) {
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id() + SEPARATOR + books.id() + SEPARATOR + sky.id();
        }
        // Value equality, not identity: PortalRoomBooks is a record, and parsing an "off" segment
        // builds a NEW instance equal to DEFAULT rather than returning it. An identity test here has
        // any tag that has ever carried a books segment re-writing itself one segment longer for
        // good — which switching Sky on and back off is now an easy way to trigger, since doing so
        // writes the books placeholder on the way through.
        if (!PortalRoomBooks.DEFAULT.equals(books)) {
            // Exits is written out as whatever it effectively is, even when that is the mode's own
            // default: a later segment cannot be written without the earlier ones in front of it,
            // and parse reads that placeholder back as the same value it stood in for.
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id() + SEPARATOR + books.id();
        }
        if (!effectiveExits.equals(mode.defaultExits())) {
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id()
                + SEPARATOR + effectiveExits.id();
        }
        if (contents != PortalRoomContents.DEFAULT) {
            return mode.id() + SEPARATOR + effectiveCopies.id() + SEPARATOR + contents.id();
        }
        if (effectiveCopies.equals(PortalRoomCopies.DEFAULT)) return mode.id();
        return mode.id() + SEPARATOR + effectiveCopies.id();
    }

    /**
     * True when the Door Wall control applies at all.
     *
     * <p>{@link PortalRoomMode#ENDLESS_REPETITION} alone. The setting describes what an appended copy
     * does with its <b>own end wall</b>, and that is the only mode whose tiles carry one:
     * {@link PortalRoomMode#ENDLESS_OPEN} writes floor and ceiling and nothing else, so it has no
     * wall to carry through the plane and the mouth's ring is all there could ever be. The two
     * non-tiling modes append nothing at all.</p>
     */
    public boolean doorWallApplies() {
        return mode.tilesWholeRoom();
    }

    /**
     * What this room actually does at the corridor mouths: {@link #doorWall} where the control
     * applies, and {@link PortalRoomDoorWall#DEFAULT} where it does not.
     *
     * <p>Read this rather than {@link #doorWall} anywhere the answer drives block writes, for the
     * same reason {@link #effectiveCopies} and {@link #effectiveExits} exist. A room whose walls were
     * changed from Endless Repetition to Endless Open still carries whatever Door Wall value it had,
     * and honouring it there would hand a copy a plane it has no wall to fill — which is a hole in
     * the one boundary that may not have one.</p>
     */
    public PortalRoomDoorWall effectiveDoorWall() {
        return doorWallApplies() ? doorWall : PortalRoomDoorWall.DEFAULT;
    }

    /** True when the Copies control applies at all — either endless mode appends tiles to roll. */
    public boolean copiesApply() {
        return mode.copiesApply();
    }

    /**
     * What this room actually does about its copies: {@link #copies} where every part of it applies,
     * and {@link PortalRoomCopies#DEFAULT} where it does not.
     *
     * <p>Read this rather than {@link #copies} anywhere the answer drives block writes, for the same
     * reason {@link #effectiveExits} exists: a room carries whatever setting it was last given, and
     * honouring one the current walls cannot use writes the wrong blocks.</p>
     *
     * <p>Two ways for it not to apply. A room that appends no tiles at all has nothing for the
     * setting to describe. And {@link PortalRoomCopies.Kind#SINGLE} is
     * {@link PortalRoomMode#ENDLESS_OPEN}'s alone — under Endless Repetition it would append solid
     * cubes where rooms should be, so it reads back as Exact there.</p>
     */
    public PortalRoomCopies effectiveCopies() {
        if (!copiesApply()) return PortalRoomCopies.DEFAULT;
        boolean singleHere = !copies.repeatsOneBlock() || mode.singleCopiesApply();
        return singleHere ? copies : PortalRoomCopies.DEFAULT;
    }

    /**
     * The same settings at the next Copies value — what the editor's one cycling button steps to.
     *
     * <p>Asked of the settings rather than of {@link PortalRoomCopies} directly because the list of
     * available values depends on the walls, and the walls live here. Single is skipped under a mode
     * that cannot use it, so the button never stops on an option that means nothing.</p>
     */
    public PortalRoomSettings nextCopies() {
        return withCopies(copies.next(mode.singleCopiesApply()));
    }

    /** The same settings with {@link PortalRoomCopies.Kind#SINGLE}'s block set to {@code blockId}. */
    public PortalRoomSettings withCopiesBlock(String blockId) {
        return withCopies(copies.withBlock(blockId));
    }

    /** True when the Exits control applies at all — only an endless room has anywhere to put one. */
    public boolean exitsApply() {
        return mode.exitsApply();
    }

    /**
     * What this room actually does about extra corridors: {@link #exits} where the control applies,
     * and the mode's own default where it does not.
     *
     * <p>Read this rather than {@link #exits} anywhere the answer drives block writes. A room whose
     * walls were changed from Endless Repetition to Bedrock Lock still carries whatever Exits value
     * it had, and honouring it would stamp corridors into a sealed room that has no copies for them
     * to stand in.</p>
     */
    public PortalRoomExits effectiveExits() {
        return exitsApply() ? exits : mode.defaultExits();
    }

    /**
     * The same settings at a different wall mode.
     *
     * <p><b>Exits is re-derived when it was only ever the old mode's default.</b> Switching the walls
     * from Endless Repetition to Endless Open should land on Endless Open's answer — no corridors —
     * rather than carry across a value the author never chose, and the only way to tell a choice from
     * an inherited default is that the inherited one still equals what it was inherited from. An
     * author who has actually set Exits keeps it across the switch.</p>
     */
    public PortalRoomSettings withMode(PortalRoomMode newMode) {
        boolean inherited = exits.equals(mode.defaultExits());
        return new PortalRoomSettings(newMode, copies, contents, inherited ? null : exits, books, sky,
            doorWall, doorOffset, doorHeightOffset, exitDoorOffset, exitDoorHeightOffset);
    }

    public PortalRoomSettings withCopies(PortalRoomCopies newCopies) {
        return new PortalRoomSettings(mode, newCopies, contents, exits, books, sky, doorWall, doorOffset,
            doorHeightOffset, exitDoorOffset, exitDoorHeightOffset);
    }

    public PortalRoomSettings withContents(PortalRoomContents newContents) {
        return new PortalRoomSettings(mode, copies, newContents, exits, books, sky, doorWall, doorOffset,
            doorHeightOffset, exitDoorOffset, exitDoorHeightOffset);
    }

    public PortalRoomSettings withExits(PortalRoomExits newExits) {
        return new PortalRoomSettings(mode, copies, contents, newExits, books, sky, doorWall, doorOffset,
            doorHeightOffset, exitDoorOffset, exitDoorHeightOffset);
    }

    public PortalRoomSettings withBooks(PortalRoomBooks newBooks) {
        return new PortalRoomSettings(mode, copies, contents, exits, newBooks, sky, doorWall, doorOffset,
            doorHeightOffset, exitDoorOffset, exitDoorHeightOffset);
    }

    public PortalRoomSettings withSky(PortalRoomSky newSky) {
        return new PortalRoomSettings(mode, copies, contents, exits, books, newSky, doorWall, doorOffset,
            doorHeightOffset, exitDoorOffset, exitDoorHeightOffset);
    }

    public PortalRoomSettings withDoorWall(PortalRoomDoorWall newDoorWall) {
        return new PortalRoomSettings(mode, copies, contents, exits, books, sky, newDoorWall, doorOffset,
            doorHeightOffset, exitDoorOffset, exitDoorHeightOffset);
    }

    /** The same settings at the next Door Wall value — what the editor's one cycling button steps to. */
    public PortalRoomSettings nextDoorWall() {
        return withDoorWall(doorWall.next());
    }

    /**
     * The same settings with the door offset set to {@code newDoorOffset}, unclamped.
     *
     * <p>Unclamped because the legal range depends on the room's own width and the world's
     * {@code CarriageDims}, neither of which this record carries — the door pointer clamps via
     * {@link PortalRoomLayout#clampDoorOffset} before calling this.</p>
     *
     * <p><b>The exit door is left exactly where it is</b>, mirroring or not — deliberately unlike the
     * way {@link #withMode} re-derives Exits. An author moves a doorway by placing a door on <i>that
     * doorway</i>, so the door they placed is the only one that may move; a room whose two doors
     * happened to agree simply stops agreeing, which is the author saying so. Re-deriving here would
     * mean a click on the near mouth silently dragged the far one with it, and there would then be no
     * gesture that moves one door of a mirrored room at all.</p>
     */
    public PortalRoomSettings withDoorOffset(PortalRoomDoorOffset newDoorOffset) {
        return new PortalRoomSettings(mode, copies, contents, exits, books, sky, doorWall, newDoorOffset,
            doorHeightOffset, exitDoorOffset, exitDoorHeightOffset);
    }

    /**
     * The same settings with the door height offset set to {@code newDoorHeightOffset}, unclamped —
     * see {@link #withDoorOffset} for why unclamped is correct here too, and for why the exit door
     * stays where it is rather than following this one.
     */
    public PortalRoomSettings withDoorHeightOffset(PortalRoomDoorHeightOffset newDoorHeightOffset) {
        return new PortalRoomSettings(mode, copies, contents, exits, books, sky, doorWall, doorOffset,
            newDoorHeightOffset, exitDoorOffset, exitDoorHeightOffset);
    }

    /**
     * The same settings with the <b>exit</b> door offset set to {@code newExitDoorOffset}, unclamped —
     * see {@link #withDoorOffset} for why unclamped is correct here too.
     */
    public PortalRoomSettings withExitDoorOffset(PortalRoomDoorOffset newExitDoorOffset) {
        return new PortalRoomSettings(mode, copies, contents, exits, books, sky, doorWall, doorOffset,
            doorHeightOffset, newExitDoorOffset, exitDoorHeightOffset);
    }

    /** The vertical twin of {@link #withExitDoorOffset}, likewise unclamped. */
    public PortalRoomSettings withExitDoorHeightOffset(
        PortalRoomDoorHeightOffset newExitDoorHeightOffset) {
        return new PortalRoomSettings(mode, copies, contents, exits, books, sky, doorWall, doorOffset,
            doorHeightOffset, exitDoorOffset, newExitDoorHeightOffset);
    }

    /**
     * True when this room's two doorways are authored apart — the one question every writer of the
     * exit corridor's position, and {@link #toTag}, actually asks.
     *
     * <p>Compared as stored rather than as clamped: two offsets that clamp to the same place in
     * today's room still differ as authored, and a room later widened should recover the author's
     * intent rather than have had it silently folded away on the last save.</p>
     */
    public boolean doorsDiffer() {
        return !doorOffset.equals(exitDoorOffset)
            || !doorHeightOffset.equals(exitDoorHeightOffset);
    }
}
