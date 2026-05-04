package games.brennan.dungeontrain.client.menu.blockvariant;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import games.brennan.dungeontrain.DungeonTrain;
import games.brennan.dungeontrain.client.menu.MenuRenderStates;
import games.brennan.dungeontrain.editor.RotationApplier;
import games.brennan.dungeontrain.editor.VariantRotation;
import games.brennan.dungeontrain.net.BlockVariantSyncPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;

/**
 * World-space renderer for {@link BlockVariantMenu}. Mirrors
 * {@link games.brennan.dungeontrain.client.menu.parts.PartPositionMenuRenderer}
 * with two tweaks: a 5-cell toolbar (Copy / Add / Remove / Clear / X)
 * instead of 4, and a per-row Lock column instead of side-mode.
 *
 * <p>Drawn during {@link RenderLevelStageEvent.Stage#AFTER_TRANSLUCENT_BLOCKS}.
 * The cell label shows the block id's path segment (e.g. "stone_bricks"
 * from "minecraft:stone_bricks") to keep the row narrow; full id is shown
 * in the search screen.</p>
 */
@EventBusSubscriber(
    modid = DungeonTrain.MOD_ID,
    value = Dist.CLIENT
)
public final class BlockVariantMenuRenderer {

    static final RenderType PANEL_QUAD = RenderType.create(
        DungeonTrain.MOD_ID + ":block_variant_menu_quad",
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

    static final double ROW_HEIGHT = 0.30;
    static final double HEADER_HEIGHT = 0.32;
    static final double TOOLBAR_HEIGHT = 0.32;
    /** A grid column = name cell + weight + (optional) X. */
    static final double COLUMN_WIDTH = 1.7;
    /** Six-cell toolbar (Copy/Add/Lock/Remove/Clear/X) needs more width. */
    static final double MIN_PANEL_WIDTH = 3.2;
    static final double X_CELL_WIDTH = 0.30;
    static final double WEIGHT_CELL_WIDTH = 0.40;
    /** ~20% of {@link #COLUMN_WIDTH} so the L/R/O pill segments are comfortable click targets. */
    static final double ROT_MODE_CELL_WIDTH = 0.34;
    static final double ROT_DIRS_CELL_WIDTH = 0.32;
    static final double TEXT_SCALE = 0.012;
    static final double POPUP_BUTTON_SIZE = 0.20;
    static final double ICON_SIZE = 0.22;
    static final double ICON_LEFT_PAD = 0.03;
    static final double ICON_TEXT_GAP = 0.05;
    /** Cumulative left-pad for the row text once an icon takes the leading slot. */
    static final double NAME_TEXT_LEFT_OFFSET = ICON_LEFT_PAD + ICON_SIZE + ICON_TEXT_GAP;

    private BlockVariantMenuRenderer() {}

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (!BlockVariantMenu.isActive()) return;
        if (BlockVariantMenu.localPos() == null) return;

        BlockVariantMenuRaycast.updateHovered();

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        PoseStack ps = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        Vec3 anchor = BlockVariantMenu.anchorPos();
        Vec3 right = BlockVariantMenu.anchorRight();
        Vec3 up = BlockVariantMenu.anchorUp();
        Vec3 normal = BlockVariantMenu.anchorNormal();

        ps.pushPose();
        ps.translate(anchor.x - cam.x, anchor.y - cam.y, anchor.z - cam.z);
        Matrix3f basis = new Matrix3f(
            (float) right.x, (float) right.y, (float) right.z,
            (float) up.x, (float) up.y, (float) up.z,
            (float) normal.x, (float) normal.y, (float) normal.z
        );
        ps.mulPose(new Quaternionf().setFromNormalized(basis));

        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        if (BlockVariantMenu.screen() == BlockVariantMenu.Screen.ROOT) {
            drawRoot(ps, buffer, font);
        } else {
            drawSearch(ps, buffer, font);
        }

        buffer.endBatch(PANEL_QUAD);
        // Flush remaining queued render types (item-icon textures, glint,
        // font batches) so they draw with correct depth ordering against
        // the world. Items use multiple internal RenderTypes that the
        // explicit PANEL_QUAD flush above doesn't cover.
        buffer.endBatch();
        ps.popPose();
    }

