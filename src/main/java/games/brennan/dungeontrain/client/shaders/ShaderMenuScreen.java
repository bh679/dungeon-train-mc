package games.brennan.dungeontrain.client.shaders;

import games.brennan.dungeontrain.client.analytics.UiAnalytics;
import games.brennan.dungeontrain.client.menu.DarkTintedButton;
import games.brennan.dungeontrain.client.shader.IrisPackControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>Shaders</b> page, opened from the title screen between Mods and Options.
 *
 * <p>Nine Iris packs are supported — the ones measured to load and to render Dungeon Train's bands
 * and dimensional carriages correctly ({@code docs/shaders/compat-matrix.md}). Before this page the
 * only way to find that out was to read the repository, and running one meant knowing Iris exists,
 * finding a pack site, guessing at a build, and locating the game directory. Here it is a click.</p>
 *
 * <p>Every preview is the same scene, camera, and time of day, captured by
 * {@code ShaderSweep}'s preview site — so the column compares packs rather than compares
 * screenshots. A pack with no preview in the jar draws a placeholder rather than the missing-texture
 * checkerboard.</p>
 *
 * <p>The action button is the one piece of state the page really carries: <b>Download &amp;
 * Install</b> when the pinned zip is not in {@code shaderpacks/}, <b>Install</b> when it is,
 * a progress bar while it is being fetched, and nothing to press once it is active. Without Iris
 * the page still lists and previews everything, with the actions disabled and a line saying so —
 * installing Iris itself is a mod jar and a restart, which is not this page's job.</p>
 */
public final class ShaderMenuScreen extends Screen {

    private static final int MARGIN = 16;
    private static final int GAP = 8;
    private static final int TOP = 32;
    private static final int BOTTOM_ROW_H = 20;
    private static final int LIST_W = 160;
    private static final int BUTTON_H = 20;

    /** The capture script writes every preview at this size; see {@code scripts/shaders/}. */
    private static final int PREVIEW_W = 854;
    private static final int PREVIEW_H = 480;

    private static final int PANE_BG = 0x66000000;
    private static final int PLACEHOLDER_BG = 0x33FFFFFF;
    private static final int SUB_COLOUR = 0xFF9A9A9A;
    private static final int WARN_COLOUR = 0xFFE0B56A;
    private static final int ERROR_COLOUR = 0xFFCF5C5C;
    private static final int OK_COLOUR = 0xFF7FDD7F;
    private static final int BAR_BG = 0xFF303030;
    private static final int BAR_FILL = 0xFF5B9BFF;

    private final Screen parent;
    /** Whether each preview texture is actually in the jar — asked once per pack, not per frame. */
    private final Map<String, Boolean> previewPresent = new HashMap<>();

    private ShaderPackList list;
    private Button action;
    private Button packPage;
    private ShaderPackList.Row selected;

    public ShaderMenuScreen(Screen parent) {
        super(Component.translatable("gui.dungeontrain.shaders.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int paneX = MARGIN + LIST_W + GAP;
        int paneW = this.width - paneX - MARGIN;
        int contentBottom = this.height - MARGIN - BOTTOM_ROW_H - GAP;

        list = new ShaderPackList(this.font, MARGIN, TOP, LIST_W, contentBottom - TOP, this::onSelect);
        addRenderableWidget(list);

        // Keep the selection across a resize; otherwise start on the running pack, so opening the
        // page tells you what you are already using rather than making you go and find it.
        if (selected == null) {
            selected = initialSelection();
        }
        list.select(selected);

        int actionW = Math.min(200, paneW);
        int actionY = contentBottom - BUTTON_H;
        action = addRenderableWidget(new DarkTintedButton(
                paneX, actionY, actionW, BUTTON_H, CommonComponents.EMPTY, b -> onAction()));

        packPage = addRenderableWidget(new DarkTintedButton(
                paneX, actionY - BUTTON_H - 4, actionW, BUTTON_H,
                Component.translatable("gui.dungeontrain.shaders.page"), b -> openPackPage(),
                0.4F, 0.6F, 1.0F));

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - MARGIN - BOTTOM_ROW_H, 200, BOTTOM_ROW_H)
                .build());

        refreshAction();
    }

