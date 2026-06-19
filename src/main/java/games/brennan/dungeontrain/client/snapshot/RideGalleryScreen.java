package games.brennan.dungeontrain.client.snapshot;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

/**
 * Browse this run's third-person ride photos ({@link RideSnapshotGallery}) and
 * save the ones worth keeping to {@code screenshots/}. Opened from the death
 * screen's top-right "photos" chip (final page only); returns there on close.
 *
 * <p>A responsive thumbnail grid (one cell per photo, contain-fit so the whole
 * frame shows) with a per-photo "save" tab that flips to "saved" once written.
 * A bottom row offers <i>Save all</i> / <i>Open folder</i> / <i>Done</i>. There
 * is no selection state — clicking a photo saves it immediately (idempotent for
 * this screen session, so a second click can't write a duplicate).</p>
 */
public final class RideGalleryScreen extends Screen {

    private static final Logger LOGGER = LogUtils.getLogger();

    // ---- Palette (ARGB), echoing NarrativeDeathScreen ----
    private static final int OVERLAY      = 0xF2090A0D;
    private static final int TITLE        = 0xFFE6D6B0;
    private static final int SUBTITLE     = 0xFF9A8F74;
    private static final int CELL_BG      = 0xFF14151A;
    private static final int CELL_BORDER  = 0x33FFFFFF;
    private static final int CELL_HOVER   = 0x88FFFFFF;
    private static final int TAG_BG       = 0x99000000;
    private static final int TAG_BORDER   = 0x33D6C496;
    private static final int TAG_TEXT     = 0xFFC7BDA7;
    private static final int SAVE_BORDER  = 0xFF5A5236;
    private static final int SAVE_TEXT    = 0xFFC9B98A;
    private static final int SAVED_BORDER = 0xFF3C6B41;
    private static final int SAVED_TEXT   = 0xFF8FCB99;
    private static final int STATUS_OK    = 0xFF7FAE84;
    private static final int STATUS_ERR   = 0xFFFF5555;

    private static final int MARGIN_X = 20;
    private static final int GRID_TOP = 46;
    private static final int GAP = 8;
    private static final int TARGET_CELL_W = 150;
    private static final int MAX_COLS = 5;
    private static final int SCROLL_STEP = 36;

    private final Screen parent;
    private final List<RideSnapshot> shots;
    private final Set<Integer> saved = new HashSet<>();

    // Layout, recomputed in relayout() (init / resize).
    private int cols = 1, cellW = TARGET_CELL_W, cellH = 84, gridBottom, maxScroll;
    private int scrollY = 0;

    private Component status = Component.empty();
    private int statusColor = STATUS_OK;