    private static void drawRoot(PoseStack ps, MultiBufferSource buffer, Font font) {
        List<BlockVariantSyncPacket.Entry> entries = BlockVariantMenu.entries();
        int n = entries.size();
        int colCount = Math.max(1, (n + BlockVariantMenu.ROWS_PER_COLUMN - 1) / BlockVariantMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(MIN_PANEL_WIDTH, colCount * COLUMN_WIDTH);
        int displayedRows = Math.min(n, BlockVariantMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ROW_HEIGHT;
        double panelH = HEADER_HEIGHT + TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        boolean removeMode = BlockVariantMenu.removeMode();
        BlockVariantMenu.Hit hovered = BlockVariantMenu.hovered();

        // Backdrop
        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        // Header — show the cell's local position + lock-id so the player can confirm what they're editing.
        double headerCY = halfH - HEADER_HEIGHT / 2.0;
        drawQuad(ps, buffer, -halfW, halfH - HEADER_HEIGHT, halfW, halfH, 0x40FFEEBB);
        net.minecraft.core.BlockPos local = BlockVariantMenu.localPos();
        int cellLockId = BlockVariantMenu.lockId();
        String lockLabel = cellLockId > 0 ? "  ·  lock " + cellLockId : "";
        String headerLabel = local == null
            ? "Block Variants"
            : "Block Variants @ " + local.getX() + "," + local.getY() + "," + local.getZ() + lockLabel;
        drawCenteredText(ps, buffer, font, headerLabel, 0, headerCY, 0xFFFFEEBB);

        // Toolbar — 7 cells: Copy | Save | Add | Lock | Remove | Clear | X.
        double toolbarTop = halfH - HEADER_HEIGHT;
        double toolbarBottom = toolbarTop - TOOLBAR_HEIGHT;
        double toolbarCY = (toolbarTop + toolbarBottom) / 2.0;
        double cellW = panelW / 7.0;
        for (int i = 0; i < 7; i++) {
            double xL = -halfW + i * cellW;
            double xR = xL + cellW;
            BlockVariantMenu.CellKind cellKind = switch (i) {
                case 0 -> BlockVariantMenu.CellKind.COPY;
                case 1 -> BlockVariantMenu.CellKind.SAVE;
                case 2 -> BlockVariantMenu.CellKind.ADD;
                case 3 -> BlockVariantMenu.CellKind.LOCK;
                case 4 -> BlockVariantMenu.CellKind.REMOVE;
                case 5 -> BlockVariantMenu.CellKind.CLEAR;
                default -> BlockVariantMenu.CellKind.CLOSE;
            };
            boolean isHover = hovered.kind() == cellKind;
            int tint;
            if (cellKind == BlockVariantMenu.CellKind.REMOVE && removeMode) {
                tint = isHover ? 0xC0FF6666 : 0x80AA4040;
            } else if (cellKind == BlockVariantMenu.CellKind.CLOSE) {
                tint = isHover ? 0xC0FF8080 : 0x40FF6060;
            } else if (cellKind == BlockVariantMenu.CellKind.COPY) {
                tint = isHover ? 0xB033FFCC : 0x40339999;
            } else if (cellKind == BlockVariantMenu.CellKind.SAVE) {
                tint = isHover ? 0xB033CCFF : 0x40337799;
            } else if (cellKind == BlockVariantMenu.CellKind.LOCK) {
                if (cellLockId > 0) {
                    // Locked cell — warm orange to make active state obvious.
                    tint = isHover ? 0xC0FFAA55 : 0x80CC7733;
                } else {
                    tint = isHover ? 0xC0AAAAAA : 0x60777777;
                }
            } else {
                tint = isHover ? 0xB0FFCC33 : 0x30FFFFFF;
            }
            drawQuad(ps, buffer, xL + 0.01, toolbarBottom + 0.005,
                xR - 0.01, toolbarTop - 0.005, tint);
            String label = switch (cellKind) {
                case COPY -> "Copy";
                case SAVE -> "Save";
                case ADD -> "Add";
                // Lock label shows current cell lock-id: "-" unlocked, or
                // the digit (e.g. "2") when locked. Cycles to next free
                // when 0, back to 0 when set.
                case LOCK -> cellLockId > 0 ? Integer.toString(cellLockId) : "-";
                case REMOVE -> removeMode ? "Cancel" : "Remove";
                case CLEAR -> "Clear";
                case CLOSE -> "X";
                default -> "";
            };
            int colour = isHover ? 0xFF000000 : 0xFFFFFFFF;
            drawCenteredText(ps, buffer, font, label, (xL + xR) / 2.0, toolbarCY, colour);
        }

        // Grid
        double colActualW = panelW / colCount;
        double gridTop = toolbarBottom;
        for (int i = 0; i < n; i++) {
            int col = i / BlockVariantMenu.ROWS_PER_COLUMN;
            int row = i % BlockVariantMenu.ROWS_PER_COLUMN;
            double colXL = -halfW + col * colActualW;
            double colXR = colXL + colActualW;
            double rowTop = gridTop - row * ROW_HEIGHT;
            double rowBottom = rowTop - ROW_HEIGHT;
            double rowCY = (rowTop + rowBottom) / 2.0;

            int rowTint = (i % 2 == 0) ? 0x20FFFFFF : 0x10FFFFFF;
            drawQuad(ps, buffer, colXL + 0.01, rowBottom + 0.005,
                colXR - 0.01, rowTop - 0.005, rowTint);

            BlockVariantSyncPacket.Entry entry = entries.get(i);

            // Cell layout right-to-left:
            //   [X]  (rightmost, remove-mode only)
            //   [Weight]
            //   [RotDirs]   (only when block is rotatable AND mode != RANDOM)
            //   [RotMode]   (only when block is rotatable)
            //   [Name] (fills the remaining left)
            double xCellW = removeMode ? X_CELL_WIDTH : 0.0;
            BlockState parsed = BlockVariantMenu.parseState(entry.stateString());
            boolean rotatable = parsed != null && RotationApplier.canRotate(parsed);
            VariantRotation.Mode rowMode = decodeMode(entry.rotMode());
            boolean showDirs = rotatable && rowMode != VariantRotation.Mode.RANDOM;
            double weightCellR = colXR - xCellW;
            double weightCellL = weightCellR - WEIGHT_CELL_WIDTH;
            double rotDirsCellR = weightCellL;
            double rotDirsCellL = showDirs ? rotDirsCellR - ROT_DIRS_CELL_WIDTH : rotDirsCellR;
            double rotModeCellR = rotDirsCellL;
            double rotModeCellL = rotatable ? rotModeCellR - ROT_MODE_CELL_WIDTH : rotModeCellR;
            double nameCellL = colXL;
            double nameCellR = rotModeCellL;

            // Name highlight + icon + label
            boolean nameHover = hovered.kind() == BlockVariantMenu.CellKind.ENTRY_NAME && hovered.index() == i;
            if (nameHover) {
                drawQuad(ps, buffer, nameCellL + 0.01, rowBottom + 0.005,
                    nameCellR - 0.005, rowTop - 0.005, 0x60FFCC33);
            }
            drawBlockIcon(ps, buffer, entry.stateString(), nameCellL, rowCY);
            drawLeftText(ps, buffer, font, shortenStateLabel(entry.stateString()),
                nameCellL + NAME_TEXT_LEFT_OFFSET, rowCY,
                nameHover ? 0xFF000000 : 0xFFFFFFFF);

            // Rotation cells
            if (rotatable) {
                drawRotationCells(ps, buffer, font, i, entry,
                    rotModeCellL, rotModeCellR, rotDirsCellL, rotDirsCellR,
                    rowBottom, rowTop, rowCY, hovered, showDirs);
            }

            // Weight cell
            boolean weightHover = hovered.kind() == BlockVariantMenu.CellKind.ENTRY_WEIGHT && hovered.index() == i;
            int weightTint = weightHover ? 0xC0FFCC33 : 0x40FFFFFF;
            drawQuad(ps, buffer, weightCellL + 0.005, rowBottom + 0.005,
                weightCellR - 0.005, rowTop - 0.005, weightTint);
            drawCenteredText(ps, buffer, font, Integer.toString(entry.weight()),
                (weightCellL + weightCellR) / 2.0, rowCY,
                weightHover ? 0xFF000000 : 0xFFFFFFFF);

            // Remove [X]
            if (removeMode) {
                double xCellL = colXR - X_CELL_WIDTH;
                double xCellR = colXR;
                boolean xHover = hovered.kind() == BlockVariantMenu.CellKind.ENTRY_REMOVE_X && hovered.index() == i;
                int xTint = xHover ? 0xC0FF4040 : 0x80AA2020;
                drawQuad(ps, buffer, xCellL + 0.005, rowBottom + 0.005,
                    xCellR - 0.005, rowTop - 0.005, xTint);
                drawCenteredText(ps, buffer, font, "X",
                    (xCellL + xCellR) / 2.0, rowCY, 0xFFFFFFFF);
            }
        }

        // OPTIONS popup is drawn last so it shadows the row underneath.
        int popupRow = BlockVariantMenu.rotPopupRowIndex();
        if (popupRow >= 0 && popupRow < n) {
            drawRotationOptionsPopup(ps, buffer, font, popupRow, entries.get(popupRow),
                colActualW, gridTop, halfW, hovered);
        }
    }

    /**
     * Draw the per-row rotation cells: a 3-state mode pill (L/R/O) and a
     * direction cell whose content depends on mode (label / dot / count).
     */
    private static void drawRotationCells(PoseStack ps, MultiBufferSource buffer, Font font,
                                          int rowIndex, BlockVariantSyncPacket.Entry entry,
                                          double modeL, double modeR, double dirsL, double dirsR,
                                          double rowBottom, double rowTop, double rowCY,
                                          BlockVariantMenu.Hit hovered, boolean showDirs) {
        VariantRotation.Mode mode = decodeMode(entry.rotMode());
        int dirMask = entry.rotDirMask() & VariantRotation.ALL_DIRS_MASK;

        boolean modeHover = hovered.kind() == BlockVariantMenu.CellKind.ENTRY_ROT_MODE && hovered.index() == rowIndex;
        boolean dirsHover = hovered.kind() == BlockVariantMenu.CellKind.ENTRY_ROT_DIRS && hovered.index() == rowIndex;

        // Mode pill — three mini-segments, the active one highlighted.
        double pillBot = rowBottom + 0.02;
        double pillTop = rowTop - 0.02;
        double segW = (modeR - modeL - 0.02) / 3.0;
        for (int seg = 0; seg < 3; seg++) {
            double sL = modeL + 0.01 + seg * segW;
            double sR = sL + segW - 0.005;
            boolean active = seg == mode.ordinal();
            int tint;
            if (active) {
                // Warm/cool/accent depending on mode, brighter on hover.
                tint = switch (seg) {
                    case 0 -> modeHover ? 0xC0FFAA55 : 0x80CC7733; // LOCK orange
                    case 1 -> modeHover ? 0xC066AAFF : 0x8033679B; // RANDOM blue
                    default -> modeHover ? 0xC0AA66FF : 0x80663399; // OPTIONS purple
                };
            } else {
                tint = modeHover ? 0x60AAAAAA : 0x30777777;
            }
            drawQuad(ps, buffer, sL, pillBot, sR, pillTop, tint);
            String label = switch (seg) {
                case 0 -> "L";
                case 1 -> "R";
                default -> "O";
            };
            drawCenteredText(ps, buffer, font, label,
                (sL + sR) / 2.0, rowCY,
                active ? 0xFFFFFFFF : 0xFF888888);
        }

        // Direction cell — hidden entirely when mode is RANDOM (no
        // per-direction concept, so the cell would just take space).
        if (!showDirs) return;
        int dirsTint = dirsHover ? 0xC0FFCC33 : 0x40FFFFFF;
        drawQuad(ps, buffer, dirsL + 0.005, rowBottom + 0.005,
            dirsR - 0.005, rowTop - 0.005, dirsTint);
        String dirsLabel = switch (mode) {
            case LOCK -> dirMask == 0 ? "?" : dirShortName(directionFromLowestBit(dirMask));
            case OPTIONS -> Integer.bitCount(dirMask) + "/6";
            default -> "";
        };
        drawCenteredText(ps, buffer, font, dirsLabel,
            (dirsL + dirsR) / 2.0, rowCY,
            dirsHover ? 0xFF000000 : 0xFFFFFFFF);
    }

    /**
     * Floating popup anchored above the row's direction cell — 3×2 grid of
     * direction toggle buttons used in OPTIONS mode. Buttons reflect
     * selection state and only toggle directions valid for this block's
     * rotation property.
     */
    private static void drawRotationOptionsPopup(PoseStack ps, MultiBufferSource buffer, Font font,
                                                 int rowIndex, BlockVariantSyncPacket.Entry entry,
                                                 double colActualW, double gridTop, double halfW,
                                                 BlockVariantMenu.Hit hovered) {
        BlockState parsed = BlockVariantMenu.parseState(entry.stateString());
        if (parsed == null) return;
        int validMask = RotationApplier.validDirMask(parsed);
        int dirMask = entry.rotDirMask() & VariantRotation.ALL_DIRS_MASK;

        int col = rowIndex / BlockVariantMenu.ROWS_PER_COLUMN;
        int row = rowIndex % BlockVariantMenu.ROWS_PER_COLUMN;
        double colXL = -halfW + col * colActualW;
        double rowTop = gridTop - row * ROW_HEIGHT;

        double popupW = 3 * POPUP_BUTTON_SIZE + 0.04;
        double popupH = 2 * POPUP_BUTTON_SIZE + 0.04;
        double popupCX = colXL + colActualW - ROT_DIRS_CELL_WIDTH / 2.0 - WEIGHT_CELL_WIDTH;
        double popupBot = rowTop + 0.02;
        double popupTop = popupBot + popupH;
        double popupL = popupCX - popupW / 2.0;
        double popupR = popupL + popupW;

        // Backdrop
        drawQuad(ps, buffer, popupL, popupBot, popupR, popupTop, 0xE0202020);

        // 3 columns × 2 rows: top row = positive (UP, EAST, SOUTH);
        // bottom row = negative (DOWN, WEST, NORTH). Visually compact.
        Direction[][] grid = {
            { Direction.UP, Direction.EAST, Direction.SOUTH },
            { Direction.DOWN, Direction.WEST, Direction.NORTH }
        };
        for (int gy = 0; gy < 2; gy++) {
            for (int gx = 0; gx < 3; gx++) {
                Direction d = grid[gy][gx];
                int bit = VariantRotation.maskOf(d);
                boolean valid = (validMask & bit) != 0;
                boolean selected = (dirMask & bit) != 0;
                boolean btnHover = hovered.kind() == BlockVariantMenu.CellKind.ROT_DIR_OPTION
                    && hovered.index() == rowIndex && hovered.secondary() == d.ordinal();

                double bL = popupL + 0.02 + gx * POPUP_BUTTON_SIZE;
                double bR = bL + POPUP_BUTTON_SIZE - 0.005;
                double bTop = popupTop - 0.02 - gy * POPUP_BUTTON_SIZE;
                double bBot = bTop - POPUP_BUTTON_SIZE + 0.005;

                int tint;
                if (!valid) {
                    tint = 0x40404040;
                } else if (selected) {
                    tint = btnHover ? 0xD066FF99 : 0xA033CC66;
                } else {
                    tint = btnHover ? 0xC0AAAAAA : 0x60777777;
                }
                drawQuad(ps, buffer, bL, bBot, bR, bTop, tint);
                int textColour = valid ? 0xFFFFFFFF : 0xFF666666;
                drawCenteredText(ps, buffer, font, dirShortName(d),
                    (bL + bR) / 2.0, (bTop + bBot) / 2.0, textColour);
            }
        }
    }

    /** Decode wire byte → mode enum, defaulting to RANDOM on out-of-range. */
    static VariantRotation.Mode decodeMode(byte raw) {
        int ord = raw & 0xFF;
        VariantRotation.Mode[] values = VariantRotation.Mode.values();
        if (ord < 0 || ord >= values.length) return VariantRotation.Mode.RANDOM;
        return values[ord];
    }

    /** XU/XD/YU/YD/ZU/ZD short-name for a Direction. */
    static String dirShortName(Direction d) {
        return switch (d) {
            case EAST -> "XU";
            case WEST -> "XD";
            case UP -> "YU";
            case DOWN -> "YD";
            case SOUTH -> "ZU";
            case NORTH -> "ZD";
        };
    }

    static Direction directionFromLowestBit(int mask) {
        int ord = Integer.numberOfTrailingZeros(mask);
        if (ord < 0 || ord >= 6) return Direction.UP;
        return Direction.values()[ord];
    }

    private static void drawSearch(PoseStack ps, MultiBufferSource buffer, Font font) {
        List<String> filtered = BlockVariantMenu.filteredBlockIds();
        // Cap the rendered list to one column's worth so the panel doesn't
        // explode horizontally when the player hasn't typed yet (vanilla
        // has ~600+ blocks).
        int maxRows = BlockVariantMenu.ROWS_PER_COLUMN * 4;
        int n = Math.min(filtered.size(), maxRows);
        int colCount = Math.max(1, (n + BlockVariantMenu.ROWS_PER_COLUMN - 1) / BlockVariantMenu.ROWS_PER_COLUMN);
        double panelW = Math.max(MIN_PANEL_WIDTH, colCount * COLUMN_WIDTH);
        int displayedRows = Math.min(n, BlockVariantMenu.ROWS_PER_COLUMN);
        if (displayedRows == 0) displayedRows = 1;
        double gridH = displayedRows * ROW_HEIGHT;
        double panelH = HEADER_HEIGHT + TOOLBAR_HEIGHT + gridH;
        double halfW = panelW / 2.0;
        double halfH = panelH / 2.0;
        BlockVariantMenu.Hit hovered = BlockVariantMenu.hovered();

        drawQuad(ps, buffer, -halfW, -halfH, halfW, halfH, 0xC8000000);

        // Header: "< Back" left chip + "Add Block Variant" rest.
        double headerTop = halfH;
        double headerBottom = halfH - HEADER_HEIGHT;
        double headerCY = (headerTop + headerBottom) / 2.0;
        double backCellW = 0.6;
        double backCellL = -halfW;
        double backCellR = backCellL + backCellW;
        boolean backHover = hovered.kind() == BlockVariantMenu.CellKind.SEARCH_BACK;
        int backTint = backHover ? 0xC0FFCC33 : 0x60FFEEBB;
        drawQuad(ps, buffer, backCellL + 0.01, headerBottom + 0.005,
            backCellR - 0.005, headerTop - 0.005, backTint);
        drawCenteredText(ps, buffer, font, "< Back",
            (backCellL + backCellR) / 2.0, headerCY,
            backHover ? 0xFF000000 : 0xFFFFFFFF);
        drawQuad(ps, buffer, backCellR + 0.005, headerBottom + 0.005,
            halfW, headerTop - 0.005, 0x40FFEEBB);
        drawCenteredText(ps, buffer, font, "Add Block Variant",
            (backCellR + halfW) / 2.0, headerCY, 0xFFFFEEBB);

        // Search field row
        double searchTop = halfH - HEADER_HEIGHT;
        double searchBottom = searchTop - TOOLBAR_HEIGHT;
        double searchCY = (searchTop + searchBottom) / 2.0;
        boolean searchHover = hovered.kind() == BlockVariantMenu.CellKind.SEARCH_FIELD;
        int searchTint = searchHover ? 0xB033FF99 : 0x60339966;
        drawQuad(ps, buffer, -halfW + 0.02, searchBottom + 0.01,
            halfW - 0.02, searchTop - 0.01, searchTint);
        String shown = "Search: " + BlockVariantMenu.searchBuffer() + "_";
        drawLeftText(ps, buffer, font, shown, -halfW + 0.06, searchCY, 0xFFFFFFFF);

        double colActualW = panelW / colCount;
        double gridTop = searchBottom;
        for (int i = 0; i < n; i++) {
            int col = i / BlockVariantMenu.ROWS_PER_COLUMN;
            int row = i % BlockVariantMenu.ROWS_PER_COLUMN;
            double colXL = -halfW + col * colActualW;
            double colXR = colXL + colActualW;
            double rowTop = gridTop - row * ROW_HEIGHT;
            double rowBottom = rowTop - ROW_HEIGHT;
            double rowCY = (rowTop + rowBottom) / 2.0;
            boolean isHover = hovered.kind() == BlockVariantMenu.CellKind.SEARCH_RESULT && hovered.index() == i;
            int tint = isHover ? 0xC0FFCC33 : (i % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
            drawQuad(ps, buffer, colXL + 0.01, rowBottom + 0.005,
                colXR - 0.01, rowTop - 0.005, tint);
            int textColour = isHover ? 0xFF000000 : 0xFFFFFFFF;
            drawBlockIcon(ps, buffer, filtered.get(i), colXL, rowCY);
            drawLeftText(ps, buffer, font, filtered.get(i),
                colXL + NAME_TEXT_LEFT_OFFSET, rowCY, textColour);
        }
    }

    /**
     * Strip {@code modid:} prefix and any blockstate properties for the
     * row label — just the path segment fits the narrow name cell. The
     * full state is preserved in the underlying entry; tooltip / search
     * still uses it.
     *
     * <p>Special-case: any vanilla command-block kind is the
     * empty-placeholder sentinel (see
     * {@code CarriageVariantBlocks.isEmptyPlaceholder}) and renders as
     * {@code "nothing"} so authors immediately read it as the
     * "leave this position empty / air at spawn time" entry.</p>
     */
    static String shortenStateLabel(String stateString) {
        if (stateString == null) return "";
        // Drop properties section "[...]"
        int bracket = stateString.indexOf('[');
        String trimmed = bracket >= 0 ? stateString.substring(0, bracket) : stateString;
        if (trimmed.equals("minecraft:command_block")
            || trimmed.equals("minecraft:chain_command_block")
            || trimmed.equals("minecraft:repeating_command_block")) {
            return "nothing";
        }
        int colon = trimmed.indexOf(':');
        return colon >= 0 ? trimmed.substring(colon + 1) : trimmed;
    }

    /**
     * Render a small inventory-style icon for the block at the start of a
     * row. Uses {@link ItemDisplayContext#GUI} so the model gets the same
     * 3-quarter transform vanilla inventory slots use. Skipped for the
     * {@code "nothing"} sentinel — its label already conveys it.
     *
     * <p>Falls back to {@link Items#BARRIER} when the block has no item
     * representation, mirroring the pattern in
     * {@link games.brennan.dungeontrain.client.VariantHoverHudOverlay}.</p>
     */
    static void drawBlockIcon(PoseStack ps, MultiBufferSource buffer,
                              String stateString, double cellLeftX, double rowCY) {
        if (stateString == null || stateString.isEmpty()) return;
        if ("nothing".equals(shortenStateLabel(stateString))) return;
        BlockState state = BlockVariantMenu.parseState(stateString);
        if (state == null) return;
        Item item = state.getBlock().asItem();
        ItemStack stack = (item == null || item == Items.AIR)
            ? new ItemStack(Items.BARRIER)
            : new ItemStack(item);

        Minecraft mc = Minecraft.getInstance();
        ItemRenderer itemRenderer = mc.getItemRenderer();
        ps.pushPose();
        ps.translate(cellLeftX + ICON_LEFT_PAD + ICON_SIZE / 2.0, rowCY, 0.002);
        ps.scale((float) ICON_SIZE, (float) ICON_SIZE, (float) ICON_SIZE);
        itemRenderer.renderStatic(
            stack,
            ItemDisplayContext.GUI,
            LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY,
            ps,
            buffer,
            mc.level,
            0
        );
        ps.popPose();
    }

    static void drawCenteredText(PoseStack ps, MultiBufferSource buffer, Font font,
                                 String text, double worldX, double worldY, int colour) {
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

    static void drawLeftText(PoseStack ps, MultiBufferSource buffer, Font font,
                             String text, double worldX, double worldY, int colour) {
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

    static void drawQuad(PoseStack ps, MultiBufferSource buffer,
                         double x1, double y1, double x2, double y2, int argb) {
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