    /** The active pack if it is one of ours, otherwise the "Shaders off" row. */
    private ShaderPackList.Row initialSelection() {
        List<ShaderPackList.Row> rows = list.rows();
        for (ShaderPackList.Row row : rows) {
            if (row.pack() != null && ShaderPackLibrary.active(row.pack())) {
                return row;
            }
        }
        return rows.get(0);
    }

    private void onSelect(ShaderPackList.Row row) {
        selected = row;
        refreshAction();
    }

    /**
     * The action button's label and enabled state, recomputed whenever the answer could have moved —
     * a new selection, a finished download, an applied pack.
     */
    private void refreshAction() {
        if (action == null) {
            return;
        }
        boolean iris = IrisPackControl.available();
        ShaderPack pack = selected == null ? null : selected.pack();
        packPage.visible = pack != null;

        if (pack == null) {
            action.setMessage(Component.translatable("gui.dungeontrain.shaders.action.off"));
            action.active = iris && !ShaderPackLibrary.shadersOff();
            return;
        }
        switch (ShaderPackLibrary.stateOf(pack)) {
            case ACTIVE -> {
                action.setMessage(Component.translatable("gui.dungeontrain.shaders.action.active"));
                action.active = false;
            }
            case DOWNLOADING -> {
                action.setMessage(Component.translatable("gui.dungeontrain.shaders.action.downloading"));
                action.active = false;
            }
            case INSTALLED -> {
                action.setMessage(Component.translatable("gui.dungeontrain.shaders.action.install"));
                action.active = iris;
            }
            default -> {
                action.setMessage(Component.translatable("gui.dungeontrain.shaders.action.download"));
                action.active = iris;
            }
        }
    }

    private void onAction() {
        ShaderPack pack = selected == null ? null : selected.pack();
        if (pack == null) {
            UiAnalytics.click(UiAnalytics.SURFACE_SHADERS, UiAnalytics.TARGET_SHADERS_OFF);
            IrisPackControl.disable();
            refreshAction();
            return;
        }
        if (ShaderPackLibrary.installed(pack)) {
            apply(pack);
            return;
        }
        UiAnalytics.click(UiAnalytics.SURFACE_SHADERS, UiAnalytics.TARGET_SHADER_DOWNLOAD);
        ShaderPackDownloader.start(pack, ok -> Minecraft.getInstance().execute(() -> {
            ShaderPackLibrary.invalidate();
            if (ok) {
                apply(pack);
            } else {
                refreshAction();
            }
        }));
        refreshAction();
    }

    private void apply(ShaderPack pack) {
        UiAnalytics.click(UiAnalytics.SURFACE_SHADERS, UiAnalytics.TARGET_SHADER_APPLY);
        IrisPackControl.apply(pack.filename());
        refreshAction();
    }

    private void openPackPage() {
        ShaderPack pack = selected == null ? null : selected.pack();
        if (pack == null) {
            return;
        }
        ConfirmLinkScreen.confirmLinkNow(this, pack.page());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        int paneX = MARGIN + LIST_W + GAP;
        int paneW = this.width - paneX - MARGIN;
        int paneBottom = packPage.getY() - GAP;
        renderDetail(g, paneX, TOP, paneW, paneBottom - TOP);

        // A download reports progress on its own thread; the button's label is only refreshed when
        // something happens, so the bar under it is what actually moves.
        ShaderPack pack = selected == null ? null : selected.pack();
        if (pack != null && ShaderPackDownloader.isDownloading(pack)) {
            renderProgress(g, action.getX(), action.getY() + action.getHeight() - 3,
                    action.getWidth(), ShaderPackDownloader.progress(pack));
        }
    }

    private void renderDetail(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANE_BG);
        ShaderPack pack = selected == null ? null : selected.pack();

        int previewH = Math.min(Math.round(w * (float) PREVIEW_H / PREVIEW_W), h - this.font.lineHeight * 5);
        previewH = Math.max(previewH, 40);
        if (pack == null) {
            drawPlaceholder(g, x, y, w, previewH,
                    Component.translatable("gui.dungeontrain.shaders.off.preview"));
        } else if (hasPreview(pack)) {
            drawContain(g, pack.preview(), x, y, w, previewH);
        } else {
            drawPlaceholder(g, x, y, w, previewH,
                    Component.translatable("gui.dungeontrain.shaders.no_preview"));
        }