    public RideGalleryScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.death.gallery.title"));
        this.parent = parent;
        this.shots = RideSnapshotGallery.all();
    }

    @Override
    protected void init() {
        relayout();

        int btnY = this.height - 28;
        int gap = 6;
        int wAll = 86, wOpen = 96, wDone = 64;
        int total = wAll + wOpen + wDone + gap * 2;
        int x = this.width / 2 - total / 2;

        Button saveAll = Button.builder(Component.translatable("gui.dungeontrain.death.gallery.save_all"), b -> saveAll())
                .bounds(x, btnY, wAll, 20).build();
        saveAll.active = !shots.isEmpty();
        addRenderableWidget(saveAll);
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.death.gallery.open_folder"), b -> openFolder())
                .bounds(x + wAll + gap, btnY, wOpen, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.dungeontrain.death.gallery.done"), b -> onClose())
                .bounds(x + wAll + wOpen + gap * 2, btnY, wDone, 20).build());
    }

    private void relayout() {
        int avail = this.width - 2 * MARGIN_X;
        cols = Mth.clamp((avail + GAP) / (TARGET_CELL_W + GAP), 1, MAX_COLS);
        cellW = (avail - (cols - 1) * GAP) / cols;
        cellH = Math.round(cellW * 9.0f / 16.0f);
        gridBottom = this.height - 50;
        int rows = shots.isEmpty() ? 0 : (shots.size() + cols - 1) / cols;
        int totalGridH = rows == 0 ? 0 : rows * cellH + (rows - 1) * GAP;
        int viewportH = Math.max(0, gridBottom - GRID_TOP);
        maxScroll = Math.max(0, totalGridH - viewportH);
        scrollY = Mth.clamp(scrollY, 0, maxScroll);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, OVERLAY);

        int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, 16, TITLE);
        Component sub = shots.isEmpty()
                ? Component.translatable("gui.dungeontrain.death.gallery.empty")
                : Component.translatable("gui.dungeontrain.death.gallery.subtitle", shots.size());
        g.drawCenteredString(this.font, sub, cx, 28, SUBTITLE);

        // Grid, clipped to the viewport so scrolled cells don't bleed over the chrome.
        if (!shots.isEmpty()) {
            g.enableScissor(MARGIN_X, GRID_TOP, this.width - MARGIN_X, gridBottom);
            for (int i = 0; i < shots.size(); i++) {
                int col = i % cols, row = i / cols;
                int x = MARGIN_X + col * (cellW + GAP);
                int y = GRID_TOP + row * (cellH + GAP) - scrollY;
                if (y + cellH < GRID_TOP || y > gridBottom) continue; // fully scrolled out
                boolean hover = mouseX >= x && mouseX < x + cellW && mouseY >= y && mouseY < y + cellH
                        && mouseY >= GRID_TOP && mouseY < gridBottom;
                drawCell(g, shots.get(i), i, x, y, hover);
            }
            g.disableScissor();
        }

        // Status line above the button row.
        if (!status.getString().isEmpty()) {
            g.drawCenteredString(this.font, status, cx, this.height - 42, statusColor);
        }

        for (Renderable r : this.renderables) {
            r.render(g, mouseX, mouseY, partialTick);
        }
    }

    private void drawCell(GuiGraphics g, RideSnapshot shot, int index, int x, int y, boolean hover) {
        g.fill(x, y, x + cellW, y + cellH, CELL_BG);
        drawContain(g, shot, x, y, cellW, cellH);
        drawBorder(g, x, y, cellW, cellH, hover ? CELL_HOVER : CELL_BORDER);

        // Tag chip, bottom-left.
        if (shot.tag() != null) {
            drawTab(g, x + 3, y + cellH - 14, shot.tag().name().toLowerCase(Locale.ROOT), TAG_BORDER, TAG_TEXT);
        }
        // Save / saved tab, top-right.
        boolean isSaved = saved.contains(index);
        String label = Component.translatable(isSaved
                ? "gui.dungeontrain.death.gallery.saved"
                : "gui.dungeontrain.death.gallery.save").getString();
        int tabW = this.font.width(label) + 8;
        drawTab(g, x + cellW - tabW - 3, y + 3, label,
                isSaved ? SAVED_BORDER : SAVE_BORDER, isSaved ? SAVED_TEXT : SAVE_TEXT);
    }

    /** Contain-fit the photo into the cell (whole frame visible, centred). */
    private void drawContain(GuiGraphics g, RideSnapshot shot, int cx, int cy, int cw, int ch) {
        float imgAspect = shot.aspect();
        float cellAspect = (float) cw / Math.max(1, ch);
        int dw, dh;
        if (cellAspect > imgAspect) { // cell wider than photo → match height
            dh = ch;
            dw = Math.round(ch * imgAspect);
        } else {                      // match width
            dw = cw;
            dh = Math.round(cw / imgAspect);
        }
        int dx = cx + (cw - dw) / 2;
        int dy = cy + (ch - dh) / 2;
        g.blit(shot.texture(), dx, dy, dw, dh, 0.0f, 0.0f, shot.width(), shot.height(), shot.width(), shot.height());
    }

    private void drawTab(GuiGraphics g, int x, int y, String text, int border, int textColor) {
        int w = this.font.width(text) + 8, h = 11;
        g.fill(x, y, x + w, y + h, TAG_BG);
        drawBorder(g, x, y, w, h, border);
        g.drawString(this.font, text, x + 4, y + 2, textColor, false);
    }

    private void drawBorder(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && !shots.isEmpty() && my >= GRID_TOP && my < gridBottom) {
            for (int i = 0; i < shots.size(); i++) {
                int col = i % cols, row = i / cols;
                int x = MARGIN_X + col * (cellW + GAP);
                int y = GRID_TOP + row * (cellH + GAP) - scrollY;
                if (mx >= x && mx < x + cellW && my >= y && my < y + cellH) {
                    saveOne(i);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            this.scrollY = Mth.clamp(this.scrollY - (int) (scrollY * SCROLL_STEP), 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mx, my, scrollX, scrollY);
    }

    /** Save one photo (no-op if already saved this session). */
    private void saveOne(int index) {
        if (saved.contains(index)) return;
        try {
            Path p = RideSnapshotExporter.save(shots.get(index));
            saved.add(index);
            setStatus(Component.translatable("gui.dungeontrain.death.gallery.status_saved",
                    p.getFileName().toString()), STATUS_OK);
        } catch (Exception e) {
            LOGGER.warn("[DungeonTrain] Failed to save ride photo {}", index, e);
            setStatus(Component.translatable("gui.dungeontrain.death.gallery.status_failed"), STATUS_ERR);
        }
    }

    /** Save every not-yet-saved photo. */
    private void saveAll() {
        int ok = 0, fail = 0;
        for (int i = 0; i < shots.size(); i++) {
            if (saved.contains(i)) continue;
            try {
                RideSnapshotExporter.save(shots.get(i));
                saved.add(i);
                ok++;
            } catch (Exception e) {
                LOGGER.warn("[DungeonTrain] Failed to save ride photo {}", i, e);
                fail++;
            }
        }
        if (fail > 0 && ok == 0) {
            setStatus(Component.translatable("gui.dungeontrain.death.gallery.status_failed"), STATUS_ERR);
        } else if (ok > 0) {
            setStatus(Component.translatable("gui.dungeontrain.death.gallery.status_saved_count", ok), STATUS_OK);
        }
    }

    private void openFolder() {
        Util.getPlatform().openUri(RideSnapshotExporter.screenshotsDir().toUri());
    }

    private void setStatus(Component text, int color) {
        this.status = text;
        this.statusColor = color;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
