package games.brennan.dungeontrain.client.menu.blockvariant;

import games.brennan.dungeontrain.config.ClientDisplayConfig;
import games.brennan.dungeontrain.config.EditorMenuSpace;
import games.brennan.dungeontrain.editor.VariantCopyRoll;
import games.brennan.dungeontrain.editor.VariantCopyScope;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client singleton state for the block-variant world-space menu. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.parts.PartPositionMenu}.
 *
 * <p>Driven by {@link BlockVariantSyncPacket} from the server: the server
 * pushes a sync when the player taps Z on a flagged block, and pushes
 * empty (close) when the player toggles off or the cell vanishes.</p>
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@link Screen#ROOT} — entries grid (state-name + weight + lock)
 *       with toolbar Copy / Add / Remove / Clear / X.</li>
 *   <li>{@link Screen#ADD_SEARCH} — typing buffer + filtered list of
 *       block IDs. Click a result → server appends.</li>
 * </ul>
 * Remove-mode is a flag on the ROOT screen (mirrors part menu) — when on,
 * each row gets a red X cell that confirms removal.</p>
 *
 * <p>The search registry is built locally from
 * {@link BuiltInRegistries#BLOCK} since vanilla guarantees identical
 * registries on both sides — no need to ship the list over the wire.</p>
 */
public final class BlockVariantMenu {

    /** Maximum rows per column before the grid wraps to a new column. */
    public static final int ROWS_PER_COLUMN = 10;

    public enum Screen { ROOT, ADD_SEARCH }

    public enum CellKind {
        NONE,
        COPY,
        SAVE,
        ADD,
        REMOVE,
        CLEAR,
        LOCK,
        /** Toolbar Rot pill — Rot Lock / Rot Free for the cell's lock group. Only on a grouped cell. */
        ROT_LOCK,
        COPY_ROLL,
        COPY_SCOPE,
        SPAN,
        SPAN_OPTION,
        CLOSE,
        ENTRY_NAME,
        ENTRY_WEIGHT,
        ENTRY_REMOVE_X,
        ENTRY_ROT_MODE,
        ENTRY_ROT_DIRS,
        ENTRY_HALF_MODE,
        ENTRY_ACTIVE_MODE,
        /** Villager row's J / R / N job-mode pill, in the Grow slot. */
        ENTRY_VILLAGER_MODE,
        /** The four fence / wall / pane connect-mode segments, in {@code VariantConnect.Mode} order. */
        ENTRY_CONNECT_DEFAULT,
        ENTRY_CONNECT_AUTO,
        ENTRY_CONNECT_LOCK,
        /** The four locked-arm toggles of a Lock row, in N / E / S / W order. */
        ENTRY_ARM_NORTH,
        ENTRY_ARM_EAST,
        ENTRY_ARM_SOUTH,
        ENTRY_ARM_WEST,
        /** The row's Grow pill (column-forming blocks) — opens the Grow popup for that row. */
        ENTRY_GROWTH,
        /** A button in the Grow popup; {@code secondary} = {@code GrowthPopupLayout.encode}. */
        GROWTH_OPTION,
        /**
         * A button in a painting row's Painting popup (opened from the Grow pill slot, which a
         * painting row uses instead); {@code secondary} = {@code GrowthPopupLayout.encode}.
         */
        PAINTING_OPTION,
        /** A button in a candle row's Candles popup (Grow pill slot); {@code secondary} = {@code GrowthPopupLayout.encode}. */
        CANDLE_OPTION,
        /** A button in a flower pot row's Plant popup (Grow pill slot); {@code secondary} = {@code GrowthPopupLayout.encode}. */
        POT_OPTION,
        /** A button in a respawn anchor row's Charge popup (Grow pill slot); {@code secondary} = {@code GrowthPopupLayout.encode}. */
        CHARGE_OPTION,
        /** A swatch in a white bed row's Colours popup (Grow pill slot); {@code secondary} = {@code GrowthPopupLayout.encode}. */
        BED_COLOUR_OPTION,
        /** A button in a slime-type mob row's Slime popup (Grow pill slot); {@code secondary} = {@code GrowthPopupLayout.encode}. */
        SLIME_OPTION,
        /** A button in a sign row's Sign popup (Grow pill slot); {@code secondary} = {@code GrowthPopupLayout.encode}. */
        SIGN_OPTION,
        ENTRY_DIFF_MIN,
        ENTRY_DIFF_MAX,
        /** The row's If pill — opens the condition popup for that row. */
        ENTRY_COND,
        /** A button in the If popup; {@code secondary} = {@code GrowthPopupLayout.encode}. */
        COND_OPTION,
        ROT_DIR_OPTION,
        SEARCH_FIELD,
        SEARCH_RESULT,
        SEARCH_BACK
    }

    /**
     * A specific cell hit by the raycast. {@code secondary} carries the
     * direction ordinal for {@link CellKind#ROT_DIR_OPTION} hits in the
     * OPTIONS-mode floating popup; {@code -1} for all other cells.
     */
    public record Hit(CellKind kind, int index, int secondary) {
        public static final Hit NONE = new Hit(CellKind.NONE, -1, -1);

        public Hit(CellKind kind, int index) {
            this(kind, index, -1);
        }
    }

    private BlockVariantMenu() {}

    /**
     * One panel's state. There are two: {@link #MAIN}, the cell's own menu, and {@link #CHILD}, the
     * group panel stacked above it (right-click a "+ Group N" row). Every static getter below reads
     * {@link #current}, so the renderer, raycast and click dispatch act on whichever panel they were
     * pointed at with {@link #withPanel}; outside that it is always {@link #MAIN}.
     */
    static final class Panel {
        boolean active;
        String variantId = "";
        @Nullable BlockPos localPos;
        List<BlockVariantSyncPacket.Entry> entries = Collections.emptyList();
        int lockId = 0;
        /** The cell's lock group rolls rotation per cell (Rot Free); false = one shared rotation. */
        boolean rotationFree;
        VariantCopyRoll copyRoll = VariantCopyRoll.DEFAULT;
        boolean copySettingsSupported;
        /** Whether this plot's spawn path honours the fence / wall connect mode — gates the row pill. */
        boolean connectSupported;
        /** Whether this plot's spawn path grows columns — gates the row's Grow pill. */
        boolean growthSupported;
        /** -1 = closed; otherwise the row whose Grow popup is open. */
        int growthPopupRow = -1;
        /** -1 = closed; otherwise the row whose If (condition) popup is open. */
        int condPopupRow = -1;
        VariantCopyScope copyScope = VariantCopyScope.BOTH;
        games.brennan.dungeontrain.editor.VariantSpan spanMode =
            games.brennan.dungeontrain.editor.VariantSpan.NONE;
        /** True while the Span button's option strip is open. */
        boolean spanPopupOpen;
        Vec3 anchorPos = Vec3.ZERO;
        Vec3 anchorRight = new Vec3(1, 0, 0);
        Vec3 anchorUp = new Vec3(0, 1, 0);
        Vec3 anchorNormal = new Vec3(0, 0, 1);
        Screen screen = Screen.ROOT;
        boolean removeMode;
        String searchBuffer = "";
        Hit hovered = Hit.NONE;
        /** -1 = closed; otherwise the row whose OPTIONS popup is open. */
        int rotPopupRowIndex = -1;

        /** Back to closed, with every sub-screen and popup reset so the next open lands on ROOT. */
        void reset() {
            active = false;
            variantId = "";
            localPos = null;
            entries = Collections.emptyList();
            lockId = 0;
            rotationFree = false;
            copyRoll = VariantCopyRoll.DEFAULT;
            copySettingsSupported = false;
            connectSupported = false;
            growthSupported = false;
            growthPopupRow = -1;
            condPopupRow = -1;
            copyScope = VariantCopyScope.BOTH;
            spanMode = games.brennan.dungeontrain.editor.VariantSpan.NONE;
            spanPopupOpen = false;
            screen = Screen.ROOT;
            removeMode = false;
            searchBuffer = "";
            hovered = Hit.NONE;
            rotPopupRowIndex = -1;
        }
    }

    /** The cell's own menu. */
    static final Panel MAIN = new Panel();
    /** The group panel stacked above {@link #MAIN}; inactive unless a group row was right-clicked. */
    static final Panel CHILD = new Panel();
    /** The panel every static getter reads. {@link #MAIN} except inside {@link #withPanel}. */
    private static Panel current = MAIN;
    /** The panel under the crosshair, whose {@link Panel#hovered} a click acts on. */
    private static Panel hoverPanel = MAIN;

    /**
     * Where this opening of the menu draws, latched when the server activates it rather than
     * read live. The two modes tear down differently — a Screen to pop versus an event
     * subscriber to stop drawing — so the menu must close in the mode it opened in.
     */
    private static EditorMenuSpace space = ClientDisplayConfig.DEFAULT_BLOCK_VARIANT_MENU_SPACE;

    /** Cached registry list — built lazily on first ADD_SEARCH entry. */
    @Nullable private static List<String> cachedBlockIds;

    /**
     * Cache of parsed {@link BlockState}s keyed by their {@code stateString}
     * — the renderer / raycaster need to call
     * {@link games.brennan.dungeontrain.editor.RotationApplier#canRotate}
     * per row each frame, and reparsing the same handful of strings every
     * frame is wasteful. Cleared whenever the menu sync changes the
     * targeted cell.
     */
    private static final Map<String, BlockState> PARSED_STATE_CACHE = new HashMap<>();

    public static boolean isActive() { return current.active; }

    /** Where this opening of the menu draws. See {@link #space}. */
    public static EditorMenuSpace space() { return space; }

    /**
     * Active <em>and</em> drawing in the world — the guard the world-space renderer and input
     * handler use. In screen-space {@link BlockVariantMenuScreen} owns rendering and input, and
     * both paths running would double-draw and double-hit-test.
     */
    public static boolean isActiveWorldspace() { return current.active && space.isWorldspace(); }
    public static String variantId() { return current.variantId; }
    @Nullable public static BlockPos localPos() { return current.localPos; }
    public static List<BlockVariantSyncPacket.Entry> entries() { return current.entries; }
    public static int lockId() { return current.lockId; }
    /** True when the cell's lock group rolls rotation per cell (Rot Free). */
    public static boolean rotationFree() { return current.rotationFree; }

    /** How this cell rolls across the copies of the repeating room it belongs to. */
    public static VariantCopyRoll copyRoll() { return current.copyRoll; }

    /** True when this plot's template repeats at all — only then are the two copy cells drawn. */
    public static boolean copySettingsSupported() { return current.copySettingsSupported; }
    public static boolean connectSupported() { return current.connectSupported; }
    public static boolean growthSupported() { return current.growthSupported; }

    /** The row whose Grow popup is open, or -1 — also -1 once that row is gone. */
    public static int growthPopupRow() {
        return current.growthPopupRow >= 0 && current.growthPopupRow < current.entries.size() ? current.growthPopupRow : -1;
    }
    public static void toggleGrowthPopup(int row) { current.growthPopupRow = current.growthPopupRow == row ? -1 : row; }
    public static void closeGrowthPopup() { current.growthPopupRow = -1; }

    /** The row whose If popup is open, or -1 — also -1 once that row is gone. */
    public static int condPopupRow() {
        return current.condPopupRow >= 0 && current.condPopupRow < current.entries.size() ? current.condPopupRow : -1;
    }
    public static void toggleCondPopup(int row) { current.condPopupRow = current.condPopupRow == row ? -1 : row; }
    public static void closeCondPopup() { current.condPopupRow = -1; }

    /**
     * True when {@code entry} is a painting row — it shows the Painting pill and popup in the Grow
     * slot. Mob and group-reference rows never do.
     */
    public static boolean isPaintingRow(BlockVariantSyncPacket.Entry entry, @Nullable BlockState parsed) {
        return parsed != null && !entry.isMob() && !entry.isGroupRef()
            && games.brennan.dungeontrain.editor.PaintingPass.isPainting(parsed);
    }

    /** True when {@code entry} is a candle row — it shows the Candles pill and popup in the Grow slot. */
    public static boolean isCandleRow(BlockVariantSyncPacket.Entry entry, @Nullable BlockState parsed) {
        return entry.entityId() == null && entry.groupRef() <= 0
            && games.brennan.dungeontrain.editor.VariantCandles.isCandle(parsed);
    }

    /** True when {@code entry} is a sign row — it shows the Sign pill and popup in the Grow slot. */
    public static boolean isSignRow(BlockVariantSyncPacket.Entry entry, @Nullable BlockState parsed) {
        return entry.entityId() == null && entry.groupRef() <= 0
            && games.brennan.dungeontrain.editor.SignVariants.isSign(parsed);
    }

    private static String signTextKey;
    private static games.brennan.dungeontrain.editor.SignVariants.Text signTextValue;

    /** The row's sign text, read from its block-entity NBT (blank when it has none). */
    public static games.brennan.dungeontrain.editor.SignVariants.Text signText(BlockVariantSyncPacket.Entry entry) {
        String key = entry.beNbt();
        if (key != null && key.equals(signTextKey)) return signTextValue;
        net.minecraft.nbt.CompoundTag tag = null;
        if (key != null && !key.isEmpty()) {
            try {
                tag = net.minecraft.nbt.TagParser.parseTag(key);
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
                // Unreadable NBT reads as a blank sign; the row keeps it until the author edits.
            }
        }
        games.brennan.dungeontrain.editor.SignVariants.Text text = games.brennan.dungeontrain.editor.SignVariants.text(tag);
        signTextKey = key;
        signTextValue = text;
        return text;
    }

    /** True when {@code entry} is a flower pot row — it shows the Plant pill and popup in the Grow slot. */
    public static boolean isPotRow(BlockVariantSyncPacket.Entry entry, @Nullable BlockState parsed) {
        return entry.entityId() == null && entry.groupRef() <= 0
            && games.brennan.dungeontrain.editor.FlowerPots.isPot(parsed);
    }

    /**
     * True when {@code entry} is a respawn anchor row (Charge) or a Respawn Obelisk row (Gems) — it shows
     * that pill and popup in the Grow slot.
     */
    public static boolean isAnchorRow(BlockVariantSyncPacket.Entry entry, @Nullable BlockState parsed) {
        return entry.entityId() == null && entry.groupRef() <= 0
            && games.brennan.dungeontrain.editor.VariantCharges.isChargeable(parsed);
    }

    /** True when {@code entry} is a white bed row — it shows the Colours pill and popup in the Grow slot. */
    public static boolean isWhiteBedRow(BlockVariantSyncPacket.Entry entry, @Nullable BlockState parsed) {
        return entry.entityId() == null && entry.groupRef() <= 0
            && games.brennan.dungeontrain.editor.VariantBedColours.isWhiteBed(parsed);
    }

    /**
     * True when {@code entry} is a slime, magma cube or End slime mob row — it shows the Size pill
     * and Slime popup in the Grow slot (a mob row has no Grow).
     */
    public static boolean isSlimeRow(BlockVariantSyncPacket.Entry entry) {
        return entry.groupRef() <= 0 && games.brennan.dungeontrain.editor.VariantSlime.isSlime(entry.entityId());
    }

    /** True when {@code entry} is a villager mob row — it shows the J / R / N pill in the Grow slot. */
    public static boolean isVillagerRow(BlockVariantSyncPacket.Entry entry) {
        return entry.groupRef() <= 0 && games.brennan.dungeontrain.editor.VariantVillager.isVillager(entry.entityId());
    }

    /** The row's slime options (any size / type on every other row). */
    public static games.brennan.dungeontrain.editor.VariantSlime slime(BlockVariantSyncPacket.Entry entry) {
        return new games.brennan.dungeontrain.editor.VariantSlime(entry.slime());
    }

    /** The row's respawn anchor charge option (as placed on every other row). */
    public static games.brennan.dungeontrain.editor.VariantCharges charges(BlockVariantSyncPacket.Entry entry) {
        return games.brennan.dungeontrain.editor.VariantCharges.fromInt(entry.charges());
    }

    /** The row's white bed colours (white only on every other row). */
    public static games.brennan.dungeontrain.editor.VariantBedColours bedColours(BlockVariantSyncPacket.Entry entry) {
        return new games.brennan.dungeontrain.editor.VariantBedColours(entry.bedColours());
    }

    /**
     * True when {@code entry} is a plain chest row: it shows the Trap pill in the Grow slot, a number
     * cell for the trapped-chest chance (no popup).
     */
    public static boolean isTrapRow(BlockVariantSyncPacket.Entry entry, @Nullable BlockState parsed) {
        return entry.entityId() == null && entry.groupRef() <= 0 && parsed != null
            && games.brennan.dungeontrain.editor.TrappedChestRoll.isTrappable(parsed);
    }

    /** The row's candle count option, or as placed when the wire token is blank / malformed. */
    public static games.brennan.dungeontrain.editor.VariantCandles candles(BlockVariantSyncPacket.Entry entry) {
        games.brennan.dungeontrain.editor.VariantCandles c =
            games.brennan.dungeontrain.editor.VariantCandles.parse(entry.candles());
        return c == null ? games.brennan.dungeontrain.editor.VariantCandles.AS_PLACED : c;
    }

    /** The row's flower pot option, or off when the wire token is blank / malformed. */
    public static games.brennan.dungeontrain.editor.VariantPot pot(BlockVariantSyncPacket.Entry entry) {
        games.brennan.dungeontrain.editor.VariantPot p =
            games.brennan.dungeontrain.editor.VariantPot.parse(entry.pot());
        return p == null ? games.brennan.dungeontrain.editor.VariantPot.NONE : p;
    }

    /**
     * The popup the Grow pill slot opens for a row that uses the slot for something else than
     * Grow (painting, candles, flower pot, respawn anchor, white bed), or {@code null} for a growable row's own Grow popup.
     */
    @Nullable
    static SlotPopup slotPopup(BlockVariantSyncPacket.Entry entry, @Nullable BlockState parsed,
                               double panelW, double halfH) {
        if (isSlimeRow(entry)) {
            return new SlotPopup(CellKind.SLIME_OPTION, SlimePopupLayout.rows(
                games.brennan.dungeontrain.editor.VariantSlime.isEndSlime(entry.entityId()), panelW, halfH));
        }
        if (isPaintingRow(entry, parsed)) {
            return new SlotPopup(CellKind.PAINTING_OPTION, PaintingPopupLayout.rows(painting(entry), panelW, halfH));
        }
        if (isCandleRow(entry, parsed)) {
            return new SlotPopup(CellKind.CANDLE_OPTION, CandlePopupLayout.rows(candles(entry), panelW, halfH));
        }
        if (isPotRow(entry, parsed)) {
            return new SlotPopup(CellKind.POT_OPTION, PotPopupLayout.rows(pot(entry), panelW, halfH));
        }
        if (isSignRow(entry, parsed)) {
            return new SlotPopup(CellKind.SIGN_OPTION, SignPopupLayout.rows(parsed, entry.signOptions(),
                signText(entry).waxed(), panelW, halfH));
        }
        if (isAnchorRow(entry, parsed)) {
            return new SlotPopup(CellKind.CHARGE_OPTION, ChargePopupLayout.rows(charges(entry),
                games.brennan.dungeontrain.editor.VariantCharges.maxFor(parsed), panelW, halfH));
        }
        if (isWhiteBedRow(entry, parsed)) {
            return new SlotPopup(CellKind.BED_COLOUR_OPTION, BedColourPopupLayout.rows(panelW, halfH));
        }
        return null;
    }

    /** A Grow-slot popup's rows and the cell kind its buttons report. */
    record SlotPopup(CellKind kind, List<GrowthPopupLayout.Row> rows) {}

    /** True when the row shows an option pill in the Grow slot other than Grow itself. */
    public static boolean usesGrowSlot(BlockVariantSyncPacket.Entry entry, @Nullable BlockState parsed) {
        return isPaintingRow(entry, parsed) || isCandleRow(entry, parsed) || isPotRow(entry, parsed)
            || isSignRow(entry, parsed)
            || isAnchorRow(entry, parsed) || isWhiteBedRow(entry, parsed) || isTrapRow(entry, parsed);
    }

    /** The row's painting option, or off when the wire token is blank / malformed. */
    public static games.brennan.dungeontrain.editor.VariantPainting painting(BlockVariantSyncPacket.Entry entry) {
        games.brennan.dungeontrain.editor.VariantPainting p =
            games.brennan.dungeontrain.editor.VariantPainting.parse(entry.painting());
        return p == null ? games.brennan.dungeontrain.editor.VariantPainting.NONE : p;
    }

    /** The placeable pictures as the client's synced registry has them (empty with no level). */
    public static List<games.brennan.dungeontrain.compat.PaintingCatalog.Picture> pictures() {
        var level = Minecraft.getInstance().level;
        return level == null ? List.of()
            : games.brennan.dungeontrain.compat.PaintingCatalog.placeable(level.registryAccess());
    }

    /** The connect-mode segment kinds, indexed by {@code VariantConnect.Mode} ordinal. */
    public static final CellKind[] CONNECT_SEGMENTS = {
        CellKind.ENTRY_CONNECT_DEFAULT, CellKind.ENTRY_CONNECT_AUTO, CellKind.ENTRY_CONNECT_LOCK};

    /** The locked-arm toggle kinds, index-aligned with {@code VariantConnect.ARM_BITS} (N, E, S, W). */
    public static final CellKind[] ARM_SEGMENTS = {
        CellKind.ENTRY_ARM_NORTH, CellKind.ENTRY_ARM_EAST, CellKind.ENTRY_ARM_SOUTH, CellKind.ENTRY_ARM_WEST};

    /** Which tiles of a repeating room this cell applies in. */
    public static VariantCopyScope copyScope() { return current.copyScope; }

    /**
     * The toolbar's cells, left to right — the one list the renderer draws and the raycaster
     * hit-tests. They were two hard-coded {@code switch (i)} ladders that had to be kept in step by
     * hand; a cell that appears only on some plots is exactly the change that would have made them
     * drift.
     */
    public static List<CellKind> toolbarCells() {
        List<CellKind> cells = new java.util.ArrayList<>(List.of(
            CellKind.COPY, CellKind.SAVE, CellKind.ADD, CellKind.LOCK));
        // Right after Lock because it is the group's own setting: only a grouped cell has a
        // rotation to share or free — an ungrouped one already rolls on its own.
        if (current.lockId > 0) cells.add(CellKind.ROT_LOCK);
        // Beside Lock, which is the other per-cell setting on this toolbar, and next to each
        // other because the two answer one question between them: where the cell is, and how it
        // rolls once it is there.
        if (current.copySettingsSupported) {
            cells.add(CellKind.COPY_ROLL);
            cells.add(CellKind.COPY_SCOPE);
        }
        // Another per-cell setting, so it joins them — only on a cell holding a door / bed /
        // tall plant, the only cells it means anything for.
        if (spanButtonShown()) cells.add(CellKind.SPAN);
        cells.add(CellKind.REMOVE);
        cells.add(CellKind.CLEAR);
        cells.add(CellKind.CLOSE);
        return List.copyOf(cells);
    }

    /** True when the cell holds a door / bed / tall plant — the only cells the Span setting applies to. */
    public static boolean spanButtonShown() {
        // The first multi row sets the footprint; a tall plant's has nothing to choose (single
        // blocks always take the bottom space — MultiBlockFootprint#cellIsTallPlant).
        for (BlockVariantSyncPacket.Entry e : current.entries) {
            if (isMultiRow(e)) {
                return !games.brennan.dungeontrain.editor.MultiBlockFootprint.isTallPlant(parseState(e.stateString()));
            }
        }
        return false;
    }

    /** The cell's span with {@code AUTO} resolved against its first row, the way spawn resolves it. */
    public static games.brennan.dungeontrain.editor.VariantSpan resolvedSpan() {
        boolean firstIsMulti = !current.entries.isEmpty() && isMultiRow(current.entries.get(0));
        return current.spanMode.resolve(firstIsMulti);
    }

    /** True when the author has picked a span, rather than it following the first row. */
    public static boolean spanExplicit() { return !current.spanMode.isDefault(); }

    public static boolean spanPopupOpen() { return current.spanPopupOpen && spanButtonShown(); }
    public static void toggleSpanPopup() { current.spanPopupOpen = !current.spanPopupOpen; }
    public static void closeSpanPopup() { current.spanPopupOpen = false; }

    private static boolean isMultiRow(BlockVariantSyncPacket.Entry e) {
        if (e.isMob() || e.isGroupRef()) return false;
        return games.brennan.dungeontrain.editor.MultiBlockFootprint.isMultiSpace(parseState(e.stateString()));
    }
    public static Vec3 anchorPos() { return current.anchorPos; }
    public static Vec3 anchorRight() { return current.anchorRight; }
    public static Vec3 anchorUp() { return current.anchorUp; }
    public static Vec3 anchorNormal() { return current.anchorNormal; }
    public static Screen screen() { return current.screen; }
    public static boolean removeMode() { return current.removeMode; }
    public static String searchBuffer() { return current.searchBuffer; }
    public static Hit hovered() { return current.hovered; }
    public static int rotPopupRowIndex() { return current.rotPopupRowIndex; }

    public static void setHovered(Hit h) {
        current.hovered = h == null ? Hit.NONE : h;
    }

    public static void openRotPopup(int rowIndex) {
        current.rotPopupRowIndex = rowIndex;
    }

    public static void closeRotPopup() {
        current.rotPopupRowIndex = -1;
    }

    /**
     * Parse and cache a candidate's {@code BlockState}. Returns {@code null}
     * on parse failure (rare — server sent us a string it had already
     * serialised, so failures only happen if registries diverge).
     */
    @Nullable
    public static BlockState parseState(String stateString) {
        if (stateString == null || stateString.isEmpty()) return null;
        BlockState cached = PARSED_STATE_CACHE.get(stateString);
        if (cached != null) return cached;
        try {
            HolderLookup.RegistryLookup<Block> blocks = BuiltInRegistries.BLOCK.asLookup();
            BlockStateParser.BlockResult parsed = BlockStateParser.parseForBlock(blocks, stateString, false);
            PARSED_STATE_CACHE.put(stateString, parsed.blockState());
            return parsed.blockState();
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Apply a server-pushed sync packet. {@code localPos == null} closes
     * the menu and resets sub-screen state so the next open lands on ROOT.
     */
    public static void applySync(BlockVariantSyncPacket packet) {
        if (packet.isChild()) {
            applyChildSync(packet);
            return;
        }
        if (packet.localPos() == null) {
            dismissScreen();
            MAIN.reset();
            // The stacked group panel belongs to this opening; it goes with it.
            CHILD.reset();
            hoverPanel = MAIN;
            return;
        }
        boolean newCell = !packet.variantId().equals(MAIN.variantId)
            || !packet.localPos().equals(MAIN.localPos);
        if (newCell) {
            CHILD.reset();
            hoverPanel = MAIN;
        }
        boolean wasActive = MAIN.active;
        withPanel(MAIN, () -> fill(packet, newCell));
        if (!wasActive) {
            space = ClientDisplayConfig.getBlockVariantMenuSpace();
        }
        // Re-pushed after every edit so the rows stay live, so only the first sync of an opening
        // puts the screen up; the ones after it just refresh what it is drawing.
        if (!wasActive && space.isScreenspace()) {
            Minecraft.getInstance().setScreen(new BlockVariantMenuScreen());
        }
    }

    /**
     * The stacked group panel's sync. Only while the main panel is open: a child left over from a
     * closed menu would draw with nothing under it. Its anchor is ignored and laid out on the client
     * above the main panel — see {@link #layoutChild}.
     */
    private static void applyChildSync(BlockVariantSyncPacket packet) {
        if (packet.localPos() == null || !MAIN.active) {
            CHILD.reset();
            hoverPanel = MAIN;
            return;
        }
        boolean newCell = !packet.variantId().equals(CHILD.variantId)
            || !packet.localPos().equals(CHILD.localPos);
        withPanel(CHILD, () -> fill(packet, newCell));
    }

    /** Copy {@code packet} into {@link #current}, resetting its sub-screens when it is a different cell. */
    private static void fill(BlockVariantSyncPacket packet, boolean newCell) {
        current.active = true;
        current.variantId = packet.variantId();
        current.localPos = packet.localPos();
        current.entries = List.copyOf(packet.entries());
        current.lockId = packet.lockId();
        current.rotationFree = packet.rotationFree();
        current.copyRoll = VariantCopyRoll.fromOrdinal(packet.copyRoll());
        current.copySettingsSupported = packet.copySettingsSupported();
        current.connectSupported = packet.connectSupported();
        current.growthSupported = packet.growthSupported();
        current.copyScope = VariantCopyScope.fromOrdinal(packet.copyScope());
        current.spanMode = games.brennan.dungeontrain.editor.VariantSpan.fromByte(packet.spanMode());
        current.anchorPos = packet.anchorPos();
        current.anchorRight = packet.anchorRight();
        current.anchorUp = packet.anchorUp();
        current.anchorNormal = current.anchorRight.cross(current.anchorUp).normalize();
        if (newCell) {
            current.screen = Screen.ROOT;
            current.removeMode = false;
            current.searchBuffer = "";
            current.rotPopupRowIndex = -1;
            current.spanPopupOpen = false;
            current.growthPopupRow = -1;
            current.condPopupRow = -1;
            // Drop the parse cache so old cells' states don't accumulate
            // (cache is cheap to rebuild — at most ~32 entries per cell).
            PARSED_STATE_CACHE.clear();
        } else if (current.rotPopupRowIndex >= 0 && current.rotPopupRowIndex >= current.entries.size()) {
            // Entry was removed underneath the popup — close it.
            current.rotPopupRowIndex = -1;
        }
        current.hovered = Hit.NONE;
    }

    /** Run {@code body} with every static getter and setter pointed at {@code panel}. */
    public static void withPanel(Panel panel, Runnable body) {
        Panel previous = current;
        current = panel;
        try {
            body.run();
        } finally {
            current = previous;
        }
    }

    /** {@link #withPanel(Panel, Runnable)} returning a value. */
    public static <T> T fromPanel(Panel panel, java.util.function.Supplier<T> body) {
        Panel previous = current;
        current = panel;
        try {
            return body.get();
        } finally {
            current = previous;
        }
    }

    /** The open panels, top first: the stacked group panel (when open), then the main one. */
    public static List<Panel> openPanels() {
        if (!MAIN.active) return List.of();
        return CHILD.active ? List.of(CHILD, MAIN) : List.of(MAIN);
    }

    /** True when the stacked group panel is open. */
    public static boolean childOpen() {
        return MAIN.active && CHILD.active;
    }

    /** The lock group the stacked panel shows, or 0 when it is closed. */
    public static int childLockId() {
        return childOpen() ? CHILD.lockId : 0;
    }

    /** True while {@link #current} is the stacked group panel. */
    public static boolean isChildPanel() {
        return current == CHILD;
    }

    /** The panel under the crosshair — the one a click is dispatched in. */
    public static Panel hoverPanel() {
        return hoverPanel;
    }

    /** The hit under the crosshair, on whichever panel it is over. */
    public static Hit hoveredAny() {
        return hoverPanel.active ? hoverPanel.hovered : Hit.NONE;
    }

    /** Record that the crosshair is over {@code panel}; the other panel's hover clears. */
    public static void setHoverPanel(Panel panel) {
        hoverPanel = panel;
        for (Panel p : List.of(MAIN, CHILD)) {
            if (p != panel) p.hovered = Hit.NONE;
        }
    }

    /**
     * Place the stacked group panel directly above the main one, in the same plane and facing: the
     * main panel is centred on its anchor, so the child's centre sits half of each panel's height
     * (plus a gap) further up. Called each frame before drawing and hit-testing, since either
     * panel's height changes as rows are added or removed.
     */
    public static void layoutChild(double worldScale) {
        if (!childOpen()) return;
        double mainH = fromPanel(MAIN, () -> BlockVariantMenuRenderer.panelSize().panelH());
        double childH = fromPanel(CHILD, () -> BlockVariantMenuRenderer.panelSize().panelH());
        CHILD.anchorRight = MAIN.anchorRight;
        CHILD.anchorUp = MAIN.anchorUp;
        CHILD.anchorNormal = MAIN.anchorNormal;
        CHILD.anchorPos = MAIN.anchorPos.add(MAIN.anchorUp.scale(
            StackedPanelLayout.childOffset(mainH, childH, worldScale)));
    }

    /** Pop our screen if it is the active one — guarded so another mod's GUI isn't clobbered. */
    private static void dismissScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof BlockVariantMenuScreen)) return;
        try {
            mc.setScreen(null);
        } catch (IllegalStateException disconnectRace) {
            // setScreen throws during world disconnect ("Trying to return to in-game GUI during
            // disconnection"). MC is tearing the screen down anyway, so our state is already clean.
        }
    }

    public static void enterSearch() {
        current.screen = Screen.ADD_SEARCH;
        current.searchBuffer = "";
        current.hovered = Hit.NONE;
    }

    public static void backToRoot() {
        current.screen = Screen.ROOT;
        current.searchBuffer = "";
        current.hovered = Hit.NONE;
    }

    public static void toggleRemoveMode() {
        current.removeMode = !current.removeMode;
        current.hovered = Hit.NONE;
    }

    public static void appendSearch(char c) {
        if (current.searchBuffer.length() >= 32) return;
        current.searchBuffer = current.searchBuffer + c;
    }

    public static void backspaceSearch() {
        if (current.searchBuffer.isEmpty()) return;
        current.searchBuffer = current.searchBuffer.substring(0, current.searchBuffer.length() - 1);
    }

    /**
     * All registered block IDs, lower-cased, sorted alphabetically. Built
     * once and cached. Used by the ADD_SEARCH sub-screen.
     */
    public static List<String> allBlockIds() {
        if (cachedBlockIds == null) {
            List<String> ids = new ArrayList<>(BuiltInRegistries.BLOCK.keySet().size());
            for (ResourceLocation loc : BuiltInRegistries.BLOCK.keySet()) {
                ids.add(loc.toString());
            }
            Collections.sort(ids);
            cachedBlockIds = Collections.unmodifiableList(ids);
        }
        return cachedBlockIds;
    }

    /** Lowercase substring filter against {@link #allBlockIds()}. Empty buffer → all ids. */
    public static List<String> filteredBlockIds() {
        List<String> all = allBlockIds();
        if (current.searchBuffer.isEmpty()) return all;
        String needle = current.searchBuffer.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String n : all) {
            if (n.toLowerCase(Locale.ROOT).contains(needle)) out.add(n);
        }
        return out;
    }
}