        int textY = y + previewH + 6;
        int textX = x + 6;
        int textW = w - 12;
        if (pack == null) {
            g.drawString(this.font, Component.translatable("gui.dungeontrain.shaders.off"), textX, textY, 0xFFFFFF);
            drawWrapped(g, Component.translatable("gui.dungeontrain.shaders.off.body"),
                    textX, textY + this.font.lineHeight + 2, textW, SUB_COLOUR);
            return;
        }

        g.drawString(this.font, pack.name(), textX, textY, 0xFFFFFF);
        g.drawString(this.font, pack.version() + " · " + pack.author() + " · " + pack.sizeLabel(),
                textX, textY + this.font.lineHeight + 1, SUB_COLOUR);

        int statusY = textY + this.font.lineHeight * 2 + 4;
        drawWrapped(g, statusLine(pack), textX, statusY, textW, statusColour(pack));
    }

    private Component statusLine(ShaderPack pack) {
        if (!IrisPackControl.available()) {
            return Component.translatable("gui.dungeontrain.shaders.needs_iris");
        }
        String error = ShaderPackDownloader.errorFor(pack);
        if (error != null && !ShaderPackLibrary.installed(pack)) {
            return Component.translatable("gui.dungeontrain.shaders.failed", error);
        }
        return switch (ShaderPackLibrary.stateOf(pack)) {
            case ACTIVE -> Component.translatable("gui.dungeontrain.shaders.status.active");
            case INSTALLED -> Component.translatable("gui.dungeontrain.shaders.status.installed");
            case DOWNLOADING -> Component.translatable("gui.dungeontrain.shaders.status.downloading");
            default -> Component.translatable("gui.dungeontrain.shaders.status.missing", pack.sizeLabel());
        };
    }

    private int statusColour(ShaderPack pack) {
        if (!IrisPackControl.available()) {
            return WARN_COLOUR;
        }
        if (ShaderPackDownloader.errorFor(pack) != null && !ShaderPackLibrary.installed(pack)) {
            return ERROR_COLOUR;
        }
        return ShaderPackLibrary.active(pack) ? OK_COLOUR : SUB_COLOUR;
    }

    private void drawWrapped(GuiGraphics g, Component text, int x, int y, int width, int colour) {
        for (var line : this.font.split(text, width)) {
            g.drawString(this.font, line, x, y, colour);
            y += this.font.lineHeight;
        }
    }

    /** Contain-fit, so a preview is never cropped or stretched — same shape as the ride gallery. */
    private void drawContain(GuiGraphics g, ResourceLocation texture, int cx, int cy, int cw, int ch) {
        float imgAspect = (float) PREVIEW_W / PREVIEW_H;
        float cellAspect = (float) cw / Math.max(1, ch);
        int dw;
        int dh;
        if (cellAspect > imgAspect) {
            dh = ch;
            dw = Math.round(ch * imgAspect);
        } else {
            dw = cw;
            dh = Math.round(cw / imgAspect);
        }
        g.blit(texture, cx + (cw - dw) / 2, cy + (ch - dh) / 2, dw, dh,
                0.0F, 0.0F, PREVIEW_W, PREVIEW_H, PREVIEW_W, PREVIEW_H);
    }

    private void drawPlaceholder(GuiGraphics g, int x, int y, int w, int h, Component text) {
        g.fill(x, y, x + w, y + h, PLACEHOLDER_BG);
        g.drawCenteredString(this.font, text, x + w / 2, y + h / 2 - this.font.lineHeight / 2, SUB_COLOUR);
    }

    private void renderProgress(GuiGraphics g, int x, int y, int w, float fraction) {
        g.fill(x, y, x + w, y + 3, BAR_BG);
        g.fill(x, y, x + Math.round(w * fraction), y + 3, BAR_FILL);
    }

    private boolean hasPreview(ShaderPack pack) {
        return previewPresent.computeIfAbsent(pack.id(), id ->
                Minecraft.getInstance().getResourceManager().getResource(pack.preview()).isPresent());
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }
}
