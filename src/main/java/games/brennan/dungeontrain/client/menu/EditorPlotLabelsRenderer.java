package games.brennan.dungeontrain.client.menu;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.net.EditorPlotLabelsPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;

import java.util.List;

/**
 * Client-side renderer for the floating editor-plot control panels. Each panel
 * is a small world-space billboard above a stamped template plot, showing the
 * model name plus interactive controls (weight arrows, save / reset / clear,
 * and template-specific buttons like Contents for carriages).
 *
 * <p>Cylindrical billboard around world-up so the panel rotates to face the
 * camera horizontally but stays upright in Y — same basis the older labels
 * used.</p>
 *
 * <p>Layout (top → bottom, only the rows applicable to the entry's category
 * are rendered):
 * <ol>
 *   <li><b>Name</b> — always.</li>
 *   <li><b>Weight</b> — when {@code weight != NO_WEIGHT}: {@code [-] xN [+]}.</li>
 *   <li><b>Actions</b> — when the entry has an actionable category
 *       (CARRIAGES / CONTENTS / TRACKS pillars/adjuncts/tunnels):
 *       {@code [Save 70%][R 15%][C 15%]}.</li>
 *   <li><b>Contents</b> — CARRIAGES only.</li>
 * </ol></p>
 *
 * <p>All layout constants are package-private so
 * {@link games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelRaycast}
 * shares the same numbers — the raycast hit math and the visible cells must
 * match exactly or the wrong cell highlights / dispatches.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class EditorPlotLabelsRenderer {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Cell kinds the raycast and input handler need to identify. Order doesn't
     * matter — only the values are referenced by name.
     */
    public enum CellKind {
        NONE,
        /** Name row — clicking teleports the player to this template's plot (lands on top by default). Always clickable. */
        NAME,
        WEIGHT_DEC,
        WEIGHT_INC,
        ACTION_SAVE,
        ACTION_RESET,
        ACTION_CLEAR,
        BUTTON_CONTENTS,
        /** "Enter" row — clicking teleports the player INSIDE the plot (under the cage). Visible only when already inPlot. */
        BUTTON_ENTER_INSIDE,
        LENGTH_DEC,
        LENGTH_INC,
        WIDTH_DEC,
        WIDTH_INC,
        HEIGHT_DEC,
        HEIGHT_INC,
        /** The number between a dimension row's arrows — opens a typing field for that axis alone. */
        LENGTH_TYPE,
        WIDTH_TYPE,
        HEIGHT_TYPE,
        /** The whole mode row — one button, clicking it steps to the next mode. */
        MODE_CYCLE,
        /** The sub-mode row — only shown while the mode makes copies. */
        COPIES_CYCLE,
        /** The floor row under it — sets the floor palette from the hand. */
        COPIES_FLOOR_HELD,
        /** The Edit half of that row — opens the Block Variant menu on the floor palette. */
        COPIES_FLOOR_EDIT,
        /** The roof row — the same two halves, aimed at the plane over the player's head. */
        COPIES_ROOF_HELD,
        COPIES_ROOF_EDIT,
        /** The furnishing row — whether the room takes a contents template, and how it is fitted. */
        ROOM_CONTENTS_CYCLE,
        /** The door-wall row — whether a copy carries its own wall through a corridor mouth. */
        DOOR_WALL_CYCLE,
        /** The sky row — whether the room is lit as though it stood outdoors, and under which sky. */
        ROOM_SKY_CYCLE,
        /** The author-lock row — whether the room stocks its shelves from one person. */
        ROOM_BOOKS_CYCLE,
        /** The Edit half of that row — the weights and the band, which only a stocking room has. */
        ROOM_BOOKS_EDIT,
        /** The extra-corridors row — only shown while the walls repeat. */
        EXITS_CYCLE,
        /** The stepper for how far apart those extra corridors go. */
        EXIT_EVERY_DEC,
        EXIT_EVERY_INC,
        EXIT_EVERY_TYPE,
        /** The stepper for how often the base pair's exit is walled off. */
        EXIT_MOVE_DEC,
        EXIT_MOVE_INC,
        EXIT_MOVE_TYPE
    }

    /**
     * The rows a panel can show, in display order.
     *
     * <p><b>Single source of truth for the row walk.</b> Counting, hit-testing and drawing used to
     * re-derive the sequence independently with their own cursors, so a row inserted at a different
     * position in any one of them made clicks land on the wrong cell. They all consume
     * {@link #rows} now, so the three cannot drift.</p>
     */
    public enum RowKind {
        NAME, WEIGHT, LENGTH, WIDTH, HEIGHT, MODE, COPIES, COPIES_FLOOR, COPIES_ROOF, DOOR_WALL,
        ROOM_CONTENTS, ROOM_BOOKS, ROOM_SKY, EXITS, EXIT_EVERY, EXIT_MOVE, ENTER, ACTION, CONTENTS
    }

    /**
     * How much of the Books row the value keeps when an Edit button shares it. Wide enough for
     * "Books: Author Mix" at the panel's text scale, leaving a button that is still comfortably
     * clickable.
     */
    private static final double BOOKS_CYCLE_SHARE = 0.72;

    /** The rows {@code entry} shows, top to bottom. */
    public static RowKind[] rows(EditorPlotLabelsPacket.Entry entry) {
        RowKind[] buf = new RowKind[RowKind.values().length];
        int n = 0;
        buf[n++] = RowKind.NAME;
        if (entry.weight() != EditorPlotLabelsPacket.NO_WEIGHT) buf[n++] = RowKind.WEIGHT;
        if (hasDimensionRows(entry)) {
            buf[n++] = RowKind.LENGTH;
            buf[n++] = RowKind.WIDTH;
            buf[n++] = RowKind.HEIGHT;
        }
        if (hasModeRow(entry)) buf[n++] = RowKind.MODE;
        if (hasCopiesRow(entry)) buf[n++] = RowKind.COPIES;
        if (hasCopiesBlockRow(entry)) {
            buf[n++] = RowKind.COPIES_FLOOR;
            buf[n++] = RowKind.COPIES_ROOF;
        }
        if (hasDoorWallRow(entry)) buf[n++] = RowKind.DOOR_WALL;
        if (hasRoomContentsRow(entry)) buf[n++] = RowKind.ROOM_CONTENTS;
        if (hasRoomBooksRow(entry)) buf[n++] = RowKind.ROOM_BOOKS;
        if (hasRoomSkyRow(entry)) buf[n++] = RowKind.ROOM_SKY;
        if (hasExitsRow(entry)) buf[n++] = RowKind.EXITS;
        if (hasExitEveryRow(entry)) buf[n++] = RowKind.EXIT_EVERY;
        if (hasExitMoveRow(entry)) buf[n++] = RowKind.EXIT_MOVE;
        if (hasEnterRow(entry)) buf[n++] = RowKind.ENTER;
        if (hasActionRow(entry)) buf[n++] = RowKind.ACTION;
        if (hasContentsButton(entry)) buf[n++] = RowKind.CONTENTS;
        return java.util.Arrays.copyOf(buf, n);
    }

    /**
     * What this room does at its walls — portal rooms only, and only while the player is standing in
     * the plot, on the same reasoning as the dimension rows.
     *
     * <p>Sits directly under them because it is the same kind of fact: a property of the room's box
     * rather than of the template inside it.</p>
     */
    public static boolean hasModeRow(EditorPlotLabelsPacket.Entry entry) {
        return entry.inPlot()
            && "PORTALS".equals(entry.category())
            && !EditorPlotLabelsPacket.NO_MODE.equals(entry.roomMode());
    }

    /**
     * Length / width / height steppers — portal rooms only, and only while the player is standing
     * in the plot. A portal room is the one plot kind whose box the author chooses, so it is the
     * only one with anything to step.
     */
    public static boolean hasDimensionRows(EditorPlotLabelsPacket.Entry entry) {
        return entry.inPlot()
            && "PORTALS".equals(entry.category())
            && entry.roomLength() != EditorPlotLabelsPacket.NO_SIZE;
    }

    /** The value a dimension row displays, or {@code -1} if {@code kind} is not a dimension row. */
    public static int dimensionValue(EditorPlotLabelsPacket.Entry entry, RowKind kind) {
        return switch (kind) {
            case LENGTH -> entry.roomLength();
            case WIDTH -> entry.roomWidth();
            case HEIGHT -> entry.roomHeight();
            default -> -1;
        };
    }

    /** Command token for a dimension row's axis — {@code length} / {@code width} / {@code height}. */
    public static String dimensionAxis(RowKind kind) {
        return switch (kind) {
            case LENGTH -> "length";
            case WIDTH -> "width";
            case HEIGHT -> "height";
            default -> "";
        };
    }

    /**
     * Whether the Copies row shows: only when the walls are endless, since those are the only modes
     * that append tiles for the setting to describe.
     */
    public static boolean hasCopiesRow(EditorPlotLabelsPacket.Entry entry) {
        return hasModeRow(entry)
            && games.brennan.dungeontrain.portal.PortalRoomSettings.parse(entry.roomMode())
                .copiesApply();
    }

    /** What the Copies row reads, e.g. {@code "Copies: Dynamic"}. */
    public static String copiesLabel(String modeTag) {
        return "Copies: " + games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag)
            .copies().displayName();
    }

    /**
     * Whether the Floor and Roof rows show: only while the Copies row above them says Single, the
     * one value that reads a block. The two always appear together — a tile has both planes, and an
     * author who has set only one still needs to see that the other is unset.
     *
     * <p>Hidden rather than dimmed under the other two, the same way the Copies row itself is absent
     * rather than greyed out under walls that make no copies — a block for tiles that are copies of
     * the room is a control with nothing on the other end of it.</p>
     */
    public static boolean hasCopiesBlockRow(EditorPlotLabelsPacket.Entry entry) {
        return hasCopiesRow(entry) && hasCopiesBlockRowFor(entry.roomMode());
    }

    /**
     * The same question asked of a mode tag alone — what the command menu has to hand.
     *
     * <p>{@code effectiveCopies} and not the raw value, so a room still carrying Single from before
     * its walls were changed to Endless Repetition does not show a block row for a setting that mode
     * cannot use.</p>
     */
    public static boolean hasCopiesBlockRowFor(String modeTag) {
        games.brennan.dungeontrain.portal.PortalRoomSettings settings =
            games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag);
        return settings.copiesApply() && settings.effectiveCopies().repeatsOneBlock();
    }

    /**
     * True when {@code blockId} is the empty-placeholder sentinel — the author asked for air.
     *
     * <p>Id-only because that is all the row is sent: the label packet carries one block id per
     * plane for the icon, never a state. Same three command-block kinds
     * {@code CarriageVariantBlocks.isEmptyPlaceholder} covers, which is where the sentinel is
     * defined.</p>
     */
    public static boolean isAirSentinelId(String blockId) {
        return "minecraft:command_block".equals(blockId)
            || "minecraft:chain_command_block".equals(blockId)
            || "minecraft:repeating_command_block".equals(blockId);
    }

    /** Where the icon sits on a Copies plane row — right of its "Floor:" / "Roof:" label. */
    private static double copiesIconCentre(double halfW) {
        return -halfW + halfW * 0.75;
    }

    /**
     * Where the Edit half of a Copies plane row starts.
     *
     * <p>{@link #BOOKS_CYCLE_SHARE}, the same split the Books row uses, so the two read as one
     * pattern: a value on the left, a way into its editor on the right.</p>
     */
    private static double copiesEditLeft(double halfW) {
        return booksEditLeft(halfW);
    }

    /** True when {@code hitX} lands on a Copies plane row's Edit button rather than its value. */
    public static boolean copiesBlockHitIsEdit(double halfW, double hitX) {
        return hitX >= copiesEditLeft(halfW);
    }

    /**
     * What the Blocks row reads where it cannot draw icons — the command menu, which is text-only.
     *
     * <p>Names the gesture rather than the value. The value lives server-side and may be a variant
     * of several candidates; the plot panel shows it as an icon, and a text row that tried to would
     * either be a namespaced id forty characters long or, worse, the id in the mode tag — which
     * stops being the answer the moment a variant is authored. Saying what the row does is honest
     * at every width.</p>
     */
    public static String copiesBlockLabel(games.brennan.dungeontrain.portal.PortalRoomCopiesVariant.Plane plane) {
        return plane.displayName() + ": + held";
    }

    /**
     * Whether the Contents row shows: on every portal room, unlike Copies.
     *
     * <p>Furnishing is not a property of the walls — a sealed room can take a contents template as
     * readily as a repeating one — so it is gated on being a portal room at all, the same condition
     * as the Walls row, and not on what the Walls row currently says.</p>
     */
    public static boolean hasRoomContentsRow(EditorPlotLabelsPacket.Entry entry) {
        return hasModeRow(entry);
    }

    /** What the Contents row reads, e.g. {@code "Contents: Fit"}. */
    public static String roomContentsLabel(String modeTag) {
        return "Contents: " + games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag)
            .contents().displayName();
    }

    /**
     * Whether the Books row shows: on every portal room, on the same reasoning as Contents.
     *
     * <p>Not gated on the room being furnished. A room can hold books without drawing a contents
     * template — its own {@code .nbt} may have shelves stamped into it — so gating on Contents would
     * hide the control on exactly the hand-authored libraries most likely to want it.</p>
     */
    public static boolean hasRoomBooksRow(EditorPlotLabelsPacket.Entry entry) {
        return hasModeRow(entry);
    }

    /** What the Books row reads, e.g. {@code "Books: Random Signature"}. */
    public static String roomBooksLabel(String modeTag) {
        return "Books: " + games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag)
            .books().displayName();
    }

    /**
     * Whether the Sky row shows: on every portal room, on the same reasoning as Contents and Books.
     *
     * <p>Not gated on the room's walls or on what is inside it. Any room can be lit as though it
     * stood outdoors — that is a statement about the place the room is pretending to be, not about
     * how it seals or what it is furnished with.</p>
     */
    public static boolean hasRoomSkyRow(EditorPlotLabelsPacket.Entry entry) {
        return hasModeRow(entry);
    }

    /** What the Sky row reads, e.g. {@code "Sky: Daylight"}. */
    public static String roomSkyLabel(String modeTag) {
        return "Sky: " + games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag)
            .sky().displayName();
    }

    /**
     * Whether the Door Wall row shows on a plot panel: a portal room, in its plot, with its walls set
     * to Endless Repetition.
     *
     * <p>Delegates to the tag-shaped test the command menu uses, so the two panels cannot come to
     * disagree about when the setting is reachable.</p>
     */
    public static boolean hasDoorWallRow(EditorPlotLabelsPacket.Entry entry) {
        return hasModeRow(entry) && hasDoorWallRow(entry.roomMode());
    }

    /**
     * Whether the Door Wall row shows: only under Endless Repetition, the one mode whose appended
     * tiles carry a wall of their own for the corridor mouth's plane to be filled from.
     */
    public static boolean hasDoorWallRow(String modeTag) {
        if (modeTag == null) return false;
        return games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag).doorWallApplies();
    }

    /** What the Room Walls row reads, e.g. {@code "Room Walls: Kept"}. */
    public static String doorWallLabel(String modeTag) {
        return "Room Walls: " + games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag)
            .effectiveDoorWall().displayName();
    }

    /**
     * Whether the Exits row shows: only while the walls repeat, since only an endless room has
     * anywhere to put an extra way back to the train.
     *
     * <p>Both endless modes, unlike Copies — an Endless Open room is asked the question too, it just
     * answers Off by default.</p>
     */
    public static boolean hasExitsRow(EditorPlotLabelsPacket.Entry entry) {
        return hasModeRow(entry)
            && games.brennan.dungeontrain.portal.PortalRoomSettings.parse(entry.roomMode())
                .exitsApply();
    }

    /** What the Exits row reads, e.g. {@code "Exits: Random"}. */
    public static String exitsLabel(String modeTag) {
        return "Exits: " + games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag)
            .exits().displayName();
    }

    /**
     * Whether the spacing stepper shows: only when Exits is showing and is laying corridors.
     *
     * <p>Absent rather than dimmed at Off, on the same reasoning the Copies row is absent under a
     * mode that makes no copies — a control with nothing on the other end of it is worse than no
     * control.</p>
     */
    public static boolean hasExitEveryRow(EditorPlotLabelsPacket.Entry entry) {
        return hasExitsRow(entry)
            && games.brennan.dungeontrain.portal.PortalRoomSettings.parse(entry.roomMode())
                .exits().lays();
    }

    /**
     * What the spacing stepper reads.
     *
     * <p>Worded to the reading it is under, because the same number means two different things:
     * {@code On} spaces a lattice, {@code Random} sets odds. "Every 8" and "1 in 8" are the shortest
     * honest way to say which one is in force.</p>
     */
    public static String exitEveryLabel(String modeTag) {
        games.brennan.dungeontrain.portal.PortalRoomExits exits =
            games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag).exits();
        return exits.kind() == games.brennan.dungeontrain.portal.PortalRoomExits.Kind.RANDOM
            ? "1 in " + exits.every()
            : "Every " + exits.every();
    }

    /**
     * Whether the moved-exit stepper shows: only under Random.
     *
     * <p>Walling off the way straight back out is only fair when there is something unpredictable to
     * go and find. Under the lattice a player could work the walk out in advance; under Off it would
     * wall the only way onward there is.</p>
     */
    public static boolean hasExitMoveRow(EditorPlotLabelsPacket.Entry entry) {
        return hasExitsRow(entry)
            && games.brennan.dungeontrain.portal.PortalRoomSettings.parse(entry.roomMode())
                .exits().movesApply();
    }

    /** What the moved-exit stepper reads, e.g. {@code "Moved exit: 7/10"}. */
    public static String exitMoveLabel(String modeTag) {
        return "Moved exit: " + games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag)
            .exits().moveChance() + "/10";
    }

    /**
     * What the mode row reads, e.g. {@code "Walls: Endless Open"}.
     *
     * <p>Resolved through {@code PortalRoomMode.parse}, which is total, so a tag hand-edited into
     * {@code weights.json} that nothing recognises shows the default the room will actually behave as
     * rather than the misspelling.</p>
     */
    public static String modeLabel(String modeTag) {
        return "Walls: " + games.brennan.dungeontrain.portal.PortalRoomSettings.parse(modeTag)
            .mode().displayName();
    }

    /** Short prefix drawn to the left of a dimension row's number. */
    public static String dimensionLabel(RowKind kind) {
        return switch (kind) {
            case LENGTH -> "L";
            case WIDTH -> "W";
            case HEIGHT -> "H";
            default -> "";
        };
    }

    /** Where the player's crosshair is currently pointing. */
    public record Hovered(int entryIndex, CellKind cell) {
        public static final Hovered NONE = new Hovered(-1, CellKind.NONE);
    }

    /** Same composite as PartPositionMenuRenderer.PANEL_QUAD. */
    private static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":editor_plot_label_quad",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.QUADS,
        256,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(MenuRenderStates.SHADER_POSITION_COLOR)
            .setTransparencyState(MenuRenderStates.TRANSPARENCY_TRANSLUCENT)
            .setCullState(MenuRenderStates.CULL_DISABLED)
            .setDepthTestState(MenuRenderStates.DEPTH_LEQUAL)
            .setWriteMaskState(MenuRenderStates.WRITE_COLOR_ONLY)
            .createCompositeState(false)
    );

    /** Glyph→world scale. Vanilla nameplate is 0.025 — bumped for editor reading. */
    static final double TEXT_SCALE = 0.025;
    /** Height of one row (world units). */
    static final double ROW_H = 0.30;
    /** Min half-width so a 24-row panel still fits the "Save"/"Contents" button labels. */
    public static final double MIN_HALF_W = 0.85;
    /** Horizontal padding inside each row. */
    static final double PAD_X = 0.10;

    /**
     * Icon edge length and slot pitch on the Copies Block row, in panel-local units.
     *
     * <p>Matched to {@code StagePanelMenuRenderer}'s strip so the two read as one control at two
     * sizes rather than as two controls.</p>
     */
    public static final double COPIES_ICON_SIZE = ROW_H * 0.8;
    public static final double COPIES_ICON_SLOT = ROW_H;


    /** Backdrop fill for the whole panel. */
    private static final int BACKDROP_COLOR = 0xC8000000;
    /** Tint fill behind the hovered cell — copied from the part menu's hover yellow. */
    private static final int HOVER_COLOR = 0x60FFCC33;
    /** Subtle row separator. */
    private static final int ROW_SEP_COLOR = 0x40FFFFFF;
    /** Soft tint behind the action / contents buttons so they look pressable. */
    private static final int BUTTON_BG = 0x40FFEEBB;
    /** Green border drawn around the panel when the player is inside a bundled-template plot. */
    private static final int BORDER_COLOR_BUNDLED = 0xFF55FF55;
    /** Blue border drawn around the panel when the player is inside a user-authored plot. */
    private static final int BORDER_COLOR_USER = 0xFF5599FF;
    /** Orange border drawn around the panel when the player is inside an imported plot (file came from a package extracted into the user folder and not yet edited locally). */
    private static final int BORDER_COLOR_IMPORTED = 0xFFFF9933;
    /**
     * Border thickness in world units. Sits OUTSIDE the panel's
     * {@code ±halfW / ±halfH} bounding rectangle so it never overlaps the
     * dark backdrop fill — visually a frame around the panel.
     */
    private static final double BORDER_THICKNESS = 0.04;

    private static final int NAME_COLOR = 0xFFFFFFFF;
    private static final int WEIGHT_COLOR = 0xFFFFEEBB;
    private static final int ARROW_COLOR = 0xFFFFFFFF;
    private static final int BUTTON_TEXT_COLOR = 0xFFFFFFFF;
    private static final int BUTTON_TEXT_DIM_COLOR = 0xFFAAAAAA;
    /** The Copies Block row's "nothing here yet" hint — dimmer than a value. */
    private static final int LABEL_COLOR = 0xFFAAAAAA;

    private static volatile List<EditorPlotLabelsPacket.Entry> CACHE = List.of();
    private static volatile Hovered HOVERED = Hovered.NONE;

    private EditorPlotLabelsRenderer() {}

    /**
     * Wipe the renderer cache on world quit. Without this, the static
     * {@link #CACHE} survives across worlds in the integrated server — quit
     * to title with floating editor labels onscreen, open a different world,
     * and the panels keep rendering until the server pushes a new snapshot
     * (which it won't, if the new world has no editor state). Symmetric with
     * {@link games.brennan.dungeontrain.client.EditorStatusHudOverlay#onLoggingOut}.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        applySnapshot(EditorPlotLabelsPacket.empty());
    }

    /** Called from the packet handler on the client thread. */
    public static void applySnapshot(EditorPlotLabelsPacket packet) {
        if (packet.isEmpty()) {
            CACHE = List.of();
            HOVERED = Hovered.NONE;
            LOGGER.info("[DungeonTrain] EditorPlotLabels: snapshot cleared");
            return;
        }
        List<EditorPlotLabelsPacket.Entry> entries = List.copyOf(packet.entries());
        CACHE = entries;
        EditorPlotLabelsPacket.Entry first = entries.get(0);
        LOGGER.info("[DungeonTrain] EditorPlotLabels: client received {} entries (first: '{}' weight={} @ {})",
            entries.size(), first.name(), first.weight(), first.worldPos());
    }

    /** Read by the raycast / input handler. */
    public static List<EditorPlotLabelsPacket.Entry> entries() {
        return CACHE;
    }

    /** Read the latest hover (set by the raycast each frame). */
    public static Hovered hovered() {
        return HOVERED;
    }

    /** Set by the raycast each frame. */
    public static void setHovered(Hovered h) {
        HOVERED = h == null ? Hovered.NONE : h;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        // Master "Editor Menus" toggle — hide all world-space editor panels when off.
        if (!games.brennan.dungeontrain.client.EditorStatusHudOverlay.isEditorMenusVisible()) return;
        List<EditorPlotLabelsPacket.Entry> snapshot = CACHE;
        if (snapshot.isEmpty()) return;

        // Refresh hover from the camera frame so the highlight tracks the
        // crosshair without a one-tick lag, mirroring PartPositionMenuRenderer.
        games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        Hovered hovered = HOVERED;
        for (int i = 0; i < snapshot.size(); i++) {
            // Auto hides the other plots' panels while the player is standing in one. Indices stay
            // the snapshot's own, because they are the hover contract with EditorPlotPanelRaycast —
            // which skips the same entries, so nothing hidden is hoverable either.
            if (!games.brennan.dungeontrain.client.EditorMenusModeState.isPanelVisible(snapshot, i)) continue;
            EditorPlotLabelsPacket.Entry entry = snapshot.get(i);
            BlockPos pos = entry.worldPos();
            Vec3 anchor = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            CellKind hitCell = (hovered.entryIndex == i) ? hovered.cell : CellKind.NONE;
            drawPanel(ps, buffer, font, cam, anchor, entry, hitCell);
        }

        buffer.endBatch(PANEL_QUAD);
        buffer.endBatch();
    }

    /**
     * Compute the half-width of {@code entry}'s panel in world units. Same
     * formula used by the renderer and the raycast so cell rectangles agree.
     *
     * <p><b>Every row that can outgrow the panel has to be measured here.</b> The width used to come
     * from the name alone, on the assumption that {@link #MIN_HALF_W} covered every other row — true
     * while the longest was "Contents". The Walls row broke it: a variant called {@code default} is
     * six characters and "Walls: Endless Repetition" is twenty-five, so the label spilled out past
     * both edges of the backdrop. Taking the widest of the two means the panel fits whatever it
     * actually has to say, and the raycast reads the same figure so the cells stay under what is
     * drawn.</p>
     */
    public static double halfWidth(EditorPlotLabelsPacket.Entry entry, Font font) {
        return halfWidth(entry, font::width);
    }

    /**
     * {@link #halfWidth(EditorPlotLabelsPacket.Entry, Font)} against any text measurer.
     *
     * <p>Exists for the same reason {@code cellAt} has an explicit-halfWidth overload: the
     * {@link Font} is the one part of this that cannot run headless, and the width rule is worth a
     * test of its own now that more than the name feeds it.</p>
     */
    public static double halfWidth(EditorPlotLabelsPacket.Entry entry,
                                   java.util.function.ToIntFunction<String> measure) {
        double w = Math.max(MIN_HALF_W * 2.0, measure.applyAsInt(entry.name()) * TEXT_SCALE + 2 * PAD_X);
        if (hasModeRow(entry)) {
            w = Math.max(w, measure.applyAsInt(modeLabel(entry.roomMode())) * TEXT_SCALE + 2 * PAD_X);
        }
        if (hasCopiesRow(entry)) {
            w = Math.max(w, measure.applyAsInt(copiesLabel(entry.roomMode())) * TEXT_SCALE + 2 * PAD_X);
        }
        if (hasCopiesBlockRow(entry)) {
            // Label, one icon, and an Edit button — the same three-part shape the Books row has.
            w = Math.max(w, (measure.applyAsInt("Block:") + measure.applyAsInt("Edit")) * TEXT_SCALE
                + 4 * PAD_X + COPIES_ICON_SLOT);
        }
        if (hasExitsRow(entry)) {
            w = Math.max(w, measure.applyAsInt(exitsLabel(entry.roomMode())) * TEXT_SCALE + 2 * PAD_X);
        }
        if (hasExitMoveRow(entry)) {
            // The longest of the portal-room labels after Walls — the arrows either side leave it
            // only the middle third, so the panel has to be sized for it or it clips.
            w = Math.max(w, measure.applyAsInt(exitMoveLabel(entry.roomMode())) * 3 * TEXT_SCALE
                + 2 * PAD_X);
        }
        return w / 2.0;
    }

    /**
     * Number of rows visible for {@code entry}. Always ≥ 1 (name).
     *
     * <p>The weight DISPLAY row stays visible whenever the entry has a weight
     * pool, so a player flying along the row can still see each plot's pick
     * weight at a glance. The interactive arrows + the action / contents
     * rows only render when {@code entry.inPlot()} is true — the player has
     * to step into a cage to edit it.</p>
     */
    public static int rowCount(EditorPlotLabelsPacket.Entry entry) {
        return rows(entry).length;
    }

    /** "Enter" row — visible only when the player is already inside this plot. */
    public static boolean hasEnterRow(EditorPlotLabelsPacket.Entry entry) {
        return entry.inPlot() && !entry.category().isEmpty();
    }

    public static double halfHeight(EditorPlotLabelsPacket.Entry entry) {
        return rowCount(entry) * ROW_H / 2.0;
    }

    /**
     * True when the entry should show the Save/Reset/Clear action row —
     * requires both an actionable category AND the player being inside this
     * specific plot.
     */
    public static boolean hasActionRow(EditorPlotLabelsPacket.Entry entry) {
        if (!entry.inPlot()) return false;
        String c = entry.category();
        return "CARRIAGES".equals(c) || "CONTENTS".equals(c) || "TRACKS".equals(c)
            || "PORTALS".equals(c);
    }

    /**
     * Contents button — opens the per-template allow-list. Gated on the player being inside the
     * plot, and on there being a pool for the toggles to steer.
     *
     * <p>Carriages always have one. A portal room only has one <b>while its Contents setting is
     * on</b>: with it Off the room draws nothing, so an allow-list would be a screen full of
     * toggles that change nothing. The setting rides in the room's own mode tag, already on the
     * entry, so the button appears and disappears as the Contents row above it is cycled.</p>
     */
    public static boolean hasContentsButton(EditorPlotLabelsPacket.Entry entry) {
        if (!entry.inPlot()) return false;
        if ("CARRIAGES".equals(entry.category())) return true;
        return hasRoomContentsRow(entry)
            && games.brennan.dungeontrain.portal.PortalRoomSettings.parse(entry.roomMode())
                .contents().furnishes();
    }

    /** True when the weight arrows (interactive [-] / [+]) should render and accept clicks. */
    public static boolean hasWeightArrows(EditorPlotLabelsPacket.Entry entry) {
        return entry.inPlot() && entry.weight() != EditorPlotLabelsPacket.NO_WEIGHT;
    }

    /**
     * Build a cylindrical-billboard basis facing {@code cam} from {@code anchor}.
     * Shared by renderer and raycast so the click hit math matches the visible
     * panel exactly. Returns {@code [right, up, normal]}.
     */
    public static Vec3[] basis(Vec3 anchor, Vec3 cam) {
        Vec3 toCam = cam.subtract(anchor);
        Vec3 horiz = new Vec3(toCam.x, 0, toCam.z);
        Vec3 normal = horiz.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : horiz.normalize();
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 right = up.cross(normal).normalize();
        return new Vec3[]{right, up, normal};
    }

    /**
     * Map a panel-local hit at {@code (hitX, hitY)} to a {@link CellKind}.
     * Returns {@link CellKind#NONE} if the hit lies outside any clickable cell.
     */
    public static CellKind cellAt(EditorPlotLabelsPacket.Entry entry, Font font,
                                  double hitX, double hitY) {
        return cellAt(entry, halfWidth(entry, font), hitX, hitY);
    }

    /**
     * {@link #cellAt(EditorPlotLabelsPacket.Entry, Font, double, double)} with the panel's
     * half-width supplied directly.
     *
     * <p>Split out because the half-width is the one part of the hit math that needs a
     * {@link Font} — everything below it is pure arithmetic over the row list, which is exactly
     * the part worth testing.</p>
     */
    public static CellKind cellAt(EditorPlotLabelsPacket.Entry entry, double halfW,
                                  double hitX, double hitY) {
        double halfH = halfHeight(entry);
        if (hitX < -halfW || hitX > halfW || hitY < -halfH || hitY > halfH) return CellKind.NONE;

        RowKind[] rows = rows(entry);
        // Row 0 is the topmost (name); subsequent rows go down.
        // y range for row N: [halfH - (N+1)*ROW_H, halfH - N*ROW_H].
        int rowFromTop = (int) Math.floor((halfH - hitY) / ROW_H);
        if (rowFromTop < 0 || rowFromTop >= rows.length) return CellKind.NONE;

        return switch (rows[rowFromTop]) {
            // Name — clickable teleport target, except on parts plots, which have an empty
            // category and stay non-interactive to match their lack of action buttons.
            case NAME -> entry.category().isEmpty() ? CellKind.NONE : CellKind.NAME;
            // Weight is always visible (display only) but its arrows are clickable only from
            // inside the plot.
            case WEIGHT -> hasWeightArrows(entry) ? weightRowCell(hitX, halfW) : CellKind.NONE;
            // The number between the arrows types that row's axis on its own — the panel has no
            // keyboard entry of its own, and stepping from 13 to 48 is thirty-five taps.
            case LENGTH -> stepperCell(hitX, halfW, CellKind.LENGTH_DEC, CellKind.LENGTH_INC, CellKind.LENGTH_TYPE);
            case WIDTH -> stepperCell(hitX, halfW, CellKind.WIDTH_DEC, CellKind.WIDTH_INC, CellKind.WIDTH_TYPE);
            case HEIGHT -> stepperCell(hitX, halfW, CellKind.HEIGHT_DEC, CellKind.HEIGHT_INC, CellKind.HEIGHT_TYPE);
            // One button rather than a stepper: there are three modes, and naming the one you want
            // costs no more clicks than aiming at an arrow for it.
            case MODE -> CellKind.MODE_CYCLE;
            case COPIES -> CellKind.COPIES_CYCLE;
            case COPIES_FLOOR -> copiesBlockHitIsEdit(halfW, hitX)
                ? CellKind.COPIES_FLOOR_EDIT : CellKind.COPIES_FLOOR_HELD;
            case COPIES_ROOF -> copiesBlockHitIsEdit(halfW, hitX)
                ? CellKind.COPIES_ROOF_EDIT : CellKind.COPIES_ROOF_HELD;
            case DOOR_WALL -> CellKind.DOOR_WALL_CYCLE;
            case ROOM_CONTENTS -> CellKind.ROOM_CONTENTS_CYCLE;
            case ROOM_BOOKS -> roomBooksRowCell(entry, hitX, halfW);
            case ROOM_SKY -> CellKind.ROOM_SKY_CYCLE;
            case EXITS -> CellKind.EXITS_CYCLE;
            case EXIT_EVERY -> stepperCell(hitX, halfW,
                CellKind.EXIT_EVERY_DEC, CellKind.EXIT_EVERY_INC, CellKind.EXIT_EVERY_TYPE);
            case EXIT_MOVE -> stepperCell(hitX, halfW,
                CellKind.EXIT_MOVE_DEC, CellKind.EXIT_MOVE_INC, CellKind.EXIT_MOVE_TYPE);
            case ENTER -> CellKind.BUTTON_ENTER_INSIDE;
            case ACTION -> actionRowCell(hitX, halfW);
            case CONTENTS -> CellKind.BUTTON_CONTENTS;
        };
    }

    private static CellKind weightRowCell(double hitX, double halfW) {
        return stepperCell(hitX, halfW, CellKind.WEIGHT_DEC, CellKind.WEIGHT_INC, CellKind.NONE);
    }

    /**
     * Thirds split shared by every {@code [-] N [+]} row: decrement on the left third, increment on
     * the right third, and {@code middle} for the number between them — {@link CellKind#NONE} where
     * the number is display-only.
     */
    private static CellKind stepperCell(double hitX, double halfW, CellKind dec, CellKind inc,
                                        CellKind middle) {
        double third = (halfW * 2.0) / 3.0;
        if (hitX < -halfW + third) return dec;
        if (hitX > halfW - third) return inc;
        return middle;
    }

    /**
     * The Books row is one button while the room stocks nothing, and two once it does — the value on
     * the left, an Edit button on the right for the weights and the author band.
     *
     * <p>Inline rather than a row of its own: the dials belong to the value beside them, and a room
     * that stocks nothing should not grow a row for settings that mean nothing.</p>
     */
    private static CellKind roomBooksRowCell(EditorPlotLabelsPacket.Entry entry, double hitX, double halfW) {
        if (!hasBookEditButton(entry)) return CellKind.ROOM_BOOKS_CYCLE;
        return hitX < booksEditLeft(halfW) ? CellKind.ROOM_BOOKS_CYCLE : CellKind.ROOM_BOOKS_EDIT;
    }

    /** Where the Edit half of the Books row starts. */
    private static double booksEditLeft(double halfW) {
        return -halfW + BOOKS_CYCLE_SHARE * (halfW * 2.0);
    }

    /** True when the Books row carries its Edit button — only a room that stocks an author has dials. */
    public static boolean hasBookEditButton(EditorPlotLabelsPacket.Entry entry) {
        return hasRoomBooksRow(entry)
            && games.brennan.dungeontrain.portal.PortalRoomSettings.parse(entry.roomMode())
                .books().weightsApply();
    }

    private static CellKind actionRowCell(double hitX, double halfW) {
        double w = halfW * 2.0;
        // Save 70%, Reset 15%, Clear 15% — left-to-right.
        double saveR = -halfW + 0.70 * w;
        double resetR = -halfW + 0.85 * w;
        if (hitX < saveR) return CellKind.ACTION_SAVE;
        if (hitX < resetR) return CellKind.ACTION_RESET;
        return CellKind.ACTION_CLEAR;
    }

    // ---------- rendering ----------

    private static void drawPanel(
        PoseStack ps, MultiBufferSource buffer, Font font,
        Vec3 cam, Vec3 anchor,
        EditorPlotLabelsPacket.Entry entry, CellKind hovered
    ) {
        Vec3[] b = basis(anchor, cam);
        Vec3 right = b[0], up = b[1], normal = b[2];

        ps.pushPose();
        ps.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);
        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        ps.mulPose(new Quaternionf().setFromNormalized(basis));

        // Same world-space scale used by every other in-world menu — applied
        // here so the panel shrinks/grows uniformly with the X menu, parts
        // panel, and block-variant menu. Matched on the input side by
        // {@link games.brennan.dungeontrain.client.menu.plot.EditorPlotPanelRaycast}.
        float worldScale = (float) ClientDisplayConfig.getWorldspaceScale();
        if (worldScale != 1.0f) {
            ps.scale(worldScale, worldScale, worldScale);
        }

        double halfW = halfWidth(entry, font);
        double halfH = halfHeight(entry);

        // Whole-panel backdrop.
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, BACKDROP_COLOR);

        // Green border when the player is inside this plot — sits OUTSIDE
        // the backdrop's ±halfW/±halfH rectangle as a frame, so it never
        // overlaps the dark panel fill. Top and bottom span the full
        // border-extended width; left and right fill only the gap between
        // them so the four quads don't double up at the corners.
        if (entry.inPlot()) {
            double t = BORDER_THICKNESS;
            // Provenance priority: imported (orange) > user-saved (blue) > bundled (green).
            // Imported wins because the file is still on the "as you received it"
            // mtime — once the player edits, isImported flips false and the
            // panel naturally falls through to the blue user tint.
            int borderColor;
            if (entry.isImported()) {
                borderColor = BORDER_COLOR_IMPORTED;
            } else if (entry.isUser()) {
                borderColor = BORDER_COLOR_USER;
            } else {
                borderColor = BORDER_COLOR_BUNDLED;
            }
            // top (full width incl. corners)
            drawQuad(ps, buffer, -halfW - t, halfH, halfW + t, halfH + t, borderColor);
            // bottom (full width incl. corners)
            drawQuad(ps, buffer, -halfW - t, -halfH - t, halfW + t, -halfH, borderColor);
            // left (between top + bottom)
            drawQuad(ps, buffer, -halfW - t, -halfH, -halfW, halfH, borderColor);
            // right (between top + bottom)
            drawQuad(ps, buffer, halfW, -halfH, halfW + t, halfH, borderColor);
        }

        // One pass over the shared row list, so what is drawn and what cellAt hit-tests cannot
        // disagree about which row is where.
        double topY = halfH;
        RowKind[] rowKinds = rows(entry);
        for (int rowIdx = 0; rowIdx < rowKinds.length; rowIdx++) {
            RowKind rowKind = rowKinds[rowIdx];
            double rTop = topY - rowIdx * ROW_H;
            double rBot = rTop - ROW_H;
            double rCY = (rTop + rBot) / 2.0;

            switch (rowKind) {
                case NAME -> {
                    // Hover-highlight when the player is aiming at it and the row is
                    // teleport-clickable (i.e. the entry has a category).
                    if (hovered == CellKind.NAME) {
                        drawQuad(ps, buffer, -halfW + 0.005, rBot + 0.005,
                            halfW - 0.005, rTop - 0.005, HOVER_COLOR);
                    }
                    drawCenteredText(ps, buffer, font, entry.name(), 0, rCY, NAME_COLOR);
                }
                // Weight — display always when there's a weight pool; arrows only when inside
                // the plot (the player has to step into the cage to edit).
                case WEIGHT -> {
                    drawQuad(ps, buffer, -halfW, rTop - 0.005, halfW, rTop + 0.005, ROW_SEP_COLOR);
                    if (hasWeightArrows(entry)) {
                        drawStepperArrows(ps, buffer, font, halfW, rTop, rBot, rCY,
                            hovered, CellKind.WEIGHT_DEC, CellKind.WEIGHT_INC);
                    }
                    drawCenteredText(ps, buffer, font, "×" + entry.weight(), 0, rCY, WEIGHT_COLOR);
                }
                // Room size — portal rooms only. Same [-] N [+] geometry as weight.
                case LENGTH, WIDTH, HEIGHT -> {
                    CellKind dec = switch (rowKind) {
                        case LENGTH -> CellKind.LENGTH_DEC;
                        case WIDTH -> CellKind.WIDTH_DEC;
                        default -> CellKind.HEIGHT_DEC;
                    };
                    CellKind inc = switch (rowKind) {
                        case LENGTH -> CellKind.LENGTH_INC;
                        case WIDTH -> CellKind.WIDTH_INC;
                        default -> CellKind.HEIGHT_INC;
                    };
                    drawQuad(ps, buffer, -halfW, rTop - 0.005, halfW, rTop + 0.005, ROW_SEP_COLOR);
                    drawStepperArrows(ps, buffer, font, halfW, rTop, rBot, rCY, hovered, dec, inc);
                    CellKind type = switch (rowKind) {
                        case LENGTH -> CellKind.LENGTH_TYPE;
                        case WIDTH -> CellKind.WIDTH_TYPE;
                        default -> CellKind.HEIGHT_TYPE;
                    };
                    if (hovered == type) {
                        double third = (halfW * 2.0) / 3.0;
                        drawQuad(ps, buffer, -halfW + third + 0.005, rBot + 0.005,
                            halfW - third - 0.005, rTop - 0.005, HOVER_COLOR);
                    }
                    drawCenteredText(ps, buffer, font,
                        dimensionLabel(rowKind) + " " + dimensionValue(entry, rowKind),
                        0, rCY, WEIGHT_COLOR);
                }
                // Mode — full-width button showing what this room does at its walls; clicking steps
                // to the next one. Sits under the dimension rows because it is the same kind of
                // fact: a property of the room's box rather than of the template inside it.
                case MODE -> {
                    drawQuad(ps, buffer, -halfW, rTop - 0.005, halfW, rTop + 0.005, ROW_SEP_COLOR);
                    int bg = hovered == CellKind.MODE_CYCLE ? HOVER_COLOR : BUTTON_BG;
                    drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, halfW - 0.01, rTop - 0.005, bg);
                    drawCenteredText(ps, buffer, font, modeLabel(entry.roomMode()), 0, rCY, WEIGHT_COLOR);
                }
                // Copies — the sub-mode under Walls, present only while the walls repeat the room.
                case COPIES -> {
                    int bg = hovered == CellKind.COPIES_CYCLE ? HOVER_COLOR : BUTTON_BG;
                    drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, halfW - 0.01, rTop - 0.005, bg);
                    drawCenteredText(ps, buffer, font, copiesLabel(entry.roomMode()), 0, rCY, WEIGHT_COLOR);
                }
                // Floor and Roof — the two variants Single repeats, as icons. Clicking a row takes
                // what the author is holding (a block, or a variant copied from a cell); its Edit
                // half opens the Block Variant menu on that plane alone.
                case COPIES_FLOOR -> drawCopiesPlaneRow(ps, buffer, font, halfW, rBot, rTop, rCY,
                    hovered, "Floor:", entry.copiesFloorBlock(),
                    CellKind.COPIES_FLOOR_HELD, CellKind.COPIES_FLOOR_EDIT);
                case COPIES_ROOF -> drawCopiesPlaneRow(ps, buffer, font, halfW, rBot, rTop, rCY,
                    hovered, "Roof:", entry.copiesRoofBlock(),
                    CellKind.COPIES_ROOF_HELD, CellKind.COPIES_ROOF_EDIT);
                // Contents — whether this room is furnished from the contents pool, and how a
                // furnishing smaller than the room is fitted into it. Off by default.
                case ROOM_CONTENTS -> {
                    int bg = hovered == CellKind.ROOM_CONTENTS_CYCLE ? HOVER_COLOR : BUTTON_BG;
                    drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, halfW - 0.01, rTop - 0.005, bg);
                    drawCenteredText(ps, buffer, font, roomContentsLabel(entry.roomMode()), 0, rCY, WEIGHT_COLOR);
                }
                // Books — whether this room stocks its shelves from one author. Off by default, and
                // once it is not, the row carries an Edit button for the weights and the author band.
                case ROOM_BOOKS -> {
                    if (hasBookEditButton(entry)) {
                        double split = booksEditLeft(halfW);
                        int valueBg = hovered == CellKind.ROOM_BOOKS_CYCLE ? HOVER_COLOR : BUTTON_BG;
                        int editBg = hovered == CellKind.ROOM_BOOKS_EDIT ? HOVER_COLOR : BUTTON_BG;
                        drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, split - 0.005, rTop - 0.005, valueBg);
                        drawQuad(ps, buffer, split + 0.005, rBot + 0.005, halfW - 0.01, rTop - 0.005, editBg);
                        drawCenteredText(ps, buffer, font, roomBooksLabel(entry.roomMode()),
                            (-halfW + split) / 2.0, rCY, WEIGHT_COLOR);
                        drawCenteredText(ps, buffer, font, "Edit",
                            (split + halfW) / 2.0, rCY, BUTTON_TEXT_COLOR);
                    } else {
                        int bg = hovered == CellKind.ROOM_BOOKS_CYCLE ? HOVER_COLOR : BUTTON_BG;
                        drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, halfW - 0.01, rTop - 0.005, bg);
                        drawCenteredText(ps, buffer, font, roomBooksLabel(entry.roomMode()), 0, rCY, WEIGHT_COLOR);
                    }
                }
                // Door Wall — whether the copies standing against the portal carriages carry their
                // own end wall through the corridor mouth's plane. Sealed by default, which is what
                // every room did before the setting existed.
                case DOOR_WALL -> {
                    int bg = hovered == CellKind.DOOR_WALL_CYCLE ? HOVER_COLOR : BUTTON_BG;
                    drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, halfW - 0.01, rTop - 0.005, bg);
                    drawCenteredText(ps, buffer, font, doorWallLabel(entry.roomMode()), 0, rCY, WEIGHT_COLOR);
                }
                // Sky — whether this room is lit as though it stood outdoors, and under which sky.
                // Off by default, which is every room lit only by whatever its own build gives it.
                case ROOM_SKY -> {
                    int bg = hovered == CellKind.ROOM_SKY_CYCLE ? HOVER_COLOR : BUTTON_BG;
                    drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, halfW - 0.01, rTop - 0.005, bg);
                    drawCenteredText(ps, buffer, font, roomSkyLabel(entry.roomMode()), 0, rCY, WEIGHT_COLOR);
                }
                // Exits — how many extra ways back to the train this room scatters through its
                // copies. Only an endless room has anywhere to put one.
                case EXITS -> {
                    int bg = hovered == CellKind.EXITS_CYCLE ? HOVER_COLOR : BUTTON_BG;
                    drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, halfW - 0.01, rTop - 0.005, bg);
                    drawCenteredText(ps, buffer, font, exitsLabel(entry.roomMode()), 0, rCY, WEIGHT_COLOR);
                }
                // How far apart those go. Same [-] N [+] geometry as the dimension rows, and the
                // number types on its own for the same reason: stepping 8 → 40 is thirty-two taps.
                case EXIT_EVERY -> {
                    drawStepperArrows(ps, buffer, font, halfW, rTop, rBot, rCY, hovered,
                        CellKind.EXIT_EVERY_DEC, CellKind.EXIT_EVERY_INC);
                    if (hovered == CellKind.EXIT_EVERY_TYPE) {
                        double third = (halfW * 2.0) / 3.0;
                        drawQuad(ps, buffer, -halfW + third + 0.005, rBot + 0.005,
                            halfW - third - 0.005, rTop - 0.005, HOVER_COLOR);
                    }
                    drawCenteredText(ps, buffer, font, exitEveryLabel(entry.roomMode()),
                        0, rCY, WEIGHT_COLOR);
                }
                // How often this room walls off the base pair's exit, so the only ways onward are
                // the copies. Random only — the lattice is predictable enough to walk.
                case EXIT_MOVE -> {
                    drawStepperArrows(ps, buffer, font, halfW, rTop, rBot, rCY, hovered,
                        CellKind.EXIT_MOVE_DEC, CellKind.EXIT_MOVE_INC);
                    if (hovered == CellKind.EXIT_MOVE_TYPE) {
                        double third = (halfW * 2.0) / 3.0;
                        drawQuad(ps, buffer, -halfW + third + 0.005, rBot + 0.005,
                            halfW - third - 0.005, rTop - 0.005, HOVER_COLOR);
                    }
                    drawCenteredText(ps, buffer, font, exitMoveLabel(entry.roomMode()),
                        0, rCY, WEIGHT_COLOR);
                }
                // Enter — full-width button, visible only when the player is already inside the
                // plot. Clicking dispatches EditorPlotActionPacket(ENTER_INSIDE) so the player
                // teleports to the plot floor (the default teleport lands on top).
                case ENTER -> {
                    drawQuad(ps, buffer, -halfW, rTop - 0.005, halfW, rTop + 0.005, ROW_SEP_COLOR);
                    int bg = hovered == CellKind.BUTTON_ENTER_INSIDE ? HOVER_COLOR : BUTTON_BG;
                    drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, halfW - 0.01, rTop - 0.005, bg);
                    drawCenteredText(ps, buffer, font, "Enter", 0, rCY, BUTTON_TEXT_COLOR);
                }
                case ACTION -> drawActionRow(ps, buffer, font, halfW, rTop, rBot, rCY, hovered);
                case CONTENTS -> {
                    int bg = hovered == CellKind.BUTTON_CONTENTS ? HOVER_COLOR : BUTTON_BG;
                    drawQuad(ps, buffer, -halfW, rTop - 0.005, halfW, rTop + 0.005, ROW_SEP_COLOR);
                    drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, halfW - 0.01, rTop - 0.005, bg);
                    drawCenteredText(ps, buffer, font, "Contents", 0, rCY, BUTTON_TEXT_COLOR);
                }
            }
        }

        ps.popPose();
    }

    /** The {@code [-]} / {@code [+]} cells shared by the weight row and every dimension row. */
    private static void drawStepperArrows(
        PoseStack ps, MultiBufferSource buffer, Font font, double halfW,
        double rTop, double rBot, double rCY, CellKind hovered, CellKind dec, CellKind inc
    ) {
        double third = (halfW * 2.0) / 3.0;
        double leftR = -halfW + third;
        double rightL = halfW - third;
        if (hovered == dec) {
            drawQuad(ps, buffer, -halfW + 0.005, rBot + 0.005, leftR - 0.005, rTop - 0.005, HOVER_COLOR);
        } else if (hovered == inc) {
            drawQuad(ps, buffer, rightL + 0.005, rBot + 0.005, halfW - 0.005, rTop - 0.005, HOVER_COLOR);
        }
        drawCenteredText(ps, buffer, font, "-", (-halfW + leftR) / 2.0, rCY, ARROW_COLOR);
        drawCenteredText(ps, buffer, font, "+", (rightL + halfW) / 2.0, rCY, ARROW_COLOR);
    }

    /** The {@code [Save 70%][R 15%][C 15%]} row. */
    private static void drawActionRow(
        PoseStack ps, MultiBufferSource buffer, Font font, double halfW,
        double aTop, double aBot, double aCY, CellKind hovered
    ) {
        {
            double w = halfW * 2.0;
            double saveR = -halfW + 0.70 * w;
            double resetR = -halfW + 0.85 * w;
            double saveCX = (-halfW + saveR) / 2.0;
            double resetCX = (saveR + resetR) / 2.0;
            double clearCX = (resetR + halfW) / 2.0;

            // Row separator (top edge).
            drawQuad(ps, buffer, -halfW, aTop - 0.005, halfW, aTop + 0.005, ROW_SEP_COLOR);

            // Faint button backgrounds so they look pressable, brighten on hover.
            int saveBg = hovered == CellKind.ACTION_SAVE ? HOVER_COLOR : BUTTON_BG;
            int resetBg = hovered == CellKind.ACTION_RESET ? HOVER_COLOR : BUTTON_BG;
            int clearBg = hovered == CellKind.ACTION_CLEAR ? HOVER_COLOR : BUTTON_BG;
            drawQuad(ps, buffer, -halfW + 0.01, aBot + 0.005, saveR - 0.005, aTop - 0.005, saveBg);
            drawQuad(ps, buffer, saveR + 0.005, aBot + 0.005, resetR - 0.005, aTop - 0.005, resetBg);
            drawQuad(ps, buffer, resetR + 0.005, aBot + 0.005, halfW - 0.01, aTop - 0.005, clearBg);

            drawCenteredText(ps, buffer, font, "Save", saveCX, aCY, BUTTON_TEXT_COLOR);
            drawCenteredText(ps, buffer, font, "R", resetCX, aCY, BUTTON_TEXT_COLOR);
            drawCenteredText(ps, buffer, font, "C", clearCX, aCY, BUTTON_TEXT_COLOR);
        }
    }

    /**
     * One Copies plane row: its label and icon on the left, an Edit button on the right.
     *
     * <p>Floor and Roof are the same row bar their label, their icon and which two cells they
     * report — written once so the two cannot drift apart in geometry, which is what the hit-test
     * assumes when it splits both of them at {@link #copiesEditLeft}.</p>
     */
    private static void drawCopiesPlaneRow(
        PoseStack ps, MultiBufferSource buffer, Font font,
        double halfW, double rBot, double rTop, double rCY,
        CellKind hovered, String label, String block,
        CellKind heldCell, CellKind editCell
    ) {
        double split = copiesEditLeft(halfW);
        int valueBg = hovered == heldCell ? HOVER_COLOR : BUTTON_BG;
        drawQuad(ps, buffer, -halfW + 0.01, rBot + 0.005, split - 0.005, rTop - 0.005, valueBg);
        drawLeftText(ps, buffer, font, label, -halfW + PAD_X, rCY, WEIGHT_COLOR);

        if (block.isEmpty()) {
            // Nothing set yet: say what to do rather than showing an empty slot.
            drawLeftText(ps, buffer, font, "hold one", copiesIconCentre(halfW) - PAD_X,
                rCY, LABEL_COLOR);
        } else if (isAirSentinelId(block)) {
            // The plane is authored as air. Drawing the sentinel's own icon would put a command
            // block in the author's roof row, which is a block they never chose; the Block Variant
            // menu calls this entry "nothing" and so does this row.
            drawLeftText(ps, buffer, font, "nothing", copiesIconCentre(halfW) - PAD_X,
                rCY, WEIGHT_COLOR);
        } else {
            MenuBlockIcons.drawBlockIcon(ps, buffer, block, copiesIconCentre(halfW),
                rCY, COPIES_ICON_SIZE);
        }

        int editBg = hovered == editCell ? HOVER_COLOR : BUTTON_BG;
        drawQuad(ps, buffer, split + 0.005, rBot + 0.005, halfW - 0.01, rTop - 0.005, editBg);
        drawCenteredText(ps, buffer, font, "Edit", (split + halfW) / 2.0, rCY, BUTTON_TEXT_COLOR);
    }

    /** {@link #drawCenteredText} anchored at its left edge — what a row with a strip beside it needs. */
    private static void drawLeftText(
        PoseStack ps, MultiBufferSource buffer, Font font,
        String text, double worldX, double worldY, int colour
    ) {
        ps.pushPose();
        ps.translate(worldX, worldY, 0.001f);
        float scale = (float) TEXT_SCALE;
        ps.scale(scale, -scale, scale);
        float y = -font.lineHeight / 2f;
        Matrix4f mat = ps.last().pose();
        font.drawInBatch(text, 0, y, colour, false, mat, buffer,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    private static void drawCenteredText(
        PoseStack ps, MultiBufferSource buffer, Font font,
        String text, double worldX, double worldY, int colour
    ) {
        ps.pushPose();
        ps.translate(worldX, worldY, 0.001f);
        float scale = (float) TEXT_SCALE;
        ps.scale(scale, -scale, scale);
        int textWidth = font.width(text);
        float x = -textWidth / 2f;
        float y = -font.lineHeight / 2f;
        Matrix4f mat = ps.last().pose();
        font.drawInBatch(text, x, y, colour, false, mat, buffer,
            Font.DisplayMode.SEE_THROUGH, 0, LightTexture.FULL_BRIGHT);
        ps.popPose();
    }

    private static void drawQuad(
        PoseStack ps, MultiBufferSource buffer,
        double x1, double y1, double x2, double y2, int argb
    ) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        VertexConsumer vc = buffer.getBuffer(PANEL_QUAD);
        Matrix4f mat = ps.last().pose();
        vc.addVertex(mat, (float) x1, (float) y1, (float) 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y1, (float) 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x2, (float) y2, (float) 0).setColor(r, g, b, a);
        vc.addVertex(mat, (float) x1, (float) y2, (float) 0).setColor(r, g, b, a);
    }
}
